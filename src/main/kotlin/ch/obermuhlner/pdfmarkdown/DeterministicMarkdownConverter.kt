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
 * - Heading detection (font size + style + text pattern)
 * - Paragraph joining (y-gap + font continuity) with hyphenation repair
 * - Italic → *…*, bold (non-heading) → **…**, bold-italic → ***…***
 * - Code blocks (monospace font → fenced ```)
 * - Indented list items (x ≥ bodyMargin + 20)
 * - Advisory callouts (Note:, Warning:, …)
 * - Epigraph / block-quote detection
 * - Table detection (x-clustered rows with ≥2 columns and ≥3 rows)
 * - ToC entry normalisation (strip underline leaders)
 */
object DeterministicMarkdownConverter {

    // ─── Regex constants ──────────────────────────────────────────────────────

    /** Isolated page-number strings with no body content. */
    private val PAGE_NUMBER_REGEX = Regex(
        // Bare 1–2-digit page numbers (optional surrounding spaces)
        """^[\s]*\d{1,2}[\s]*$""" +
        // Numbers decorated with separator characters on at least one side
        """|^[\s]*[\-–—|]+[\s\-–—|]*\d+[\s\-–—|]*$""" +
        """|^[\s\-–—|]*\d+[\s]*[\-–—|]+[\s]*$""" +
        // "Page N" / "Page N of M"
        """|^[Pp]age\s+\d+(\s+of\s+\d+)?$""" +
        // "N / M" fraction form
        """|\d+\s*/\s*\d+""" +
        // "N | S e i t e" (German)
        """|\d[\s]*\|[\s]*S[\s]*e[\s]*i[\s]*t[\s]*e"""
    )

    /**
     * Footer URL artifacts added by web-to-PDF converters (e.g. localhost temp-file links).
     * Only localhost URLs are filtered; real web URLs (github.com, etc.) may appear in code blocks.
     */
    private val FOOTER_URL_REGEX = Regex("""^https?://localhost""", RegexOption.IGNORE_CASE)

    /** Bullet-marker prefix: •, ■, -, *, □, · followed by a space. */
    private val BULLET_PREFIX_REGEX   = Regex("""^[•■\*□·]\s|^-\s""")

    /** Numbered/lettered item prefix: "1. ", "2) ", "a. " etc. */
    private val NUMBERED_PREFIX_REGEX = Regex("""^\d+[.)]\s|^[a-z][.)]\s""")

    private val ADVISORY_REGEX = Regex(
        """^(Note|Warning|Tip|Important|Caution|Remark):\s*""",
        RegexOption.IGNORE_CASE
    )

    /** "1. Title", "12.Title", "2.The" — numbered section heading candidate (bold required). */
    private val NUMBERED_H2_REGEX = Regex("""^\d+\.\D""")

    /** "1.2" or "1.2.3" — numbered sub-section heading candidate. */
    private val NUMBERED_H3_REGEX = Regex("""^\d+\.\d""")

    /** Three or more consecutive underscores ⇒ ToC entry (leader dots). */
    private val TOC_UNDERSCORES = Regex("""_{3,}""")

    /** Trailing spaces/underscores + page number at end of a ToC line. */
    private val TOC_PAGE_NUMBER = Regex("""[\s_]+\d+\s*$""")

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

        val visible = elements.filter { !isPageNumber(it.text) }
        if (visible.isEmpty()) return ""

        // On the first page the title candidate is the element with the largest font size
        // (only if it exceeds the body-text size by at least 10%).
        val titleCandidateFontSize: Int? = if (isFirstPage && !titleAlreadyUsed) {
            visible.maxByOrNull { it.fontSize }?.fontSize
                ?.takeIf { it > modeFontSize * 1.10 }
        } else null

        val bodyMargin = computeBodyMargin(visible)
        val colBounds  = detectColumnBoundaries(visible)
        val columns    = splitIntoColumns(visible, colBounds)

        data class Chunk(val y: Int, val text: String)

        val chunks    = mutableListOf<Chunk>()
        var titleUsed = titleAlreadyUsed

        for (col in columns) {
            val sorted      = col.sortedBy { it.y }
            val withInitials = mergeDropInitials(sorted)

            val tableRegions = detectTableRegions(withInitials)
            val inTable      = withInitials.filter { el ->
                tableRegions.any { tr -> tr.allElements.any { it === el } }
            }.toSet()
            val prose = withInitials.filter { it !in inTable }

            val blocks = buildBlocks(prose, bodyMargin, modeFontSize, isFirstPage, titleUsed, titleCandidateFontSize, options)

            for (block in blocks) {
                if (block is Block.Heading && block.level == 1) titleUsed = true
                val rendered = renderBlock(block, options)
                if (rendered.isNotEmpty()) chunks.add(Chunk(block.minY, rendered))
            }
            for (tr in tableRegions) {
                chunks.add(Chunk(tr.minY, renderTableRegion(tr)))
            }
        }

        chunks.sortBy { it.y }
        return chunks.joinToString("\n\n") { it.text }.trim()
    }

    // ─── Page-number filter ───────────────────────────────────────────────────

    fun isPageNumber(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (FOOTER_URL_REGEX.containsMatchIn(t)) return true
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
    private fun detectColumnBoundaries(elements: List<TextElement>): List<IntRange> {
        if (elements.size < 6) return listOf(0..Int.MAX_VALUE)

        val minX      = elements.minOf { it.x }
        val pageWidth = elements.maxOf { it.endX }
        val span      = (pageWidth - minX).coerceAtLeast(1)

        // Build the histogram from only the leftmost element per y-row.
        // This prevents table sub-columns (e.g. a "Target Year" cell at x=137
        // when body text starts at x=56) from filling in what should be a clean
        // inter-column gap, while still giving every page column representation
        // (rows where only a right-column element exists still produce an anchor).
        val anchorXStarts = elements.groupBy { it.y }.values.map { row -> row.minOf { it.x } }

        // 50-bucket histogram of anchor x-start positions
        val buckets = 50
        val bw      = span.toDouble() / buckets
        val hist    = IntArray(buckets)
        for (x in anchorXStarts) hist[((x - minX) / bw).toInt().coerceIn(0, buckets - 1)]++

        // Find contiguous empty bands (≥3 consecutive empty buckets) in middle 80% of page
        val lo = (buckets * 0.10).toInt()
        val hi = (buckets * 0.90).toInt()

        val splitPoints = mutableListOf<Int>()
        var gapStart    = -1
        for (b in lo..hi) {
            if (hist[b] == 0) {
                if (gapStart < 0) gapStart = b
            } else {
                if (gapStart >= 0 && b - gapStart >= 3) {
                    val midX = minX + ((gapStart + b - 1) / 2.0 * bw).toInt()
                    splitPoints.add(midX)
                }
                gapStart = -1
            }
        }
        // flush trailing gap
        if (gapStart >= 0 && hi - gapStart >= 3) {
            val midX = minX + ((gapStart + hi) / 2.0 * bw).toInt()
            splitPoints.add(midX)
        }

        if (splitPoints.isEmpty()) return listOf(0..Int.MAX_VALUE)

        // Require each column to have at least 4 elements (absolute minimum).
        // A relative-only threshold (15 % of total) allowed splits with only 2–3
        // elements on one side, which fires falsely on centred multi-line headings
        // whose continuation lines happen to sit at a higher x than the body margin.
        val total      = elements.size
        val minColSize = maxOf(4, (total * 0.15).toInt())

        /** Returns true when ≥50% of [els] have x within 30 units of the mode x. */
        fun isCoherentColumn(els: List<TextElement>): Boolean {
            if (els.isEmpty()) return false
            val modeX    = els.groupingBy { it.x }.eachCount().maxByOrNull { it.value }?.key ?: return false
            val nearCount = els.count { abs(it.x - modeX) <= 30 }
            return nearCount >= els.size * 0.50
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
     * Detects typographic drop initials — short (1–3-char) letter fragments at a
     * narrow x across ≥2 consecutive y-rows — and prepends each to its companion
     * element on the same row.
     */
    private fun mergeDropInitials(elements: List<TextElement>): List<TextElement> {
        if (elements.size < 2) return elements

        // Find x values that host only short letter fragments across multiple rows
        val dropXs = elements
            .groupBy { it.x }
            .filter { (_, els) ->
                els.size >= 2 &&
                els.all { it.text.trim().length in 1..3 && it.text.any(Char::isLetter) }
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
                    abs(other.y - el.y) <= el.height / 2 &&
                    other.x > el.endX &&
                    other.x - el.endX <= el.height * 3
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
    ): List<Block> {
        val blocks    = mutableListOf<Block>()
        var i         = 0
        var titleUsed = initialTitleUsed

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
                    if (next.font.endsWith("-mono") && yGap <= codeLines.last().height * 2) {
                        codeLines.add(next); j++
                    } else break
                }
                blocks.add(Block.CodeBlock(codeLines))
                i = j
                continue
            }

            // ── Heading ─────────────────────────────────────────────────────
            val headingLevel = detectHeadingLevel(el, modeFontSize, isFirstPage, titleUsed, titleCandidateFontSize)
            if (headingLevel > 0) {
                // Merge consecutive lines that belong to the same heading
                // (multi-line titles / wrapped section headers).
                val headingParts = mutableListOf(text)
                var j = i + 1
                while (j < elements.size) {
                    val next     = elements[j]
                    val yGap     = next.y - (elements[j - 1].y + elements[j - 1].height)
                    val sameFont = next.font == el.font && abs(next.fontSize - el.fontSize) <= 1
                    if (sameFont && yGap <= el.height * 3) {
                        headingParts.add(next.text.trim()); j++
                    } else break
                }
                val headingText = headingParts.joinToString(" ")

                if (TOC_UNDERSCORES.containsMatchIn(headingText)) {
                    if (options.includeToc) {
                        val clean = headingText.replace(TOC_UNDERSCORES, "")
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
            val advMatch = ADVISORY_REGEX.find(text)
            if (advMatch != null && (el.font.contains("bold") || el.font.contains("italic"))) {
                val label = advMatch.groupValues[1].replaceFirstChar { it.uppercase() }
                val rest  = text.removePrefix(advMatch.value)
                blocks.add(Block.Advisory(label, rest, el))
                i++
                continue
            }

            // ── List items ──────────────────────────────────────────────────
            // Only treat as indented if within 100 units of the body margin.
            // A larger gap means the element is in a separate layout zone (e.g. a
            // different column or table cell), not a list item.
            if (el.x >= bodyMargin + 20 && el.x <= bodyMargin + 100) {
                val items = mutableListOf(el)
                var j = i + 1
                while (j < elements.size) {
                    val next  = elements[j]
                    val yGap  = next.y - (elements[j - 1].y + elements[j - 1].height)
                    if (abs(next.x - el.x) <= 5 && yGap <= elements[j - 1].height * 3) {
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
            if (hasBulletOrNumberedPrefix(text)) {
                val items = mutableListOf(el)
                var j = i + 1
                while (j < elements.size) {
                    val next = elements[j]
                    val yGap = next.y - (elements[j - 1].y + elements[j - 1].height)
                    // Continue list if next line also has a bullet/number prefix OR
                    // it continues the current item at a deeper x-indent with the same font
                    val isContinuation = !hasBulletOrNumberedPrefix(next.text.trim()) &&
                                         next.font == el.font &&
                                         next.x > el.x &&
                                         yGap <= elements[j - 1].height * 2
                    val isNextItem = hasBulletOrNumberedPrefix(next.text.trim()) &&
                                     yGap <= elements[j - 1].height * 4
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
            val epSz = fontSizeToCssKeyword(el.fontSize, modeFontSize)
            val isMediumQuote = epSz == "medium" &&
                (el.font == "italic" || el.font == "bold-italic") &&
                el.text.trimStart().let {
                    it.startsWith('"') || it.startsWith('\u201c') ||
                    it.startsWith('—') || it.startsWith('–')
                }
            if ((el.font == "italic" || el.font == "bold-italic") &&
                (isSmallSize(epSz) || isMediumQuote)) {
                val epLines = mutableListOf(el)
                var j = i + 1
                while (j < elements.size) {
                    val next = elements[j]
                    val sz   = fontSizeToCssKeyword(next.fontSize, modeFontSize)
                    val yGap = next.y - (epLines.last().y + epLines.last().height)
                    // Allow up to 3× line height gap and accept both sizes (last line may differ slightly)
                    if ((next.font == "italic" || next.font == "bold-italic") &&
                        (isSmallSize(sz) || sz == "medium") &&
                        yGap <= epLines.last().height * 3) {
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
                            next.text.trim().length < 70 &&
                            yGap <= epLines.last().height * 4) {
                            attribution = next; j++
                        }
                    }
                    blocks.add(Block.Epigraph(epLines, attribution))
                    i = j
                    continue
                }
            }

            // ── Regular paragraph ────────────────────────────────────────────
            val paraLines = mutableListOf(el)
            var j = i + 1
            while (j < elements.size) {
                val next       = elements[j]
                val prevEl     = paraLines.last()
                val yGap       = next.y - (prevEl.y + prevEl.height)
                val maxGap     = prevEl.height * 2
                val nextMono   = next.font.endsWith("-mono")
                val nextHead   = detectHeadingLevel(next, modeFontSize, false, titleUsed, null) > 0
                val nextAdv    = ADVISORY_REGEX.containsMatchIn(next.text.trim()) &&
                                 (next.font.contains("bold") || next.font.contains("italic"))
                if (next.font == el.font && yGap <= maxGap &&
                    !nextMono && !nextHead && !nextAdv) {
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
        titleCandidateFontSize: Int? = null
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

        // (4) Bold ALL-CAPS short standalone → ##
        val hasLetters = text.any(Char::isLetter)
        val allCaps    = hasLetters && text.filter(Char::isLetter).all(Char::isUpperCase)
        if (isBold && allCaps && text.length < 80) return 2

        // (5) Bold x-large/xx-large standalone → ##
        if (isBold && size in setOf("x-large", "xx-large")) return 2

        return 0
    }

    private fun isSmallSize(size: String) = size in setOf("x-small", "small")

    private fun hasBulletOrNumberedPrefix(text: String): Boolean =
        BULLET_PREFIX_REGEX.containsMatchIn(text) || NUMBERED_PREFIX_REGEX.containsMatchIn(text)

    /**
     * Removes a leading bullet character from [text], if present.
     * Numbered prefixes (1., 2), a.) are left intact — the number carries meaning.
     */
    private fun stripBulletPrefix(text: String): String {
        val m = BULLET_PREFIX_REGEX.find(text) ?: return text
        return text.removePrefix(m.value).trimStart()
    }

    // ─── Code language detection ──────────────────────────────────────────────

    private fun detectCodeLanguage(lines: List<String>): String {
        val joined = lines.joinToString("\n")
        // Strong Python indicators — each alone is sufficient
        val pythonStrong = listOf("def ", "import ", "class ", "elif ", "lambda ", "self.", "__init__", "print(")
        // Strong bash indicators — each alone is sufficient
        val bashStrong   = listOf("#!/", "sudo ", "apt-", "apt ", "brew ", "pip ", "npm ", "git ", "curl ", "wget ",
                                  "echo ", "grep ", "export ", "chmod ", "mkdir ", " && ", " || ", "$ ")
        // Plain-text/ASCII-art indicators: box-drawing borders or fill-in underscores
        val textStrong   = listOf("+--", "+==", "|  ", "| ", "___")
        return when {
            pythonStrong.any { joined.contains(it) } -> "python"
            bashStrong.any   { joined.contains(it) } -> "bash"
            textStrong.any   { joined.contains(it) } -> "text"
            else                                     -> ""
        }
    }

    // ─── Block rendering ──────────────────────────────────────────────────────

    private fun renderBlock(block: Block, options: ConversionOptions = ConversionOptions.READABLE): String = when (block) {
        is Block.Heading -> "#".repeat(block.level) + " " + block.text

        is Block.Paragraph -> renderParagraph(block.lines, options)

        is Block.ListItems -> block.items.joinToString("\n") { "- " + stripBulletPrefix(it.text.trim()) }

        is Block.CodeBlock -> buildString {
            val lang = detectCodeLanguage(block.lines.map { it.text })
            appendLine("```$lang")
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
        return if (options.stripInlineFormatting) joined else applyEmphasis(joined, lines.first().font)
    }

    private fun applyEmphasis(text: String, font: String): String = when (font) {
        "italic"      -> "*$text*"
        "bold"        -> "**$text**"
        "bold-italic" -> "***$text***"
        else          -> text
    }

    // ─── Table detection ──────────────────────────────────────────────────────

    private fun detectTableRegions(elements: List<TextElement>): List<TableRegion> {
        if (elements.size < 6) return emptyList()

        val yRows = groupByYRows(elements, tolerance = 15)
        if (yRows.size < 3) return emptyList()

        val result   = mutableListOf<TableRegion>()
        var runStart = -1

        fun flushRun(endExclusive: Int) {
            if (runStart < 0) return
            val runRows = yRows.subList(runStart, endExclusive)
            if (runRows.size >= 3) buildTableRegion(runRows)?.let { result.add(it) }
            runStart = -1
        }

        for ((idx, row) in yRows.withIndex()) {
            val cols       = countXClusters(row, minGap = 30)
            val shortCells = row.all { it.text.trim().length <= 80 }
            if (cols >= 2 && shortCells) {
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

    /** Count how many distinct x-clusters exist in a row. */
    private fun countXClusters(row: List<TextElement>, minGap: Int): Int {
        if (row.isEmpty()) return 0
        val sorted = row.sortedBy { it.x }
        var count  = 1
        for (k in 1 until sorted.size) {
            if (sorted[k].x - sorted[k - 1].endX > minGap) count++
        }
        return count
    }

    private fun buildTableRegion(yRows: List<List<TextElement>>): TableRegion? {
        val allElements = yRows.flatten()

        // Collect all distinct x-starts and cluster them into column start positions
        val xSorted = allElements.map { it.x }.distinct().sorted()
        val colStarts = mutableListOf(xSorted.first())
        for (k in 1 until xSorted.size) {
            if (xSorted[k] - xSorted[k - 1] > 30) colStarts.add(xSorted[k])
        }
        val colCount = colStarts.size
        if (colCount < 2) return null

        // Build half-open column ranges: [colStarts[i], colStarts[i+1])
        val colRanges = (0 until colCount).map { k ->
            colStarts[k]..(if (k + 1 < colCount) colStarts[k + 1] - 1 else Int.MAX_VALUE)
        }

        fun rowToCells(rowEls: List<TextElement>): List<String> {
            val cells = Array(colCount) { "" }
            for (el in rowEls.sortedBy { it.x }) {
                val colIdx = colRanges.indexOfFirst { el.x in it }.let { if (it < 0) colCount - 1 else it }
                cells[colIdx] = if (cells[colIdx].isEmpty()) el.text.trim()
                                else "${cells[colIdx]} ${el.text.trim()}"
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

    private fun renderTableRegion(tr: TableRegion): String = buildString {
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
}
