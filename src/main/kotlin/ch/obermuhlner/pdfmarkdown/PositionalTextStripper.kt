package ch.obermuhlner.pdfmarkdown

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PDFBox [PDFTextStripper] subclass that captures per-glyph position, size, and font metadata
 * into a list of [TextElement] objects rather than plain text.
 */
class PositionalTextStripper : PDFTextStripper() {
    private val elements = mutableListOf<TextElement>()

    /** Extract [TextElement] objects for the page range already configured on this stripper. */
    fun extractElements(doc: PDDocument): List<TextElement> {
        elements.clear()
        writeText(doc, StringWriter())
        return elements.toList()
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isBlank()) return
        val first = textPositions.firstOrNull() ?: return
        val x = first.xDirAdj.roundToInt()
        val y = first.yDirAdj.roundToInt()
        val fontSize = first.fontSizeInPt.roundToInt()
        val height = textPositions.maxOf { it.heightDir }.roundToInt()
        val font = normalizeFontStyle(first.font)
        val last = textPositions.last()
        val endX = (last.xDirAdj + last.width).roundToInt()
        elements.add(
            TextElement(
                x, y, endX, height, fontSize, font,
                normalizeText(remapFallbackGlyphs(text, textPositions))
            )
        )
    }

    /**
     * PDFBox maps a glyph to `?` (U+003F) when it has no Unicode entry in the font's ToUnicode
     * CMap. Such a `?` is a fallback placeholder, not a real question mark. We detect this by
     * comparing the decoded character against the raw character code: a genuine `?` has code 0x3F;
     * any other code means PDFBox fell back. We replace fallback-mapped `?` with `□` (U+25A1
     * WHITE SQUARE) — a neutral, visually recognisable placeholder.
     *
     * When `text` and `textPositions` lengths differ (e.g. ligatures decoded as two chars from one
     * glyph), 1-to-1 mapping is not safe and the text is returned unchanged.
     */
    private fun remapFallbackGlyphs(text: String, textPositions: List<TextPosition>): String {
        if (!text.contains('?')) return text
        if (text.length != textPositions.size) return text
        return buildString {
            for (i in text.indices) {
                val ch = text[i]
                val codes = textPositions[i].characterCodes
                val isRealQuestion = ch != '?' || (codes.isNotEmpty() && codes[0] == 0x3F)
                append(if (isRealQuestion) ch else '□')
            }
        }
    }
}

/**
 * Merges adjacent [TextElement]s on the same line that share the same font and size into a single
 * element.  Elements are first sorted by (y, x) so merging is left-to-right within each line.
 */
fun mergeElements(elements: List<TextElement>): List<TextElement> {
    if (elements.isEmpty()) return elements
    val sorted = elements.sortedWith(compareBy({ it.y }, { it.x }))
    val result = mutableListOf<TextElement>()
    var current = sorted[0]
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        if (canMerge(current, next)) {
            val separator = if (next.x - current.endX > 1) " " else ""
            current = current.copy(
                endX = next.endX,
                height = maxOf(current.height, next.height),
                text = current.text + separator + next.text,
            )
        } else {
            result.add(current)
            current = next
        }
    }
    result.add(current)
    return result
}

fun canMerge(a: TextElement, b: TextElement): Boolean {
    val yTolerance = maxOf(2, a.fontSize / 4)
    val maxGap = a.fontSize * 1.5
    return abs(a.y - b.y) <= yTolerance &&
            a.font == b.font &&
            abs(a.fontSize - b.fontSize) <= 1 &&
            b.x >= a.x &&
            (b.x - a.endX) < maxGap
}

/** Normalises a [PDFont] into one of the style tokens used throughout this library. */
fun normalizeFontStyle(font: PDFont): String {
    val descriptor = font.fontDescriptor
    val boldByDescriptor = descriptor != null && (descriptor.isForceBold || descriptor.fontWeight >= 700f)
    val italicByDescriptor = descriptor != null && descriptor.isItalic

    // Strip the PDF subset prefix (e.g. "BJOPBO+LinBiolinumTB" → "LinBiolinumTB")
    val name = (font.name ?: "").substringAfter('+').uppercase()
    val boldByName = name.contains("BOLD") || name.contains("HEAVY") || name.contains("BLACK") ||
            name.contains("DEMI") || name.endsWith("TB") || name.endsWith("-BD") || name.endsWith("BD")
    val italicByName = name.contains("ITALIC") || name.contains("OBLIQUE") || name.contains("SLANTED") ||
            name.endsWith("TI") || name.endsWith("-IT") || name.endsWith("IT")
    val monoByName = name.contains("MONO") || name.contains("COURIER") || name.contains("CODE") ||
            name.contains("TYPEWRITER") || name.contains("FIXED") || name.contains("CONSOL") ||
            name.contains("INCONSOLATA") || name.contains("TERMINAL") || name.contains("TELETYPE")

    val bold = boldByDescriptor || boldByName
    val italic = italicByDescriptor || italicByName

    val base = when {
        bold && italic -> "bold-italic"
        bold -> "bold"
        italic -> "italic"
        else -> "normal"
    }
    return if (monoByName) "$base-mono" else base
}

/** Normalises common Unicode whitespace and soft-hyphen variants found in PDFs. */
fun normalizeText(text: String): String = text
    .replace('\u00A0', ' ')       // non-breaking space → regular space
    .replace('\u202F', ' ')       // narrow no-break space → regular space
    .replace('\u2011', '-')       // non-breaking hyphen → hyphen-minus
    .replace('\u0095', '\u2022')  // Windows-1252 bullet (Latin-1 control) → bullet •
    .replace("\u00AD", "")        // soft hyphen → remove
    .replace("\u2060", "")        // word joiner → remove
    .replace("\uFEFF", "")        // zero-width no-break space (BOM) → remove
