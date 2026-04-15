package ch.obermuhlner.pdfmarkdown

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfMarkdownTest {

    @Test
    fun `mergeElements returns empty list for empty input`() {
        assertEquals(emptyList(), mergeElements(emptyList()))
    }

    @Test
    fun `mergeElements merges adjacent same-font elements on the same line`() {
        val a = TextElement(x = 10, y = 100, endX = 50, height = 12, fontSize = 12, font = "normal", text = "Hello")
        val b = TextElement(x = 53, y = 100, endX = 90, height = 12, fontSize = 12, font = "normal", text = "World")
        val merged = mergeElements(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("Hello World", merged[0].text)
    }

    @Test
    fun `mergeElements keeps elements with different fonts separate`() {
        val a = TextElement(x = 10, y = 100, endX = 50, height = 12, fontSize = 12, font = "normal", text = "Hello")
        val b = TextElement(x = 51, y = 100, endX = 90, height = 12, fontSize = 12, font = "bold", text = "World")
        val result = mergeElements(listOf(a, b))
        assertEquals(2, result.size)
    }

    @Test
    fun `fontSizeToCssKeyword returns medium for body text`() {
        assertEquals("medium", fontSizeToCssKeyword(12, 12))
    }

    @Test
    fun `fontSizeToCssKeyword returns large for 1_2x body size`() {
        assertEquals("large", fontSizeToCssKeyword(14, 12))
    }

    @Test
    fun `escapeXml escapes ampersand and angle brackets`() {
        assertEquals("&amp;&lt;&gt;", escapeXml("&<>"))
    }

    @Test
    fun `page 7 of trading guide has no O UCH`() {
        val file = java.io.File("src/test/resources/trading-guide.pdf")
        if (!file.exists()) return  // skip if PDF not available
        val (pageElements, _) = extractFilteredPageElements(file, maxPages = 7)
        val page7texts = pageElements.lastOrNull()?.map { it.text } ?: emptyList()
        val allText = page7texts.joinToString(" ")
        // Dump character codes around "O UCH" for debugging
        val problematic = page7texts.filter { it.contains("O UCH") }
        if (problematic.isNotEmpty()) {
            val s = problematic.first()
            val idx = s.indexOf("O UCH")
            val chars = s.substring(maxOf(0, idx - 2), minOf(s.length, idx + 10))
                .map { "'$it'(${it.code})" }.joinToString(", ")
            error("Found 'O UCH' in page 7. Chars around it: $chars")
        }
    }

    @Test
    fun `normalizeSpreadText collapses O UCH into OUCH`() {
        val input = "QTI Good-for-Business-Day (pre-opening, continuous trading, closing auction, post trading) O UCH Trading Interface (OTI)"
        val result = normalizeSpreadText(input)
        assert(result.contains("OUCH")) { "Expected OUCH but got: $result" }
        assert(!result.contains("O UCH")) { "Still contains O UCH: $result" }
    }

    @Test
    fun `normalizeSpreadText leaves valid as of 01 intact`() {
        assertEquals("valid as of 01 July 2024", normalizeSpreadText("valid as of 01 July 2024"))
    }

    @Test
    fun `detectRepeatedElements finds elements appearing on multiple pages`() {
        val el = TextElement(x = 10, y = 10, endX = 50, height = 12, fontSize = 12, font = "normal", text = "Header")
        val page1 = listOf(el, TextElement(x = 10, y = 100, endX = 200, height = 12, fontSize = 12, font = "normal", text = "Content 1"))
        val page2 = listOf(el, TextElement(x = 10, y = 100, endX = 200, height = 12, fontSize = 12, font = "normal", text = "Content 2"))
        val repeated = detectRepeatedElements(listOf(page1, page2), minPages = 2)
        assertEquals(setOf(Triple(10, 10, "Header")), repeated)
    }

    @Test
    fun `toImages renders correct number of pages`(@TempDir tempDir: Path) {
        val pdfFile = File("src/test/resources/trading-guide.pdf")
        if (!pdfFile.exists()) return

        val images = PdfMarkdown.toImages(pdfFile, maxPages = 3, dpi = 72)
        assertEquals(3, images.size)
        images.forEach { image ->
            assertTrue(image.width > 0)
            assertTrue(image.height > 0)
        }
    }

    @Test
    fun `toImageFiles creates PNG files`(@TempDir tempDir: Path) {
        val pdfFile = File("src/test/resources/trading-guide.pdf")
        if (!pdfFile.exists()) return

        val outputDir = tempDir.toFile()
        val files = PdfMarkdown.toImageFiles(pdfFile, maxPages = 2, outputDir = outputDir, dpi = 72)

        assertEquals(2, files.size)
        assertTrue(files[0].name.lowercase().endsWith(".png"))
        assertTrue(files[1].name.lowercase().endsWith(".png"))
        assertTrue(files[0].exists())
        assertTrue(files[1].exists())
        assertTrue(files[0].length() > 0)
        assertTrue(files[1].length() > 0)
    }

    @Test
    fun `toImageFiles names files with pdf filename prefix`(@TempDir tempDir: Path) {
        val pdfFile = File("src/test/resources/trading-guide.pdf")
        if (!pdfFile.exists()) return

        val outputDir = tempDir.toFile()
        val files = PdfMarkdown.toImageFiles(pdfFile, maxPages = 3, outputDir = outputDir, dpi = 72)

        assertEquals("trading-guide_page_001", files[0].nameWithoutExtension)
        assertEquals("trading-guide_page_002", files[1].nameWithoutExtension)
        assertEquals("trading-guide_page_003", files[2].nameWithoutExtension)
    }
}
