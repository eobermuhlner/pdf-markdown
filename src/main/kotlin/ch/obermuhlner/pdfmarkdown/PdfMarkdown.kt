package ch.obermuhlner.pdfmarkdown

import org.apache.pdfbox.Loader
import java.io.File

/**
 * Public API facade for pdf-markdown.
 *
 * All entry points are on this object; internal helpers are package-private.
 */
object PdfMarkdown {

    /**
     * Converts [file] to Markdown using the deterministic rule-based converter.
     * No LLM or network access is required.
     *
     * @param file      Input PDF file.
     * @param maxPages  Maximum number of pages to process (default: all pages).
     * @return          Full document Markdown, pages joined by blank lines.
     */
    fun toMarkdown(file: File, maxPages: Int = Int.MAX_VALUE): String {
        val (pageElements, modeFontSize) = extractFilteredPageElements(file, maxPages)
        return DeterministicMarkdownConverter.convertDocument(pageElements, modeFontSize)
            .joinToString("\n\n")
    }

    /**
     * Converts [file] to the intermediate positional XML format used as input for LLM-based
     * conversion or for debugging the extraction stage.
     *
     * @param file      Input PDF file.
     * @param maxPages  Maximum number of pages to process (default: all pages).
     * @return          XML string with a `<document>` root element.
     */
    fun toXml(file: File, maxPages: Int = Int.MAX_VALUE): String =
        convertPdfToXml(file, maxPages)
}

// ─── Internal helpers ─────────────────────────────────────────────────────────

/**
 * Extracts per-page [TextElement] lists from [file] with repeated headers/footers stripped.
 * Returns the element lists paired with the document-wide mode font size.
 */
internal fun extractFilteredPageElements(
    file: File,
    maxPages: Int = Int.MAX_VALUE,
): Pair<List<List<TextElement>>, Int> {
    val result = mutableListOf<List<TextElement>>()
    var docModeFontSize = 12

    Loader.loadPDF(file).use { doc ->
        val stripper = PositionalTextStripper()
        val pagesToProcess = minOf(doc.numberOfPages, maxPages)

        val allPageElements = mutableListOf<Pair<Int, List<TextElement>>>()
        for (pageNum in 1..pagesToProcess) {
            stripper.startPage = pageNum
            stripper.endPage = pageNum
            val elements = mergeElements(stripper.extractElements(doc))
            if (elements.isNotEmpty()) allPageElements.add(pageNum to elements)
        }

        val repeatedKeys = detectRepeatedElements(
            allPageElements.map { it.second },
            minPages = maxOf(2, allPageElements.size / 3),
        )

        docModeFontSize = allPageElements.flatMap { it.second }
            .groupingBy { it.fontSize }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 12

        for ((_, rawElements) in allPageElements) {
            val elements = rawElements.filter { el ->
                Triple(el.x, el.y, el.text.trim()) !in repeatedKeys
            }
            if (elements.isNotEmpty()) result.add(elements)
        }
    }

    return result to docModeFontSize
}

internal fun convertPdfToXml(file: File, maxPages: Int = Int.MAX_VALUE): String {
    val sb = StringBuilder()
    sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    sb.appendLine("<document>")

    Loader.loadPDF(file).use { doc ->
        val stripper = PositionalTextStripper()
        val pageElements = mutableListOf<List<TextElement>>()
        val pagesToProcess = minOf(doc.numberOfPages, maxPages)
        for (pageNum in 1..pagesToProcess) {
            stripper.startPage = pageNum
            stripper.endPage = pageNum
            pageElements.add(mergeElements(stripper.extractElements(doc)))
        }

        val modeFontSize = pageElements.flatten()
            .groupingBy { it.fontSize }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 12

        for ((index, elements) in pageElements.withIndex()) {
            val pageNum = index + 1
            sb.appendLine("""  <page number="$pageNum">""")
            for (el in elements) {
                val size = fontSizeToCssKeyword(el.fontSize, modeFontSize)
                val r = el.endX
                val cx = (el.x + el.endX) / 2
                sb.appendLine(
                    """    <text x="${el.x}" y="${el.y}" r="$r" cx="$cx" s="$size" fs="${el.fontSize}" f="${el.font}" h="${el.height}">${escapeXml(el.text)}</text>"""
                )
            }
            sb.appendLine("  </page>")
        }
    }

    sb.append("</document>")
    return sb.toString()
}

internal fun fontSizeToCssKeyword(fontSize: Int, modeFontSize: Int): String {
    val ratio = fontSize.toDouble() / modeFontSize
    return when {
        ratio < 0.70 -> "xx-small"
        ratio < 0.82 -> "x-small"
        ratio < 0.94 -> "small"
        ratio < 1.10 -> "medium"
        ratio < 1.35 -> "large"
        ratio < 1.70 -> "x-large"
        else -> "xx-large"
    }
}

internal fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

/** Returns (x, y, trimmedText) keys that appear on at least [minPages] distinct pages. */
internal fun detectRepeatedElements(
    pageElements: List<List<TextElement>>,
    minPages: Int,
): Set<Triple<Int, Int, String>> {
    val pageCounts = mutableMapOf<Triple<Int, Int, String>, Int>()
    for (elements in pageElements) {
        val seenOnPage = mutableSetOf<Triple<Int, Int, String>>()
        for (el in elements) {
            val key = Triple(el.x, el.y, el.text.trim())
            if (seenOnPage.add(key)) pageCounts[key] = (pageCounts[key] ?: 0) + 1
        }
    }
    return pageCounts.filterValues { it >= minPages }.keys.toSet()
}
