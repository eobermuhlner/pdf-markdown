package ch.obermuhlner.pdfmarkdown

/**
 * A single run of text extracted from a PDF page, carrying positional and typographic metadata.
 *
 * @property x       Left x-coordinate (points)
 * @property y       Baseline y-coordinate (points)
 * @property endX    Right x-coordinate (points)
 * @property height  Line height (points)
 * @property fontSize Font size in points
 * @property font       Normalised font style: one of `normal`, `bold`, `italic`, `bold-italic`,
 *                      or any of those with a `-mono` suffix for monospace fonts
 * @property fontWeight Raw font weight from the PDF font descriptor (e.g. 300, 400, 700).
 *                      Defaults to 400 when no descriptor is available.
 *                      Used for document-relative bold detection and merge gating.
 * @property text    Decoded text content
 * @property rawFont Raw PDFBox font metadata, only populated when extraction is run in raw mode.
 *                   Format: `"<fullName>|fw=<weight>|fb=<forceBold>|fi=<italic>"`,
 *                   e.g. `"BJOPBO+CiscoSans-Light|fw=300.0|fb=false|fi=false"`.
 */
data class TextElement(
    val x: Int,
    val y: Int,
    val endX: Int,
    val height: Int,
    val fontSize: Int,
    val font: String,
    val text: String,
    val fontWeight: Int = 400,
    val rawFont: String? = null,
)
