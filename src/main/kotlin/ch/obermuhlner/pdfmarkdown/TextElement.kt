package ch.obermuhlner.pdfmarkdown

/**
 * A single run of text extracted from a PDF page, carrying positional and typographic metadata.
 *
 * @property x       Left x-coordinate (points)
 * @property y       Baseline y-coordinate (points)
 * @property endX    Right x-coordinate (points)
 * @property height  Line height (points)
 * @property fontSize Font size in points
 * @property font    Normalised font style: one of `normal`, `bold`, `italic`, `bold-italic`,
 *                   or any of those with a `-mono` suffix for monospace fonts
 * @property text    Decoded text content
 */
data class TextElement(
    val x: Int,
    val y: Int,
    val endX: Int,
    val height: Int,
    val fontSize: Int,
    val font: String,
    val text: String,
)
