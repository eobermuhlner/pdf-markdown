package ch.obermuhlner.pdfmarkdown

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDSimpleFont
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PDFBox [PDFTextStripper] subclass that captures per-glyph position, size, and font metadata
 * into a list of [TextElement] objects rather than plain text.
 */
class PositionalTextStripper(private val rawMode: Boolean = false) : PDFTextStripper() {

    companion object {
        /**
         * Supplementary glyph name → Unicode mapping for TeX/CM math fonts.
         *
         * PDFBox resolves glyph names via the Adobe Glyph List (AGL), which does not include
         * TeX-specific names used in Computer Modern fonts (CMMI, CMSY, CMEX, etc.).  When PDFBox
         * cannot map a glyph it emits a WARNING and the character becomes `?`.  This table covers
         * the most common TeX math glyph names so they survive text extraction.
         *
         * Combining-character glyphs (e.g. `vector` = combining right-arrow-above, `negationslash`)
         * and TeX-internal control glyphs (e.g. `suppress`) are mapped to the empty string because
         * they have no meaningful standalone rendering in plain text.
         */
        val TEX_MATH_GLYPH_MAP: Map<String, String> = buildMap {
            // ── CMSY – Computer Modern Symbol ─────────────────────────────────
            put("lessmuch",        "\u226A") // ≪
            put("greatermuch",     "\u226B") // ≫
            put("negationslash",   "")       // combining solidus overlay — skip
            put("element",         "\u2208") // ∈
            put("owner",           "\u220B") // ∋
            put("propersubset",    "\u2282") // ⊂
            put("propersuperset",  "\u2283") // ⊃
            put("reflexsubset",    "\u2286") // ⊆
            put("reflexsuperset",  "\u2287") // ⊇
            put("union",           "\u222A") // ∪
            put("intersection",    "\u2229") // ∩
            put("unionmulti",      "\u228E") // ⊎
            put("logicaland",      "\u2227") // ∧
            put("logicalor",       "\u2228") // ∨
            put("arrowleft",       "\u2190") // ←
            put("arrowright",      "\u2192") // →
            put("arrowup",         "\u2191") // ↑
            put("arrowdown",       "\u2193") // ↓
            put("arrowboth",       "\u2194") // ↔
            put("arrownortheast",  "\u2197") // ↗
            put("arrowsoutheast",  "\u2198") // ↘
            put("arrownorthwest",  "\u2196") // ↖
            put("arrowsouthwest",  "\u2199") // ↙
            put("arrowdblleft",    "\u21D0") // ⇐
            put("arrowdblright",   "\u21D2") // ⇒
            put("arrowdblup",      "\u21D1") // ⇑
            put("arrowdbldown",    "\u21D3") // ⇓
            put("arrowdblboth",    "\u21D4") // ⇔
            put("mapsto",          "\u21A6") // ↦
            put("universal",       "\u2200") // ∀
            put("existential",     "\u2203") // ∃
            put("emptyset",        "\u2205") // ∅
            put("infinity",        "\u221E") // ∞
            put("proportional",    "\u221D") // ∝
            put("prime",           "\u2032") // ′
            put("integral",        "\u222B") // ∫
            put("logicalnot",      "\u00AC") // ¬
            put("perpendicular",   "\u22A5") // ⊥
            put("latticetop",      "\u22A4") // ⊤
            put("aleph",           "\u2135") // ℵ
            put("Rfractur",        "\u211C") // ℜ
            put("Ifractur",        "\u2111") // ℑ
            put("circleplus",      "\u2295") // ⊕
            put("circleminus",     "\u2296") // ⊖
            put("circlemultiply",  "\u2297") // ⊗
            put("circledivide",    "\u2298") // ⊘
            put("circledot",       "\u2299") // ⊙
            put("plusminus",       "\u00B1") // ±
            put("minusplus",       "\u2213") // ∓
            put("similar",         "\u223C") // ∼
            put("approxequal",     "\u2248") // ≈
            put("equivalence",     "\u2261") // ≡
            put("lessequal",       "\u2264") // ≤
            put("greaterequal",    "\u2265") // ≥
            put("precedesequal",   "\u2AAF") // ⪯
            put("followsequal",    "\u2AB0") // ⪰
            put("precedes",        "\u227A") // ≺
            put("follows",         "\u227B") // ≻
            put("equivasymptotic", "\u224D") // ≍
            put("similarequal",    "\u2243") // ≃
            put("turnstileleft",   "\u22A2") // ⊢
            put("turnstileright",  "\u22A3") // ⊣
            put("floorleft",       "\u230A") // ⌊
            put("floorright",      "\u230B") // ⌋
            put("ceilingleft",     "\u2308") // ⌈
            put("ceilingright",    "\u2309") // ⌉
            put("angbracketleft",  "\u27E8") // ⟨
            put("angbracketright", "\u27E9") // ⟩
            put("bardbl",          "\u2016") // ‖
            put("triangle",        "\u25B3") // △
            put("triangleinv",     "\u25BD") // ▽
            put("bullet",          "\u2022") // •
            put("openbullet",      "\u25E6") // ◦
            put("asteriskmath",    "\u2217") // ∗
            put("diamondmath",     "\u22C4") // ⋄
            put("minus",           "\u2212") // −
            // ── CMMI – Computer Modern Math Italic ────────────────────────────
            put("lscript",         "\u2113") // ℓ (script l)
            put("vector",          "")       // combining right-arrow accent — skip
            put("partial",         "\u2202") // ∂
            put("nabla",           "\u2207") // ∇
            // ── MSAM / MSBM – AMS symbol fonts ────────────────────────────────
            put("square",          "\u25A1") // □
            put("blacksquare",     "\u25A0") // ■
            put("checkmark",       "\u2713") // ✓
            put("circledR",        "\u00AE") // ®
            // ── CMR / OT1 TeX internals ────────────────────────────────────────
            put("suppress",        "")       // kerning-suppression control glyph
            put("visiblespace",    "\u2423") // ␣
        }
    }
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
        val fontSize = first.fontSizeInPt.roundToInt()
        val height = textPositions.maxOf { it.heightDir }.roundToInt()
        val font = normalizeFontStyle(first.font)
        val fontWeight = first.font.fontDescriptor?.fontWeight?.toInt() ?: 400
        val y = first.yDirAdj.roundToInt()

