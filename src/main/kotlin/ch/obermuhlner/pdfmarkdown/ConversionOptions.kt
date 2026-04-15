package ch.obermuhlner.pdfmarkdown

/**
 * Controls which conversion rules are applied and how blocks are rendered.
 *
 * Use the named presets [READABLE] and [RAG] for common configurations,
 * or construct a custom instance by overriding individual fields.
 */
data class ConversionOptions(
    /**
     * Strip inline bold/italic asterisks from paragraph text.
     * Structural formatting (headings, fenced code, tables, lists) is always kept.
     * Useful for RAG pipelines where `**word**` and `*word*` are embedding noise.
     */
    val stripInlineFormatting: Boolean = false,

    /**
     * Include Table of Contents entries in the output.
     * ToC entries are low-value in a RAG corpus — they duplicate section titles without content.
     */
    val includeToc: Boolean = true,

    /** How epigraph / block-quote blocks are rendered. */
    val epigraphFormat: EpigraphFormat = EpigraphFormat.BLOCKQUOTE,

    /** How advisory callouts (Note:, Warning:, …) are rendered. */
    val advisoryFormat: AdvisoryFormat = AdvisoryFormat.BLOCKQUOTE,

    /**
     * When true, a cell whose [TextElement.endX] reaches the start of the next column
     * AND whose neighbour column slots are empty is treated as a colspan candidate:
     * the cell text is repeated into those empty slots.
     * Reduces silent data loss in RAG pipelines at the cost of possible text duplication
     * when a normal cell happens to be wide with an empty neighbour.
     */
    val normalizeTableSpans: Boolean = false,

    /**
     * Fine-tuning parameters for conversion rules.
     * Override individual values for specialized document types.
     */
    val ruleTuning: RuleTuning = RuleTuning(),
) {
    enum class EpigraphFormat {
        /** Render as Markdown block quote: `> text`. */
        BLOCKQUOTE,
        /** Render as plain paragraph text, no `>` prefix. */
        PLAIN,
    }

    enum class AdvisoryFormat {
        /** Render as Markdown block quote: `> **Note:** text`. */
        BLOCKQUOTE,
        /** Render as plain text: `Note: text`. */
        PLAIN,
    }

    companion object {
        /** Optimised for human-readable Markdown (default). */
        val READABLE = ConversionOptions()

        /**
         * Optimised for RAG pipelines: structure preserved, inline formatting noise removed,
         * ToC and blockquote wrappers stripped.
         */
        val RAG = ConversionOptions(
            stripInlineFormatting = true,
            includeToc = false,
            epigraphFormat = EpigraphFormat.PLAIN,
            advisoryFormat = AdvisoryFormat.PLAIN,
            normalizeTableSpans = true,
        )
    }
}

/**
 * Fine-tuning parameters for conversion rules.
 *
 * These values control the heuristics used to detect and merge text elements
 * into Markdown blocks. Override individual values to optimize for specific
 * document types (e.g., dense academic papers, loose business reports).
 *
 * All numeric values have sensible defaults that work well for typical documents.
 */
