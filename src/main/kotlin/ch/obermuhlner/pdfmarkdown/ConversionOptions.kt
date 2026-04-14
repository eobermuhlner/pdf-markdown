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