        // Split runs at large intra-run gaps (column-sized whitespace that PDFBox delivers
        // as a single text run). Only possible when text:position mapping is 1-to-1.
        val splitThreshold = if (fontSize > 0) fontSize * 3.0 else height * 4.0
        val splitPoints: List<Int> = if (text.length == textPositions.size) {
            (0 until textPositions.size - 1).filter { i ->
                textPositions[i + 1].xDirAdj - (textPositions[i].xDirAdj + textPositions[i].width) > splitThreshold
            }
        } else emptyList()

        val rawFont = if (rawMode) rawFontInfo(first.font) else null

        if (splitPoints.isEmpty()) {
            // Fast path — single element (existing behaviour)
            val x = first.xDirAdj.roundToInt()
            val last = textPositions.last()
            val endX = (last.xDirAdj + last.width).roundToInt()
            elements.add(
                TextElement(
                    x, y, endX, height, fontSize, font,
                    normalizeText(remapFallbackGlyphs(text, textPositions)),
                    fontWeight = fontWeight,
                    rawFont = rawFont,
                )
            )
        } else {
            // Slow path — emit one element per segment between split points
            val boundaries = listOf(-1) + splitPoints + listOf(textPositions.size - 1)
            for (b in 0 until boundaries.size - 1) {
                val start = boundaries[b] + 1
                val end = boundaries[b + 1]
                val segText = text.substring(start, end + 1)
                if (segText.isBlank()) continue
                val segPositions = textPositions.subList(start, end + 1)
                val segFirst = segPositions.first()
                val segLast = segPositions.last()
                val segX = segFirst.xDirAdj.roundToInt()
                val segEndX = (segLast.xDirAdj + segLast.width).roundToInt()
                elements.add(
                    TextElement(
                        segX, y, segEndX, height, fontSize, font,
                        normalizeText(remapFallbackGlyphs(segText, segPositions)),
                        fontWeight = fontWeight,
                        rawFont = rawFont,
                    )
                )
            }
        }
    }

    /**
     * PDFBox maps a glyph to `?` (U+003F) when it has no Unicode entry in the font's ToUnicode
     * CMap. Such a `?` is a fallback placeholder, not a real question mark. We detect this by
     * comparing the decoded character against the raw character code: a genuine `?` has code 0x3F;
     * any other code means PDFBox fell back.
     *
     * For fallback-mapped glyphs we first try a supplementary lookup: if the font is a simple font
     * (Type1 / TrueType) with an explicit encoding, we retrieve the glyph name and consult
     * [TEX_MATH_GLYPH_MAP] for common TeX/CM math glyph names missing from the standard AGL.
     * If the lookup succeeds the Unicode string is used; if not, the glyph is replaced with `□`
     * (U+25A1 WHITE SQUARE) as a neutral, visually recognisable placeholder.
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
                val tp = textPositions[i]
                val codes = tp.characterCodes
                val isRealQuestion = ch != '?' || (codes.isNotEmpty() && codes[0] == 0x3F)
                if (isRealQuestion) {
                    append(ch)
                } else {
                    val glyphName = getGlyphName(tp)
                    when {
                        glyphName != null && glyphName in TEX_MATH_GLYPH_MAP ->
                            append(TEX_MATH_GLYPH_MAP[glyphName])
                        else -> append('□')
                    }
                }
            }
        }
    }

    /** Returns the glyph name for a [TextPosition] from the font's encoding, or null. */
    private fun getGlyphName(tp: TextPosition): String? {
        val font = tp.font
        if (font is PDSimpleFont) {
            val codes = tp.characterCodes
            if (codes.isNotEmpty()) {
                return try { font.encoding?.getName(codes[0]) } catch (_: Exception) { null }
            }
        }
        return null
    }
}

