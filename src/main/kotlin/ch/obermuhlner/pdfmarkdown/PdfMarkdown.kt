package ch.obermuhlner.pdfmarkdown

import org.apache.pdfbox.Loader
import java.awt.image.BufferedImage
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
    fun toMarkdown(
        file: File,
        maxPages: Int = Int.MAX_VALUE,
        options: ConversionOptions = ConversionOptions.READABLE,
    ): String {
        val (pageElements, modeFontSize) = extractFilteredPageElements(file, maxPages)
        return DeterministicMarkdownConverter.convertDocument(pageElements, modeFontSize, options)
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

    /**
     * Converts a PDF to the intermediate positional XML format, including raw PDFBox font
     * metadata attributes (`fn`, `fp`, `fw`, `fb`, `fi`) on each `<text>` element for debugging.
     *
     * @param file      PDF file to convert.
     * @param maxPages  Maximum number of pages to process (default: all pages).
     * @return          XML string with a `<document>` root element.
     */
    fun toXmlRaw(file: File, maxPages: Int = Int.MAX_VALUE): String =
        convertPdfToXml(file, maxPages, raw = true)

    /**
     * Renders each page of [file] as a [BufferedImage].
     *
     * @param file      Input PDF file.
     * @param maxPages  Maximum number of pages to process (default: all pages).
     * @param dpi       Resolution in dots per inch (default: 72).
     * @return          List of images, one per page.
     */
    fun toImages(file: File, maxPages: Int = Int.MAX_VALUE, dpi: Int = 72): List<BufferedImage> =
        PdfImageConverter.renderAllPages(file, maxPages, dpi)

    /**
     * Renders each page of [file] as a PNG image file.
     *
     * @param file       Input PDF file.
     * @param maxPages   Maximum number of pages to process (default: all pages).
     * @param outputDir  Directory to write image files.
     * @param dpi        Resolution in dots per inch (default: 72).
     * @return           List of created image files with names `page_001.png`, `page_002.png`, etc.
     */
    fun toImageFiles(file: File, maxPages: Int = Int.MAX_VALUE, outputDir: File, dpi: Int = 72): List<File> =
        PdfImageConverter.writePageImages(file, outputDir, maxPages, dpi)
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

        val allElements = allPageElements.flatMap { it.second }

        docModeFontSize = allElements
            .groupingBy { it.fontSize }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 12

        // Document-relative bold detection: find the modal font weight (body weight), then
        // upgrade elements whose weight is ≥1.5× the modal from "normal"→"bold" / "italic"→"bold-italic".
        // This catches documents that use a custom weight scale (e.g. body=350, emphasis=600)
        // where the absolute ≥700 threshold in normalizeFontStyle would miss the bold.
        val modalFontWeight = allElements
            .filter { it.fontWeight > 0 }
            .groupingBy { it.fontWeight }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 400

        for ((_, rawElements) in allPageElements) {
            val elements = rawElements
                .filter { el -> Triple(el.x, el.y, el.text.trim()) !in repeatedKeys }
                .map { el -> upgradeFont(el, modalFontWeight) }
            if (elements.isNotEmpty()) result.add(elements)
        }
    }

    return result to docModeFontSize
}

/**
 * Upgrades a [TextElement]'s normalized font style when its weight is significantly heavier
 * than the document's modal (body) font weight.
 *
 * A ratio of ≥1.5× triggers an upgrade: `"normal"` → `"bold"`, `"italic"` → `"bold-italic"`.
 * Elements already classified as bold, or with no descriptor weight (fontWeight == 0), are unchanged.
 */
internal fun upgradeFont(el: TextElement, modalFontWeight: Int): TextElement {
    if (modalFontWeight <= 0 || el.fontWeight <= 0) return el
    if (el.fontWeight.toFloat() / modalFontWeight < 1.4f) return el
    val upgraded = when (el.font) {
        "normal"       -> "bold"
        "italic"       -> "bold-italic"
        "normal-mono"  -> "bold-mono"
        "italic-mono"  -> "bold-italic-mono"
        else           -> return el   // already bold or unrecognised — leave unchanged
    }
    return el.copy(font = upgraded)
}

internal fun convertPdfToXml(file: File, maxPages: Int = Int.MAX_VALUE, raw: Boolean = false): String {
    val sb = StringBuilder()
    sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    sb.appendLine("<document>")

    Loader.loadPDF(file).use { doc ->
        val stripper = PositionalTextStripper(rawMode = raw)
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
                val rawAttrs = if (el.rawFont != null) {
                    val parts = el.rawFont.split('|')
                    val fp = parts[0]
                    val fn = fp.substringAfter('+')
                    val fb = parts.getOrNull(2)?.removePrefix("fb=") ?: "?"
                    val fi = parts.getOrNull(3)?.removePrefix("fi=") ?: "?"
                    """ fn="${escapeXml(fn)}" fp="${escapeXml(fp)}" fb="$fb" fi="$fi""""
                } else ""
                sb.appendLine(
                    """    <text x="${el.x}" y="${el.y}" r="$r" cx="$cx" s="$size" fs="${el.fontSize}" f="${el.font}" fw="${el.fontWeight}"$rawAttrs h="${el.height}">${escapeXml(el.text)}</text>"""
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
