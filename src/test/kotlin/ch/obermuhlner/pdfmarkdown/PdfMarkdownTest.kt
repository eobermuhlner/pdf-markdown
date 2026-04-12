package ch.obermuhlner.pdfmarkdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
    fun `detectRepeatedElements finds elements appearing on multiple pages`() {
        val el = TextElement(x = 10, y = 10, endX = 50, height = 12, fontSize = 12, font = "normal", text = "Header")
        val page1 = listOf(el, TextElement(x = 10, y = 100, endX = 200, height = 12, fontSize = 12, font = "normal", text = "Content 1"))
        val page2 = listOf(el, TextElement(x = 10, y = 100, endX = 200, height = 12, fontSize = 12, font = "normal", text = "Content 2"))
        val repeated = detectRepeatedElements(listOf(page1, page2), minPages = 2)
        assertEquals(setOf(Triple(10, 10, "Header")), repeated)
    }
}