/**
 * Merges adjacent [TextElement]s on the same line that share the same font and size into a single
 * element.  Elements are first sorted by (y, x) so merging is left-to-right within each line.
 *
 * After each merge run, [normalizeSpreadText] is applied to collapse artificially-spaced text
 * such as "B u si n e ss  D a y" (produced by PDFBox when extracting rotated/vertical glyphs)
 * into readable words like "Business Day".
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
            result.add(current.copy(text = normalizeSpreadText(current.text)))
            current = next
        }
    }
    result.add(current.copy(text = normalizeSpreadText(current.text)))
    return result
}

fun canMerge(a: TextElement, b: TextElement): Boolean {
    val yTolerance = maxOf(2, a.fontSize / 4)
    // When fontSize is zero or negative (rotated/vertical text extracted by PDFBox yields
    // implausible sizes), fall back to the element's physical height so that adjacent glyphs
    // on the same baseline can still be merged.  A 2× height multiplier is used (vs 1.0× for
    // normal text) because individual-glyph extractions can have zero-width bounding boxes
    // with inter-glyph gaps that exactly equal 1.0× height.
    //
    // 1.0× fontSize is intentionally tighter than the former 1.5×:
    // - Typical inter-word space is ~0.3× fontSize → merges fine at 1.0×.
    // - Column gaps in compact financial tables are ~1.3–2× fontSize → no longer merged,
    //   allowing the table detector to see the correct x-clusters.
    val maxGap = if (a.fontSize > 0) a.fontSize.toDouble() * 1.0 else a.height.toDouble() * 2.0
    return abs(a.y - b.y) <= yTolerance &&
            a.font == b.font &&
            abs(a.fontWeight - b.fontWeight) <= 50 &&
            abs(a.fontSize - b.fontSize) <= 1 &&
            b.x >= a.x &&
            (b.x - a.endX) < maxGap
}

/**
 * Collapses artificially-spaced text that PDFBox produces when extracting rotated glyphs.
 *
 * PDFBox extracts vertical/rotated text character-by-character, inserting spaces between
 * each glyph.  This leaves strings like "B u si n e ss  D a y" or "T ra d in g" in the
 * element text.  The heuristic works in two passes:
 *
 * 1. Split on 2+ consecutive spaces (which mark real word boundaries in spread text).
 * 2. Within each part, collapse runs of 3+ consecutive 1–2-char tokens (or 2 single-char
 *    tokens) into one word, leaving longer tokens unchanged.
 *
 * Examples:
 *   "B u si n e ss  D a y"               →  "Business Day"
 *   "T ra d in g -A t- L a st"           →  "Trading-At-Last"
 *   "E n d  o f  T ra d in g"            →  "End of Trading"
 *   "Q T I Good-for-Business-Day OUCH"   →  "QTI Good-for-Business-Day OUCH"
 *   "Market Model and Matching Rules"    →  unchanged  (tokens longer than 2 chars)
 */
