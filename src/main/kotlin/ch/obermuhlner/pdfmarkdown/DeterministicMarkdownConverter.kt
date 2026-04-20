package ch.obermuhlner.pdfmarkdown

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Deterministic rule-based PDF-to-Markdown converter.
 *
 * Converts lists of [TextElement] (extracted from PDF pages) into Markdown text
 * using spatial and typographic heuristics — no LLM required.
 *
 * Rules implemented:
 * - Page number filtering
 * - Two-column layout detection
 * - Drop-initial merging
 * - Heading detection (font size + style + text pattern, all 6 Markdown levels)
 * - Paragraph joining (y-gap + font continuity) with hyphenation repair
 * - Italic → *…*, bold (non-heading) → **…**, bold-italic → ***…***
 * - Code blocks (monospace font → fenced ```)
 * - Indented list items (x ≥ bodyMargin + 20)
 * - Advisory callouts (Note:, Warning:, …)
 * - Epigraph / block-quote detection
 * - Table detection (x-clustered rows with ≥2 columns and ≥3 rows)
 * - ToC entry normalisation (strip dot/underscore leaders)
 */
object DeterministicMarkdownConverter {

    // ─── Compiled patterns (created from RuleTuning) ───────────────────────────

    /**
     * Compiled patterns derived from RuleTuning.
     * Created once per conversion run.
     */
    data class CompiledPatterns(
        val bulletPrefixRegex: Regex,
        val advisoryRegex: Regex,
    )

    /**
     * Creates CompiledPatterns from RuleTuning values.
     */
    private fun compilePatterns(tuning: RuleTuning): CompiledPatterns {
        val bulletChars = tuning.bulletPrefixChars.map { 
            if (it == '-') "-"
            else Regex.escape(it.toString()) 
        }.joinToString("")
        val bulletRegex = Regex("""^[$bulletChars]\s""")

        val advisoryRegex = Regex(
            """^(${tuning.advisoryLabels}):\s*""",
            RegexOption.IGNORE_CASE
        )

        return CompiledPatterns(bulletRegex, advisoryRegex)
    }

    // ─── Regex constants (defaults) ──────────────────────────────────────────────

    /** Isolated page-number strings with no body content. */
    private val PAGE_NUMBER_REGEX = Regex(
        // Bare 1–4-digit page numbers (optional surrounding spaces)
        """^[\s]*\d{1,4}[\s]*$""" +
        // Numbers decorated with separator characters on at least one side
        """|^[\s]*[\-–—|]+[\s\-–—|]*\d+[\s\-–—|]*$""" +
        """|^[\s\-–—|]*\d+[\s]*[\-–—|]+[\s]*$""" +
        // "N / M" fraction form
        """|\d+\s*/\s*\d+"""
    )

    /** Bullet-marker prefix: •, ■, -, *, □, · followed by a space. */
    private val DEFAULT_BULLET_PREFIX_REGEX = Regex("""^[•■\*□·]\s|^-\s""")

    /** Numbered/lettered item prefix: "1. ", "2) ", "a. " etc. */
    private val NUMBERED_PREFIX_REGEX = Regex("""^\d+[.)]\s|^[a-z][.)]\s""")

    private val DEFAULT_ADVISORY_REGEX = Regex(
        """^(Note|Warning|Tip|Important|Caution|Remark):\s*""",
        RegexOption.IGNORE_CASE
    )

    /** "1. Title", "12.Title", "2.The" — numbered section heading candidate (bold required). */
    private val NUMBERED_H2_REGEX = Regex("""^\d+\.\D""")

    /** "1.2" or "1.2.3" — numbered sub-section heading candidate. */
    private val NUMBERED_H3_REGEX = Regex("""^\d+\.\d""")

    /** Three or more consecutive dots or underscores ⇒ ToC entry leader characters. */
    private val TOC_LEADERS = Regex("""[._]{3,}""")

    /** Trailing spaces/dots/underscores + page number at end of a ToC line. */
    private val TOC_PAGE_NUMBER = Regex("""[\s._]+\d+\s*$""")

    /** "valid as of DD Month YYYY" — standalone date line that should not merge with others. */
    private val VALID_AS_OF_REGEX = Regex("""^valid as of\s+\d+\s+\w+\s+\d{4}""", RegexOption.IGNORE_CASE)

    // ─── Internal block types ─────────────────────────────────────────────────

    sealed class Block {
        abstract val minY: Int

        data class Heading(val level: Int, val text: String, val source: TextElement) : Block() {
            override val minY get() = source.y
        }

        data class Paragraph(val lines: List<TextElement>) : Block() {
            override val minY get() = lines.first().y
        }

        data class ListItems(val items: List<TextElement>) : Block() {
            override val minY get() = items.first().y
        }

        data class CodeBlock(val lines: List<TextElement>) : Block() {
            override val minY get() = lines.first().y
        }

        data class Advisory(val label: String, val rest: String, val source: TextElement) : Block() {
            override val minY get() = source.y
        }

        data class Epigraph(
            val lines: List<TextElement>,
            val attribution: TextElement?
        ) : Block() {
            override val minY get() = lines.first().y
        }
    }

    data class TableRegion(
        val allElements: List<TextElement>,
        val minY: Int,
        val headerRow: List<String>,
        val dataRows: List<List<String>>,
        val colCount: Int
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Convert all pages of a document.
     *
     * @param pageElementsList  one inner list per page (repeated headers/footers already stripped)
     * @param docModeFontSize   document-wide body font size for CSS-keyword normalisation
     * @return                  one markdown string per page
     */
    fun convertDocument(
        pageElementsList: List<List<TextElement>>,
        docModeFontSize: Int,
        options: ConversionOptions = ConversionOptions.READABLE,
    ): List<String> {
        var titleUsed = false
        return pageElementsList.mapIndexed { idx, els ->
            val pageMode = els.groupingBy { it.fontSize }.eachCount()
                .maxByOrNull { it.value }?.key ?: docModeFontSize
            val md = convertPage(
                elements = els,
                modeFontSize = pageMode,
                isFirstPage = idx == 0,
                titleAlreadyUsed = titleUsed,
                options = options,
            )
            if (md.lines().any { it.startsWith("# ") }) titleUsed = true
            md
        }
    }

    /**
     * Convert a single page's element list to Markdown.
     */
    fun convertPage(
        elements: List<TextElement>,
        modeFontSize: Int,
        isFirstPage: Boolean = false,
        titleAlreadyUsed: Boolean = false,
        options: ConversionOptions = ConversionOptions.READABLE,
    ): String {
        if (elements.isEmpty()) return ""

        // Filter page numbers, but guard against false positives: bare 1–4-digit numbers
        // (e.g. "120") match the page-number pattern but may be valid table cell values.
        // Only treat them as page numbers when they appear in a small font (xx-small, x-small,
        // or small relative to the boconsiderdy size), which is typical for footer page numbers.
        val visible = elements.filter { el ->
            !isPageNumber(el.text) ||
            fontSizeToCssKeyword(el.fontSize, modeFontSize) !in setOf("xx-small", "x-small", "small")
        }
        if (visible.isEmpty()) return ""

        // On the first page the title candidate is the element with the largest font size
        // (only if it exceeds the body-text size by the configured ratio).
        val tuning = options.ruleTuning
        val patterns = compilePatterns(tuning)
        val titleCandidateFontSize: Int? = if (isFirstPage && !titleAlreadyUsed) {
            visible.maxByOrNull { it.fontSize }?.fontSize
                ?.takeIf { it > modeFontSize * tuning.titleMinRatio }
        } else null

        val bodyMargin = computeBodyMargin(visible)
        val colBounds  = detectColumnBoundaries(visible, tuning)
        val columns    = splitIntoColumns(visible, colBounds)

        val titleUsed = titleAlreadyUsed

        // Per-column table detection and block building.
        // colProcessed: pre-computed per-column elements (mergeDropInitials applied ONCE per column).
        // Reusing these objects ensures identity-set comparisons work correctly in cross-column merging.
        data class ColResult(
            val col: Int,
            val tableRegions: List<TableRegion>,
            val blocks: List<Block>,
            val processed: List<TextElement>,
        )
        val colProcessed = columns.map { col -> mergeDropInitials(col.sortedBy { it.y }, tuning) }
        val colResults = colProcessed.mapIndexed { colIdx, withInitials ->
            val tableRegions = detectTableRegions(withInitials, options)
            val inTable      = withInitials.filter { el ->
                tableRegions.any { tr -> tr.allElements.any { it === el } }
            }.toSet()
            val prose = withInitials.filter { it !in inTable }

            val blocks = buildBlocks(prose, bodyMargin, modeFontSize, isFirstPage, titleUsed, titleCandidateFontSize, options, patterns)
            ColResult(colIdx, tableRegions, blocks, withInitials)
        }

        // ── Cross-column table handling ───────────────────────────────────────
        // Two mechanisms handle tables split across layout columns:
        //
        // (A) Left-column extension: a table detected in column C has its leftmost
        //     column in column C-1 (companion matching ≥50% of y-rows → prepend).
        //
        // (B) Pair-wise detection: adjacent columns that have NO independent tables
        //     are merged and subjected to table detection, catching tables whose
        //     columns straddle the boundary (e.g. Cash Position).
        //
        // Suppressed elements (those consumed by a cross-column table) are tracked
        // by (x, y, trimmedText) triples — more robust than identity comparison.
        data class ElemKey(val x: Int, val y: Int, val text: String)
        fun TextElement.key() = ElemKey(x, y, this.text.trim())

        val crossColumnTables = mutableListOf<TableRegion>()      // new tables from pair-wise detection
        val extendedTableRegions = mutableListOf<Pair<Int, TableRegion>>() // (tr.minY, extended)
        val suppressedKeys = mutableSetOf<ElemKey>()

        if (colBounds.size > 1) {
            // ── (A) Left-column extension ─────────────────────────────────────
            for (colIdx in colResults.indices) {
                for (tr in colResults[colIdx].tableRegions) {
                    val leftColIdx = colIdx - 1
                    if (leftColIdx < 0) continue
                    val adjElements = colResults[leftColIdx].processed

                    val trYRows = groupByYRows(tr.allElements, tuning.tableRowTolerance)
                    val trYPositions = trYRows.map { row -> row.minOf { it.y } }

                    val adjGrouped = groupByYRows(adjElements, tuning.tableRowTolerance)
                    val companions = adjGrouped.filter { adjRow ->
                        val adjY = adjRow.minOf { it.y }
                        trYPositions.any { ty -> abs(adjY - ty) <= tuning.tableRowTolerance * 2 }
                    }

                    val adjTableKeys = colResults[leftColIdx].tableRegions
                        .flatMap { it.allElements }.map { it.key() }.toSet()
                    val validCompanions = companions.filter { adjRow ->
                        adjRow.all { it.text.trim().length <= 80 } &&
                        adjRow.none { it.key() in adjTableKeys } &&
                        adjRow.none { it.key() in suppressedKeys }
                    }
                    if (validCompanions.size.toDouble() / trYRows.size < 0.5) continue

                    val mergedYRows = trYPositions.map { ty ->
                        val orig = trYRows.firstOrNull { row -> row.minOf { it.y } == ty } ?: emptyList()
                        val comp = validCompanions.firstOrNull { adjRow ->
                            abs(adjRow.minOf { it.y } - ty) <= tuning.tableRowTolerance * 2
                        } ?: emptyList()
                        comp + orig
                    }
                    val merged = buildTableRegion(mergedYRows, options) ?: continue
                    extendedTableRegions.add(tr.minY to merged)
                    validCompanions.flatten().forEach { suppressedKeys.add(it.key()) }
                }
            }

            // ── (B) Pair-wise cross-column detection ──────────────────────────
            // Only runs when adjacent columns have NO independent tables — avoids
            // conflicting with per-column tables that are already correctly detected.
            for (leftIdx in 0 until colResults.size - 1) {
                val rightIdx = leftIdx + 1
                if (colResults[leftIdx].tableRegions.isNotEmpty() ||
                    colResults[rightIdx].tableRegions.isNotEmpty()) continue  // any column has tables

                val leftEls  = colResults[leftIdx].processed
                val rightEls = colResults[rightIdx].processed
                val leftKeys = leftEls.map { it.key() }.toSet()

                val mergedEls = (leftEls + rightEls).sortedBy { it.y }
                val crossTables = detectTableRegions(mergedEls, options)
                for (ct in crossTables) {
                    val hasLeft  = ct.allElements.any { it.key() in leftKeys }
                    val hasRight = ct.allElements.any { it.key() !in leftKeys }
                    if (!hasLeft || !hasRight) continue   // must span both columns
                    // Skip if already covered by an extended table at same position
                    if (extendedTableRegions.any { (_, ext) ->
                            abs(ext.minY - ct.minY) <= tuning.tableRowTolerance }) continue
                    // Require at least 3 data rows (4 total with header) for cross-column tables
                    // to avoid spurious tables from two-column layout prose content that
                    // coincidentally shares y-positions (e.g. title words aligning with list items)
                    if (ct.dataRows.size < 3) continue
                    // Reject cross-column tables containing monospace (code) elements —
                    // no legitimate table cell should be in a code/monospace font
                    if (ct.allElements.any { it.font.endsWith("-mono") }) continue
                    // Reject cross-column tables where any element starts with a bullet prefix —
                    // such elements are list items, not table cells (e.g. □ reference bullets in sidebars)
                    val bulletPrefixRegex = Regex("^[${Regex.escape(tuning.bulletPrefixChars)}]\\s")
                    if (ct.allElements.any { bulletPrefixRegex.containsMatchIn(it.text) }) continue
                    // Reject cross-column tables where any element is a section heading —
                    // such elements are column-section labels, not table cells (e.g. parallel section
                    // headings like "Speakers | Reservations" in a brochure layout)
                    if (ct.allElements.any { detectHeadingLevel(it, modeFontSize, isFirstPage, titleUsed, null, tuning.headingMediumMinRatio) > 0 }) continue
                    // Reject cross-column tables where either source column contains a sub-section
                    // heading (H2–H6) that appears BEFORE the table's y-start. Such a heading marks
                    // the column as an independent section (e.g. a "Reservations" section whose
                    // paragraph text coincidentally shares y-positions with an adjacent column's list
                    // items). H1 / title-level elements are excluded from this check because decorative
                    // large-font title words can appear in a column above a legitimate data table.
                    val hasSubsectionHeadingBeforeTable = { els: List<TextElement> ->
                        els.any { el -> el.y < ct.minY &&
                            detectHeadingLevel(el, modeFontSize, isFirstPage, titleUsed, null, tuning.headingMediumMinRatio) in 2..6 }
                    }
                    if (hasSubsectionHeadingBeforeTable(leftEls) || hasSubsectionHeadingBeforeTable(rightEls)) continue
                    crossColumnTables.add(ct)
                    ct.allElements.forEach { suppressedKeys.add(it.key()) }
                }
            }
        }

        // ── Helper: all TextElement sources for a block (for suppression check) ──
        fun blockElements(block: Block): List<TextElement> = when (block) {
            is Block.Heading   -> listOf(block.source)
            is Block.Paragraph -> block.lines
            is Block.ListItems -> block.items
            is Block.CodeBlock -> block.lines
            is Block.Advisory  -> listOf(block.source)
            is Block.Epigraph  -> block.lines + listOfNotNull(block.attribution)
        }

        // Render all column results.
        // For multi-column pages use "newspaper" reading order: complete the left column
        // before starting the right column.  Sorting all blocks by Y alone interleaves
        // columns incorrectly when their Y ranges overlap (e.g. a two-column report where
        // the right column starts at a smaller Y than the left-column title).
        // Cross-column tables span all columns; assign them to column 0 so they appear
        // at the correct Y position within the left-column section.
        data class BlockChunk(val colIdx: Int, val minY: Int, val rendered: String)
        val allChunks = mutableListOf<BlockChunk>()
        for (result in colResults) {
            for (block in result.blocks) {
                if (blockElements(block).any { it.key() in suppressedKeys }) continue
                val rendered = renderBlock(block, options, patterns)
                if (rendered.isNotEmpty()) {
                    allChunks.add(BlockChunk(result.col, block.minY, rendered))
                }
            }
            for (tr in result.tableRegions) {
                if (tr.allElements.all { it.key() in suppressedKeys }) continue
                val extended = extendedTableRegions.firstOrNull { (minY, ext) ->
                    minY == tr.minY && ext.colCount > tr.colCount
                }?.second
                val rendered = if (extended != null) {
                    renderTableRegion(extended, options)
                } else {
                    renderTableRegion(tr, options)
                }
                if (rendered.isNotEmpty()) {
                    allChunks.add(BlockChunk(result.col, tr.minY, rendered))
                }
            }
        }
        for (ct in crossColumnTables) {
            val rendered = renderTableRegion(ct, options)
            if (rendered.isNotEmpty()) {
                allChunks.add(BlockChunk(0, ct.minY, rendered))
            }
        }

        if (colBounds.size > 1) {
            // Multi-column: newspaper reading order (col 0 → col 1 → …), Y within each column.
            allChunks.sortWith(compareBy({ it.colIdx }, { it.minY }))
        } else {
            allChunks.sortBy { it.minY }
        }
        return allChunks.joinToString("\n\n") { it.rendered }.trim()
    }

    // ─── Page-number filter ───────────────────────────────────────────────────

    fun isPageNumber(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.length <= 20 && PAGE_NUMBER_REGEX.containsMatchIn(t)
    }

    // ─── Body-margin computation ──────────────────────────────────────────────

    private fun computeBodyMargin(elements: List<TextElement>): Int {
        val xs = elements.filter { !it.font.endsWith("-mono") }.map { it.x }
        if (xs.isEmpty()) return 0
        val xCounts = xs.groupingBy { it }.eachCount()
        // Use the LEFTMOST x that appears ≥2 times.  This makes indented list items
        // (to the right of the page margin) reliably detectable even when headings or
        // list items are the most-frequent x value in the document.
        return xCounts.filter { it.value >= 2 }.keys.minOrNull()
            ?: xCounts.maxByOrNull { it.value }?.key ?: 0
    }

    // ─── Column detection ─────────────────────────────────────────────────────

    /**
     * Returns one [IntRange] per detected column (left-to-right).
     * Single-column pages return `listOf(0..Int.MAX_VALUE)`.
     */
    private fun detectColumnBoundaries(elements: List<TextElement>, tuning: RuleTuning): List<IntRange> {
        if (elements.size < tuning.columnDetectionMinElements) return listOf(0..Int.MAX_VALUE)

        val minX      = elements.minOf { it.x }
        val pageWidth = elements.maxOf { it.endX }
        val span      = (pageWidth - minX).coerceAtLeast(1)

        // Build the histogram from only the leftmost element per y-row.
        // This prevents table sub-columns (e.g. a "Target Year" cell at x=137
        // when body text starts at x=56) from filling in what should be a clean
        // inter-column gap, while still giving every page column representation
        // (rows where only a right-column element exists still produce an anchor).
        val anchorXStarts = elements.groupBy { it.y }.values.map { row -> row.minOf { it.x } }

        // Histogram of anchor x-start positions
        val buckets = tuning.columnHistogramBuckets
        val bw      = span.toDouble() / buckets
        val hist    = IntArray(buckets)
        for (x in anchorXStarts) hist[((x - minX) / bw).toInt().coerceIn(0, buckets - 1)]++

        // Find contiguous empty bands in middle portion of page
        val lo = (buckets * tuning.columnMarginFraction).toInt()
        val hi = (buckets * (1 - tuning.columnMarginFraction)).toInt()

        val splitPoints = mutableListOf<Int>()
        var gapStart    = -1
        for (b in lo..hi) {
            if (hist[b] == 0) {
                if (gapStart < 0) gapStart = b
            } else {
                if (gapStart >= 0 && b - gapStart >= tuning.columnMinGapBuckets) {
                    val midX = minX + ((gapStart + b - 1) / 2.0 * bw).toInt()
                    splitPoints.add(midX)
                }
                gapStart = -1
            }
        }
        // flush trailing gap
        if (gapStart >= 0 && hi - gapStart >= tuning.columnMinGapBuckets) {
            val midX = minX + ((gapStart + hi) / 2.0 * bw).toInt()
            splitPoints.add(midX)
        }

        if (splitPoints.isEmpty()) return listOf(0..Int.MAX_VALUE)

        // Require each column to have enough elements (absolute + relative threshold).
        val total      = elements.size
        val minColSize = maxOf(tuning.columnMinSizeAbsolute, (total * tuning.columnMinSizeFraction).toInt())

        /** Returns true when enough of [els] have x within distance of the mode x. */
        fun isCoherentColumn(els: List<TextElement>): Boolean {
            if (els.isEmpty()) return false
            val modeX    = els.groupingBy { it.x }.eachCount().maxByOrNull { it.value }?.key ?: return false
            val nearCount = els.count { abs(it.x - modeX) <= tuning.columnCoherenceXDistance }
            return nearCount >= els.size * tuning.columnCoherenceMinFraction
        }

        fun buildRanges(splits: List<Int>): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var start  = 0
            for (sp in splits) { ranges.add(start..sp); start = sp + 1 }
            ranges.add(start..Int.MAX_VALUE)
            return ranges
        }

        /** Returns true when every non-empty column in [splits] is large enough and coherent. */
        fun validatePartition(splits: List<Int>): Boolean =
            buildRanges(splits).all { r ->
                val els = elements.filter { it.x in r }
                els.isEmpty() || (els.size >= minColSize && isCoherentColumn(els))
            }

        // First try the full set of detected gaps (supports 3-column layouts).
        // If that fails, fall back to trying each gap individually (2-column layouts).
        // Independent per-gap validation (old approach) fails for 3-column pages because
        // it lumps two columns together on one side of the tested gap.
        if (validatePartition(splitPoints)) return buildRanges(splitPoints)

        for (sp in splitPoints) {
            if (validatePartition(listOf(sp))) return buildRanges(listOf(sp))
        }

        return listOf(0..Int.MAX_VALUE)
    }

    private fun splitIntoColumns(
        elements: List<TextElement>,
        colBounds: List<IntRange>
    ): List<List<TextElement>> {
        if (colBounds.size == 1) return listOf(elements)
        val cols = colBounds.map { mutableListOf<TextElement>() }
        for (el in elements) {
            val idx = colBounds.indexOfFirst { el.x in it }
            (if (idx >= 0) cols[idx] else cols.last()).add(el)
        }
        return cols.filter { it.isNotEmpty() }
    }

    // ─── Drop-initial merging ─────────────────────────────────────────────────

    /**
     * Detects typographic drop initials — short letter fragments at a
     * narrow x across multiple consecutive y-rows — and prepends each to its companion
     * element on the same row.
     */
    private fun mergeDropInitials(elements: List<TextElement>, tuning: RuleTuning): List<TextElement> {
        if (elements.size < 2) return elements

        // Find x values that host only short letter fragments across multiple rows
        val dropXs = elements
            .groupBy { it.x }
            .filter { (_, els) ->
                els.size >= tuning.dropInitialMinRows &&
                els.all { it.text.trim().length in 1..tuning.dropInitialMaxLength && it.text.any(Char::isLetter) }
            }
            .keys.toSet()

        if (dropXs.isEmpty()) return elements

        val skipSet = identitySetOf<TextElement>()
        val result  = mutableListOf<TextElement>()

        for (el in elements) {
            if (el in skipSet) continue
            if (el.x in dropXs) {
                // Look for a companion element to the right on the same y-row
                val companion = elements.firstOrNull { other ->
                    other !in skipSet &&
                    other !== el &&
                    abs(other.y - el.y) <= (el.height * tuning.dropInitialYDistanceFraction).toInt() &&
                    other.x > el.endX &&
                    other.x - el.endX <= (el.height * tuning.dropInitialXDistanceMultiplier).toInt()
                }
                if (companion != null) {
                    skipSet.add(el)
                    skipSet.add(companion)
                    result.add(companion.copy(x = el.x, text = el.text.trimEnd() + companion.text))
                    continue
                }
            }
            result.add(el)
        }
        return result
    }

    // ─── Block building ───────────────────────────────────────────────────────

    private fun buildBlocks(
        elements: List<TextElement>,
        bodyMargin: Int,
        modeFontSize: Int,
        isFirstPage: Boolean,
        initialTitleUsed: Boolean,
        titleCandidateFontSize: Int? = null,
        options: ConversionOptions = ConversionOptions.READABLE,
        patterns: CompiledPatterns,
    ): List<Block> {
        val blocks    = mutableListOf<Block>()
        var i         = 0
        var titleUsed = initialTitleUsed
        val tuning    = options.ruleTuning
        val bulletRegex = patterns.bulletPrefixRegex
        val advisoryRegex = patterns.advisoryRegex

        while (i < elements.size) {
            val el   = elements[i]
            val text = el.text.trim()
            val isMono = el.font.endsWith("-mono")

            // ── Code block ──────────────────────────────────────────────────
            if (isMono) {
                val codeLines = mutableListOf(el)
                var j = i + 1
                while (j < elements.size) {
                    val next = elements[j]
                    val yGap = next.y - (codeLines.last().y + codeLines.last().height)
                    if (next.font.endsWith("-mono") && yGap <= codeLines.last().height * tuning.codeYGapMultiplier) {
                        codeLines.add(next); j++
                    } else break
                }
                blocks.add(Block.CodeBlock(codeLines))
                i = j
                continue
            }

            // ── Heading ─────────────────────────────────────────────────────
            val headingLevel = detectHeadingLevel(el, modeFontSize, isFirstPage, titleUsed, titleCandidateFontSize, tuning.headingMediumMinRatio)
            if (headingLevel > 0) {
                // Merge consecutive lines that belong to the same heading
                // (multi-line titles / wrapped section headers).
                val headingParts = mutableListOf(text)
                var j = i + 1
                while (j < elements.size) {
                    val next     = elements[j]
                    val yGap     = next.y - (elements[j - 1].y + elements[j - 1].height)
                    val sameFont = next.font == el.font && abs(next.fontSize - el.fontSize) <= tuning.headingMergeMaxFontSizeDiff
                    if (sameFont && yGap <= el.height * tuning.headingMergeMaxYGapMultiplier) {
                        headingParts.add(next.text.trim()); j++
                    } else break
                }
                val headingText = headingParts.joinToString(" ")

                if (TOC_LEADERS.containsMatchIn(headingText)) {
                    if (options.includeToc) {
                        val clean = headingText.replace(TOC_LEADERS, "")
                            .replace(TOC_PAGE_NUMBER, "").trim()
                        blocks.add(Block.Paragraph(listOf(el.copy(text = clean))))
                    }
                } else {
                    if (headingLevel == 1) titleUsed = true
                    blocks.add(Block.Heading(headingLevel, headingText, el))
                }
                i = j
                continue
            }

            // ── Advisory callout ────────────────────────────────────────────
            val advMatch = advisoryRegex.find(text)
            if (advMatch != null && (el.font.contains("bold") || el.font.contains("italic"))) {
                val label = advMatch.groupValues[1].replaceFirstChar { it.uppercase() }
                val rest  = text.removePrefix(advMatch.value)
                blocks.add(Block.Advisory(label, rest, el))
                i++
                continue
            }

            // ── List items ──────────────────────────────────────────────────
            // Only treat as indented if within configurable range of the body margin.
            // A larger gap means the element is in a separate layout zone (e.g. a
            // different column or table cell), not a list item.
            if (el.x >= bodyMargin + tuning.listMinIndent && el.x <= bodyMargin + tuning.listMaxIndent) {
                val items = mutableListOf(el)
                var j = i + 1
                while (j < elements.size) {
                    val next  = elements[j]
                    val yGap  = next.y - (elements[j - 1].y + elements[j - 1].height)
                    if (abs(next.x - el.x) <= tuning.listXVariance && yGap <= elements[j - 1].height * tuning.listYGapMultiplier) {
                        items.add(next); j++
                    } else break
                }
                if (items.size >= 2) {
                    blocks.add(Block.ListItems(items))
                    i = j
                    continue
                }
                // Single indented element — fall through to paragraph
            }

            // ── Bullet / numbered list items (by text-content marker) ────────
            if (hasBulletOrNumberedPrefix(text, bulletRegex)) {
                val items = mutableListOf(el)
                var j = i + 1
                while (j < elements.size) {
                    val next = elements[j]
                    val yGap = next.y - (elements[j - 1].y + elements[j - 1].height)
                    // Continue list if next line also has a bullet/number prefix OR
                    // it continues the current item at a deeper x-indent with the same font
                    val isContinuation = !hasBulletOrNumberedPrefix(next.text.trim(), bulletRegex) &&
                                         next.font == el.font &&
                                         next.x > el.x &&
                                         yGap <= elements[j - 1].height * tuning.listContinuationYGapMultiplier
                    val isNextItem = hasBulletOrNumberedPrefix(next.text.trim(), bulletRegex) &&
                                     yGap <= elements[j - 1].height * tuning.bulletListYGapMultiplier
                    if (isNextItem || isContinuation) {
                        items.add(next); j++
                    } else break
                }
                blocks.add(Block.ListItems(items))
                i = j
                continue
            }

            // ── Epigraph ─────────────────────────────────────────────────────
            // Fire on small italic text (original heuristic), OR on medium italic
            // text that begins with an opening-quote character or em-dash attribution
            // (signals a displayed quotation, not regular body prose).
            // Guard: text starting with "|" is an embedded table row — never treat as epigraph.
            val epSz = fontSizeToCssKeyword(el.fontSize, modeFontSize)
            val isMediumQuote = epSz == "medium" &&
                (el.font == "italic" || el.font == "bold-italic") &&
                el.text.trimStart().let {
                    it.startsWith('"') || it.startsWith('\u201c') ||
                    it.startsWith('—') || it.startsWith('–')
                }
            if (!el.text.trimStart().startsWith("|") &&
                (el.font == "italic" || el.font == "bold-italic") &&
                (isSmallSize(epSz) || isMediumQuote)) {
                val epLines = mutableListOf(el)
                var j = i + 1
                while (j < elements.size) {
                    val next = elements[j]
                    val sz   = fontSizeToCssKeyword(next.fontSize, modeFontSize)
                    val yGap = next.y - (epLines.last().y + epLines.last().height)
                    // Allow configurable gap multiplier and accept both sizes (last line may differ slightly).
                    // Guard: stop absorbing "|"-starting lines — those are embedded table rows.
                    if (!next.text.trimStart().startsWith("|") &&
                        (next.font == "italic" || next.font == "bold-italic") &&
                        (isSmallSize(sz) || sz == "medium") &&
                        yGap <= epLines.last().height * tuning.epigraphYGapMultiplier) {
                        epLines.add(next); j++
                    } else break
                }
                if (epLines.size >= 2) {
                    // Optional attribution line: short normal or italic line immediately after
                    var attribution: TextElement? = null
                    if (j < elements.size) {
                        val next = elements[j]
                        val sz   = fontSizeToCssKeyword(next.fontSize, modeFontSize)
                        val yGap = next.y - (epLines.last().y + epLines.last().height)
                        if ((next.font == "normal" || next.font == "italic") &&
                            (isSmallSize(sz) || sz == "medium") &&
                            next.text.trim().length < tuning.epigraphAttributionMaxLength &&
                            yGap <= epLines.last().height * tuning.epigraphAttributionYGapMultiplier) {
                            attribution = next; j++
                        }
                    }
                    blocks.add(Block.Epigraph(epLines, attribution))
                    i = j
                    continue
                }
            }

            // ── Regular paragraph ────────────────────────────────────────────
            // "valid as of" dates are standalone and should not merge with other text
            val isValidAsOf = VALID_AS_OF_REGEX.containsMatchIn(text)
            val paraLines = mutableListOf(el)
            var j = i + 1
            while (j < elements.size && !isValidAsOf) {
                val next       = elements[j]
                val prevEl     = paraLines.last()
                val yGap       = next.y - (prevEl.y + prevEl.height)
                val maxGap     = prevEl.height * tuning.paragraphMaxYGapMultiplier
                val nextMono   = next.font.endsWith("-mono")
                val nextHead   = detectHeadingLevel(next, modeFontSize, false, titleUsed, null, tuning.headingMediumMinRatio) > 0
                val nextAdv    = advisoryRegex.containsMatchIn(next.text.trim()) &&
                                 (next.font.contains("bold") || next.font.contains("italic"))
                // "valid as of" dates are standalone — don't merge into a paragraph
                val nextValidAsOf = VALID_AS_OF_REGEX.containsMatchIn(next.text.trim())
                // Prevent merging elements from different layout zones (e.g. separate columns
                // or a diagram's time column vs. its description column).  Genuine paragraph
                // continuation lines wrap at (nearly) the same left margin as the first line.
                val nextXFar   = abs(next.x - el.x) > tuning.paragraphMaxXDistance
                // A line that starts a new bullet/numbered item must not be absorbed into
                // the preceding paragraph even when it is vertically close.
                val nextBullet = hasBulletOrNumberedPrefix(next.text.trim(), bulletRegex)
                // A line that begins with "|" (embedded table-row syntax) should not be
                // merged into a preceding paragraph — it needs to render as a bare line.
                val nextIsTableRow = next.text.trim().startsWith("|")
                if (next.font == el.font && yGap <= maxGap &&
                    !nextMono && !nextHead && !nextAdv && !nextValidAsOf && !nextXFar && !nextBullet && !nextIsTableRow) {
                    paraLines.add(next); j++
                } else break
            }
            blocks.add(Block.Paragraph(paraLines))
            i = j
        }

        return blocks
    }

    // ─── Heading detection ────────────────────────────────────────────────────

    private fun detectHeadingLevel(
        el: TextElement,
        modeFontSize: Int,
        isFirstPage: Boolean,
        titleUsed: Boolean,
        titleCandidateFontSize: Int? = null,
        headingMediumMinRatio: Double = 1.05,
    ): Int {
        val text = el.text.trim()
        if (text.isBlank() || text.length > 200) return 0
        if (el.font.endsWith("-mono")) return 0

        val isBold = el.font == "bold" || el.font == "bold-italic"
        val size   = fontSizeToCssKeyword(el.fontSize, modeFontSize)

        // (1) Document title: first page, not yet used.
        // Match either the pre-computed largest-font candidate or x-large/xx-large threshold.
        if (isFirstPage && !titleUsed) {
            val isTitleBySize    = size in setOf("xx-large", "x-large")
            val isTitleByMaxFont = titleCandidateFontSize != null && el.fontSize == titleCandidateFontSize
            if (isTitleBySize || isTitleByMaxFont) return 1
        }

        // (2) Bold + "N. " → ##
        if (isBold && NUMBERED_H2_REGEX.containsMatchIn(text)) return 2

        // (3) Large + "N.N" standalone short line → ###
        if (size == "large" && NUMBERED_H3_REGEX.containsMatchIn(text) && text.length < 80) return 3

        // (3b) Non-bold "large" standalone → ##
        // Catches section headers in documents that use a larger-but-not-bold font for headings
        // (e.g. brochures where the body font is 9pt and section titles are 12pt normal-weight).
        if (size == "large" && !isBold && text.length < 80) return 2

        // (4) Bold ALL-CAPS short standalone → ##
        // Require at least 5 letters to avoid false positives on short abbreviations
        // like "(CET)" (3 letters), "CLOB" (4 letters), "QDM" (3 letters), etc.
        val hasLetters = text.any(Char::isLetter)
        val allCaps    = hasLetters && text.filter(Char::isLetter).all(Char::isUpperCase)
        if (isBold && allCaps && text.length < 80 && text.count(Char::isLetter) >= 5) return 2

        // (5) Bold x-large/xx-large standalone → ##
        if (isBold && size in setOf("x-large", "xx-large")) return 2

        // (6) H4: Bold + "large" (below x-large threshold, so not caught by H2 rule 5)
        // Numbered patterns (rule 2) are checked first and take priority.
        if (isBold && size == "large") return 4

        // (7) H5: Bold + "medium" when distinctly larger than body text
        // Uses configurable ratio to avoid catching same-size bold emphasis
        if (isBold && size == "medium" && el.fontSize > modeFontSize * headingMediumMinRatio) return 5

        return 0
    }

    private fun isSmallSize(size: String) = size in setOf("x-small", "small")

    private fun hasBulletOrNumberedPrefix(text: String, bulletRegex: Regex = DEFAULT_BULLET_PREFIX_REGEX): Boolean =
        bulletRegex.containsMatchIn(text) || NUMBERED_PREFIX_REGEX.containsMatchIn(text)

    /**
     * Removes a leading bullet character from [text], if present.
     * Numbered prefixes (1., 2), a.) are left intact — the number carries meaning.
     */
    private fun stripBulletPrefix(text: String, bulletRegex: Regex = DEFAULT_BULLET_PREFIX_REGEX): String {
        val m = bulletRegex.find(text) ?: return text
        return text.removePrefix(m.value).trimStart()
    }

    // ─── Block rendering ──────────────────────────────────────────────────────

    private fun renderBlock(block: Block, options: ConversionOptions = ConversionOptions.READABLE, patterns: CompiledPatterns? = null): String = when (block) {
        is Block.Heading -> "#".repeat(block.level) + " " + block.text

        is Block.Paragraph -> renderParagraph(block.lines, options)

        is Block.ListItems -> block.items.joinToString("\n") { item ->
            val text = item.text.trim()
            if (NUMBERED_PREFIX_REGEX.containsMatchIn(text)) text
            else "- " + stripBulletPrefix(text, patterns?.bulletPrefixRegex ?: DEFAULT_BULLET_PREFIX_REGEX)
        }

        is Block.CodeBlock -> buildString {
            appendLine("```")
            block.lines.forEach { appendLine(it.text.trim()) }
            append("```")
        }

        is Block.Advisory -> when (options.advisoryFormat) {
            ConversionOptions.AdvisoryFormat.BLOCKQUOTE -> "> **${block.label}:** ${block.rest}"
            ConversionOptions.AdvisoryFormat.PLAIN      -> "${block.label}: ${block.rest}"
        }

        is Block.Epigraph -> when (options.epigraphFormat) {
            ConversionOptions.EpigraphFormat.BLOCKQUOTE -> buildString {
                block.lines.forEachIndexed { idx, el ->
                    if (idx < block.lines.size - 1 || block.attribution != null) {
                        appendLine("> " + el.text.trim())
                    } else {
                        append("> " + el.text.trim())
                    }
                }
                block.attribution?.let { append("\n> — " + it.text.trim()) }
            }
            ConversionOptions.EpigraphFormat.PLAIN -> buildString {
                block.lines.forEachIndexed { idx, el ->
                    if (idx < block.lines.size - 1 || block.attribution != null) {
                        appendLine(el.text.trim())
                    } else {
                        append(el.text.trim())
                    }
                }
                block.attribution?.let { append("\n— " + it.text.trim()) }
            }
        }
    }

    private fun renderParagraph(lines: List<TextElement>, options: ConversionOptions = ConversionOptions.READABLE): String {
        if (lines.isEmpty()) return ""
        val joined = buildString {
            for ((idx, el) in lines.withIndex()) {
                val t = el.text.trim()
                if (idx == 0) {
                    append(t)
                } else if (endsWith("-") && t.isNotEmpty() && t[0].isLowerCase()) {
                    // Hyphenation repair: "infor-" + "mation" → "information"
                    deleteCharAt(length - 1)
                    append(t)
                } else {
                    append(" ").append(t)
                }
            }
        }
        // Text that begins with "|" represents embedded table-row syntax — don't wrap
        // it in emphasis markers so that it remains parseable as a Markdown table line.
        return if (options.stripInlineFormatting || joined.trimStart().startsWith("|")) joined
               else applyEmphasis(joined, lines.first().font)
    }

    private fun applyEmphasis(text: String, font: String): String = when (font) {
        "italic"      -> "*$text*"
        "bold"        -> "**$text**"
        "bold-italic" -> "***$text***"
        else          -> text
    }

    // ─── Table detection ──────────────────────────────────────────────────────

    private fun detectTableRegions(
        elements: List<TextElement>,
        options: ConversionOptions = ConversionOptions.READABLE,
    ): List<TableRegion> {
        val tuning = options.ruleTuning
        if (elements.size < tuning.columnDetectionMinElements) return emptyList()

        val yRows = groupByYRows(elements, tolerance = tuning.tableRowTolerance)
        if (yRows.size < tuning.tableMinRows) return emptyList()

        val result   = mutableListOf<TableRegion>()
        var runStart = -1

        fun flushRun(endExclusive: Int) {
            if (runStart < 0) return
            val runRows = yRows.subList(runStart, endExclusive)
            if (runRows.size >= tuning.tableMinRows) buildTableRegion(runRows, options)?.let { result.add(it) }
            runStart = -1
        }

        for ((idx, row) in yRows.withIndex()) {
            val cols       = countXClusters(row, minGap = tuning.tableMinColumnGap)
            val shortCells = row.all { it.text.trim().length <= 80 }
            // Skip rows with many x-clusters (>15) at the start of a run - these are
            // sub-header rows that would mess up column detection for the actual data rows
            val isSubHeaderRow = cols > 15
            // A single bold/bold-italic element inside an active run is treated as a
            // section-header divider (e.g. "Bond Market" spanning a multi-section table).
            // We keep it in the run so the rows below still share the full column layout
            // derived from the rows above; it renders as a spanning bold row in the table.
            // Guard: the y-gap from the previous row must not exceed the threshold —
            // large gaps indicate that this bold element is a heading after the table, not
            // a section divider within it.
            val prevRowBottomY = if (idx > 0) yRows[idx - 1].maxOf { it.y + it.height } else Int.MIN_VALUE
            val yGapFromPrev = row.minOf { it.y } - prevRowBottomY
            val prevRowMaxHeight = if (idx > 0) yRows[idx - 1].maxOf { it.height } else 0
            val isSingleBoldDivider = runStart >= 0
                && row.size == 1
                && (row[0].font == "bold" || row[0].font == "bold-italic")
                && (idx == 0 || yGapFromPrev <= prevRowMaxHeight * tuning.tableDividerMaxYGapMultiplier)
            if ((cols >= 2 && shortCells && !isSubHeaderRow) || isSingleBoldDivider) {
                if (runStart < 0) runStart = idx
            } else {
                flushRun(idx)
            }
        }
        flushRun(yRows.size)

        return result
    }

    /** Group elements into y-rows: elements within [tolerance] y-units share a row. */
    private fun groupByYRows(
        elements: List<TextElement>,
        tolerance: Int
    ): List<List<TextElement>> {
        val sorted = elements.sortedBy { it.y }
        val rows   = mutableListOf<MutableList<TextElement>>()
        for (el in sorted) {
            val last = rows.lastOrNull()
            if (last == null || el.y - last.first().y > tolerance) {
                rows.add(mutableListOf(el))
            } else {
                last.add(el)
            }
        }
        return rows
    }

    /** Count how many distinct x-clusters exist in a row.
     *
     * Uses x-to-x distance (start positions) rather than endX-to-x so that long
     * cells whose text nearly reaches the next column's start position are not
     * erroneously merged into a single cluster.  This matches the approach used in
     * [buildTableRegion] which clusters column starts by x position.
     */
    private fun countXClusters(row: List<TextElement>, minGap: Int): Int {
        if (row.isEmpty()) return 0
        val sorted = row.sortedBy { it.x }
        var count  = 1
        for (k in 1 until sorted.size) {
            if (sorted[k].x - sorted[k - 1].x > minGap) count++
        }
        return count
    }

    private fun buildTableRegion(
        yRows: List<List<TextElement>>,
        options: ConversionOptions = ConversionOptions.READABLE,
    ): TableRegion? {
        val tuning = options.ruleTuning
        val allElements = yRows.flatten()

        // Collect all distinct x-starts and cluster them into column start positions
        val xSorted = allElements.map { it.x }.distinct().sorted()
        val colStarts = mutableListOf(xSorted.first())
        for (k in 1 until xSorted.size) {
            if (xSorted[k] - xSorted[k - 1] > tuning.tableMinColumnGap) colStarts.add(xSorted[k])
        }
        val colCount = colStarts.size
        if (colCount < 2) return null

        // Build half-open column ranges: [colStarts[i], colStarts[i+1])
        val colRanges = (0 until colCount).map { k ->
            colStarts[k]..(if (k + 1 < colCount) colStarts[k + 1] - 1 else Int.MAX_VALUE)
        }

        fun rowToCells(rowEls: List<TextElement>): List<String> {
            val cells = Array(colCount) { "" }
            // A row with exactly one bold element that maps to a single column is a
            // section-header divider (e.g. "Bond Market" in a multi-section table).
            // Render its text in bold so it stands out; leave other columns empty.
            val isSectionHeaderRow = rowEls.size == 1
                && (rowEls[0].font == "bold" || rowEls[0].font == "bold-italic")
            for (el in rowEls.sortedBy { it.x }) {
                val colIdx = colRanges.indexOfFirst { el.x in it }.let { if (it < 0) colCount - 1 else it }
                val rawText = el.text.trim()
                val text = if (isSectionHeaderRow && !options.stripInlineFormatting) "**$rawText**" else rawText
                cells[colIdx] = if (cells[colIdx].isEmpty()) text
                                else "${cells[colIdx]} $text"
            }
            // Normalization: repeat a cell's text into empty neighbours whose column
            // boundary the cell's endX reaches. Only active when normalizeTableSpans is set.
            // Skip section-header rows — their empty columns are intentional.
            if (options.normalizeTableSpans && !isSectionHeaderRow) {
                for (k in 0 until colCount - 1) {
                    if (cells[k].isEmpty()) continue
                    val maxEndX = rowEls
                        .filter { el -> colRanges.indexOfFirst { el.x in it }
                            .let { if (it < 0) colCount - 1 else it } == k }
                        .maxOfOrNull { it.endX } ?: continue
                    for (m in k + 1 until colCount) {
                        if (maxEndX >= colStarts[m] && cells[m].isEmpty()) cells[m] = cells[k]
                        else break
                    }
                }
            }
            return cells.toList()
        }

        // First bold row → header; remaining rows → data
        val headerIdx = yRows.indexOfFirst { row -> row.any { it.font == "bold" || it.font == "bold-italic" } }
            .let { if (it < 0) 0 else it }
        val headerRow = rowToCells(yRows[headerIdx])
        val dataRows  = yRows.indices.filter { it != headerIdx }.map { rowToCells(yRows[it]) }

        return TableRegion(
            allElements = allElements,
            minY        = allElements.minOf { it.y },
            headerRow   = headerRow,
            dataRows    = dataRows,
            colCount    = colCount
        )
    }

    private fun renderTableRegion(
        tr: TableRegion,
        @Suppress("UNUSED_PARAMETER") options: ConversionOptions = ConversionOptions.READABLE,
    ): String = buildString {
        fun appendRow(cells: List<String>) {
            append("| ")
            append(cells.joinToString(" | ") { it.replace("|", "\\|") })
            appendLine(" |")
        }
        appendRow(tr.headerRow)
        appendLine("|" + " --- |".repeat(tr.colCount))
        tr.dataRows.forEach { appendRow(it) }
        // Remove trailing newline
        if (endsWith("\n")) deleteCharAt(length - 1)
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /** Identity-based set (avoids false positives from data-class equality). */
    private fun <T> identitySetOf(vararg items: T): MutableSet<T> {
        val set: MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
        items.forEach { set.add(it) }
        return set
    }

    /** Collects items into an identity-based set. */
    private fun <T> Iterable<T>.toIdentitySet(): MutableSet<T> {
        val set: MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
        forEach { set.add(it) }
        return set
    }
}