data class RuleTuning(
    // ─── Heading detection ────────────────────────────────────────────────────

    /**
     * Minimum font size ratio vs. body text for a first-page element to be
     * considered a document title (H1).
     * Default: 1.10 (10% larger than body text)
     */
    val titleMinRatio: Double = 1.10,

    /**
     * Minimum font size ratio vs. body text for bold "medium" text to be
     * considered a heading (H5).
     * Default: 1.05 (5% larger than body text)
     */
    val headingMediumMinRatio: Double = 1.05,

    // ─── Paragraph joining ────────────────────────────────────────────────────

    /**
     * Maximum vertical gap between two lines (as a multiple of line height)
     * before they are considered separate paragraphs.
     * Default: 2.0
     */
    val paragraphMaxYGapMultiplier: Double = 2.0,

    /**
     * Maximum horizontal (x) distance between lines before they are considered
     * to be in different layout zones (e.g., separate columns).
     * Default: 150
     */
    val paragraphMaxXDistance: Int = 150,

    // ─── Heading merge ────────────────────────────────────────────────────────

    /**
     * Maximum vertical gap between heading lines (as a multiple of line height)
     * before they are considered separate headings.
     * Default: 3.0
     */
    val headingMergeMaxYGapMultiplier: Double = 3.0,

    /**
     * Maximum font size difference between heading lines that can be merged.
     * Default: 1
     */
    val headingMergeMaxFontSizeDiff: Int = 1,

    // ─── List detection ─────────────────────────────────────────────────────

    /**
     * Minimum x-indent (relative to body margin) for an element to be
     * considered an indented list item.
     * Default: 20
     */
    val listMinIndent: Int = 20,

    /**
     * Maximum x-indent (relative to body margin) for an element to be
     * considered an indented list item (beyond this, it's a different zone).
     * Default: 100
     */
    val listMaxIndent: Int = 100,

    /**
     * Maximum vertical gap between list items (as a multiple of line height).
     * Default: 3.0
     */
    val listYGapMultiplier: Double = 3.0,

    /**
     * Maximum horizontal (x) distance variation between consecutive list items.
     * Default: 5
     */
    val listXVariance: Int = 5,

    /**
     * Maximum vertical gap between bullet/numbered list items (as multiple of line height).
     * Default: 4.0
     */
    val bulletListYGapMultiplier: Double = 4.0,

    /**
     * Maximum vertical gap for list item continuation lines (as multiple of line height).
     * Default: 2.0
     */
    val listContinuationYGapMultiplier: Double = 2.0,

    // ─── Epigraph detection ──────────────────────────────────────────────────

    /**
     * Maximum vertical gap between epigraph lines (as a multiple of line height).
     * Default: 3.0
     */
    val epigraphYGapMultiplier: Double = 3.0,

    /**
     * Maximum vertical gap between epigraph and attribution line (as a multiple of line height).
     * Default: 4.0
     */
    val epigraphAttributionYGapMultiplier: Double = 4.0,

    /**
     * Maximum length of an attribution line.
     * Default: 70
     */
    val epigraphAttributionMaxLength: Int = 70,

    // ─── Table detection ─────────────────────────────────────────────────────

    /**
     * Minimum horizontal gap between x-clusters for them to be considered
     * separate columns in a table.
     * Default: 30
     */
    val tableMinColumnGap: Int = 30,

    /**
     * Minimum number of rows for a region to be considered a table.
     * Default: 3
     */
    val tableMinRows: Int = 3,

    /**
     * Y-position tolerance for grouping elements into the same row.
     * Default: 12
     */
    val tableRowTolerance: Int = 12,

    /**
     * Minimum number of elements on a page for column detection to run.
     * Default: 6
     */
    val columnDetectionMinElements: Int = 6,

    /**
     * Number of histogram buckets for column detection.
     * Default: 50
     */
    val columnHistogramBuckets: Int = 50,

    /**
     * Fraction of page width to exclude from column detection (start/end).
     * Default: 0.10 (10% each side)
     */
    val columnMarginFraction: Double = 0.10,

    /**
     * Minimum consecutive empty histogram buckets to consider as a column gap.
     * Default: 3
     */
    val columnMinGapBuckets: Int = 3,

    /**
     * Minimum column size as a fraction of total page elements.
     * Default: 0.15
     */
    val columnMinSizeFraction: Double = 0.15,

    /**
     * Minimum column size (absolute number of elements).
     * Default: 4
     */
    val columnMinSizeAbsolute: Int = 4,

    /**
     * Maximum x-distance from mode column x for an element to be considered
     * part of a coherent column.
     * Default: 30
     */
    val columnCoherenceXDistance: Int = 30,

    /**
     * Minimum fraction of elements in a column that must be within
     * [columnCoherenceXDistance] of the mode x.
     * Default: 0.50
     */
    val columnCoherenceMinFraction: Double = 0.50,

    // ─── Drop-initial detection ───────────────────────────────────────────────

    /**
     * Minimum number of rows a drop-initial must appear in.
     * Default: 2
     */
    val dropInitialMinRows: Int = 2,

    /**
     * Maximum character length of a drop-initial letter.
     * Default: 3
     */
    val dropInitialMaxLength: Int = 3,

    /**
     * Maximum y-distance between drop-initial and its companion (as fraction of height).
     * Default: 0.5
     */
    val dropInitialYDistanceFraction: Double = 0.5,

    /**
     * Maximum x-distance between drop-initial and companion (as multiple of height).
     * Default: 3.0
     */
    val dropInitialXDistanceMultiplier: Double = 3.0,

    // ─── Code block detection ─────────────────────────────────────────────────

    /**
     * Maximum vertical gap between consecutive code lines (as multiple of line height).
     * Default: 2.0
     */
    val codeYGapMultiplier: Double = 2.0,
) {
    /**
     * Creates a copy with overridden values from a map.
     * Only non-null values in the map are applied.
     */
    fun withOverrides(overrides: Map<String, Any?>): RuleTuning {
        var result = this
        overrides.forEach { (key, value) ->
            if (value != null) {
                result = when (key) {
                    "titleMinRatio" -> result.copy(titleMinRatio = value.toString().toDoubleOrNull() ?: result.titleMinRatio)
                    "headingMediumMinRatio" -> result.copy(headingMediumMinRatio = value.toString().toDoubleOrNull() ?: result.headingMediumMinRatio)
                    "paragraphMaxYGapMultiplier" -> result.copy(paragraphMaxYGapMultiplier = value.toString().toDoubleOrNull() ?: result.paragraphMaxYGapMultiplier)
                    "paragraphMaxXDistance" -> result.copy(paragraphMaxXDistance = value.toString().toIntOrNull() ?: result.paragraphMaxXDistance)
                    "headingMergeMaxYGapMultiplier" -> result.copy(headingMergeMaxYGapMultiplier = value.toString().toDoubleOrNull() ?: result.headingMergeMaxYGapMultiplier)
                    "headingMergeMaxFontSizeDiff" -> result.copy(headingMergeMaxFontSizeDiff = value.toString().toIntOrNull() ?: result.headingMergeMaxFontSizeDiff)
                    "listMinIndent" -> result.copy(listMinIndent = value.toString().toIntOrNull() ?: result.listMinIndent)
                    "listMaxIndent" -> result.copy(listMaxIndent = value.toString().toIntOrNull() ?: result.listMaxIndent)
                    "listYGapMultiplier" -> result.copy(listYGapMultiplier = value.toString().toDoubleOrNull() ?: result.listYGapMultiplier)
                    "listXVariance" -> result.copy(listXVariance = value.toString().toIntOrNull() ?: result.listXVariance)
                    "bulletListYGapMultiplier" -> result.copy(bulletListYGapMultiplier = value.toString().toDoubleOrNull() ?: result.bulletListYGapMultiplier)
                    "listContinuationYGapMultiplier" -> result.copy(listContinuationYGapMultiplier = value.toString().toDoubleOrNull() ?: result.listContinuationYGapMultiplier)
                    "epigraphYGapMultiplier" -> result.copy(epigraphYGapMultiplier = value.toString().toDoubleOrNull() ?: result.epigraphYGapMultiplier)
                    "epigraphAttributionYGapMultiplier" -> result.copy(epigraphAttributionYGapMultiplier = value.toString().toDoubleOrNull() ?: result.epigraphAttributionYGapMultiplier)
                    "epigraphAttributionMaxLength" -> result.copy(epigraphAttributionMaxLength = value.toString().toIntOrNull() ?: result.epigraphAttributionMaxLength)
                    "tableMinColumnGap" -> result.copy(tableMinColumnGap = value.toString().toIntOrNull() ?: result.tableMinColumnGap)
                    "tableMinRows" -> result.copy(tableMinRows = value.toString().toIntOrNull() ?: result.tableMinRows)
                    "tableRowTolerance" -> result.copy(tableRowTolerance = value.toString().toIntOrNull() ?: result.tableRowTolerance)
                    "columnDetectionMinElements" -> result.copy(columnDetectionMinElements = value.toString().toIntOrNull() ?: result.columnDetectionMinElements)
                    "columnHistogramBuckets" -> result.copy(columnHistogramBuckets = value.toString().toIntOrNull() ?: result.columnHistogramBuckets)
                    "columnMarginFraction" -> result.copy(columnMarginFraction = value.toString().toDoubleOrNull() ?: result.columnMarginFraction)
                    "columnMinGapBuckets" -> result.copy(columnMinGapBuckets = value.toString().toIntOrNull() ?: result.columnMinGapBuckets)
                    "columnMinSizeFraction" -> result.copy(columnMinSizeFraction = value.toString().toDoubleOrNull() ?: result.columnMinSizeFraction)
                    "columnMinSizeAbsolute" -> result.copy(columnMinSizeAbsolute = value.toString().toIntOrNull() ?: result.columnMinSizeAbsolute)
                    "columnCoherenceXDistance" -> result.copy(columnCoherenceXDistance = value.toString().toIntOrNull() ?: result.columnCoherenceXDistance)
                    "columnCoherenceMinFraction" -> result.copy(columnCoherenceMinFraction = value.toString().toDoubleOrNull() ?: result.columnCoherenceMinFraction)
                    "dropInitialMinRows" -> result.copy(dropInitialMinRows = value.toString().toIntOrNull() ?: result.dropInitialMinRows)
                    "dropInitialMaxLength" -> result.copy(dropInitialMaxLength = value.toString().toIntOrNull() ?: result.dropInitialMaxLength)
                    "dropInitialYDistanceFraction" -> result.copy(dropInitialYDistanceFraction = value.toString().toDoubleOrNull() ?: result.dropInitialYDistanceFraction)
                    "dropInitialXDistanceMultiplier" -> result.copy(dropInitialXDistanceMultiplier = value.toString().toDoubleOrNull() ?: result.dropInitialXDistanceMultiplier)
                    "codeYGapMultiplier" -> result.copy(codeYGapMultiplier = value.toString().toDoubleOrNull() ?: result.codeYGapMultiplier)
                    else -> result
                }
            }
        }
        return result
    }
}