fun normalizeSpreadText(text: String): String {
    if (text.length < 3) return text
    val parts = text.split(Regex(" {2,}")).mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val tokens = trimmed.split(' ').filter { it.isNotEmpty() }
        when {
            // Entire part is short tokens: collapse all
            tokens.size >= 3 && tokens.all { it.length <= 2 } -> tokens.joinToString("")
            tokens.size == 2 && tokens.all { it.length == 1 } -> tokens.joinToString("")
            // Mixed content: collapse consecutive runs of letter-bearing 1–2-char tokens
            // anywhere in the text.  A run must have ≥3 tokens (or 2 single-char tokens)
            // to be collapsed.  Non-letter tokens (digits, punctuation like "01", "|")
            // break the run so that "valid as of 01 July" is left intact.
            else -> collapseLetterRuns(tokens)
        }
    }
    if (parts.isEmpty()) return text.trim()
    return parts.joinToString(" ")
}

/**
 * Returns raw PDFBox font metadata as a pipe-delimited string for debugging.
 * Format: `"<fullName>|fw=<weight>|fb=<forceBold>|fi=<italic>"`
 * where weight/fb/fi come from the font descriptor (`"?"` if no descriptor is present).
 */
fun rawFontInfo(font: PDFont): String {
    val fullName = font.name ?: ""
    val descriptor = font.fontDescriptor
    val fw = descriptor?.fontWeight?.toString() ?: "?"
    val fb = descriptor?.isForceBold?.toString() ?: "?"
    val fi = descriptor?.isItalic?.toString() ?: "?"
    return "$fullName|fw=$fw|fb=$fb|fi=$fi"
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

/**
 * Collapses runs of spread-text tokens inside a pre-split token list.
 *
 * A run begins with a single-letter token (a clear sign that PDFBox split a glyph out
 * individually from rotated/vertical text) and continues while subsequent tokens are
 * "spread-like" (see [isSpreadFragment]).  The run is collapsed only when it has ≥ 2 tokens.
 * Long words and tokens with no letters break any run, so normal text like
 * "valid as of 01 July 2024" is left intact.
 */
private fun collapseLetterRuns(tokens: List<String>): String {
    val sb = StringBuilder()
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        if (t.length == 1 && t[0].isLetter()) {
            // Single-letter token: potential start of a spread run.
            var j = i
            while (j < tokens.size && isSpreadFragment(tokens[j])) j++
            val run = tokens.subList(i, j)
            if (sb.isNotEmpty()) sb.append(' ')
            if (run.size >= 2) sb.append(run.joinToString(""))
            else sb.append(t)
            i = j
        } else {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
            i++
        }
    }
    return sb.toString()
}

/**
 * Returns true when [t] looks like a fragment of a word split by PDFBox character-by-character
 * extraction from rotated/vertical text:
 *   - a single letter (e.g. "O", "U", "C", "H"),
 *   - a 2-char token containing at least one letter (e.g. "si", "ra"), or
 *   - a 3–4-char token made entirely of uppercase letters (e.g. "UCH" from "OUCH",
 *     "QTI", "OTI" from vertical column labels).
 */
private fun isSpreadFragment(t: String): Boolean = when (t.length) {
    1    -> t[0].isLetter()
    2    -> t.any { it.isLetter() }
    3, 4 -> t.all { it.isUpperCase() }
    else -> false
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
    .replace(Regex("[\uE000-\uF8FF]"), "")  // BMP Private Use Area — font-specific glyph codes with no text meaning
