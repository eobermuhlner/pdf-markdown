package ch.obermuhlner.pdfmarkdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterministicMarkdownConverterTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Shorthand constructor for [TextElement].
     * [endX] defaults to a rough estimate based on text length.
     * [height] defaults to match [fontSize] (normal for body text).
     */
    private fun el(
        x: Int, y: Int,
        text: String,
        font: String = "normal",
        fontSize: Int = 12,
        height: Int = fontSize,
        endX: Int = x + text.length * 7,
    ) = TextElement(x, y, endX, height, fontSize, font, text)

    /**
     * Convert a list of elements as a single page with body [modeFontSize]=12.
     * Set [isFirst]=true to enable first-page title detection.
     */
    private fun page(
        vararg elements: TextElement,
        isFirst: Boolean = false,
        modeFontSize: Int = 12,
    ): String = DeterministicMarkdownConverter.convertPage(
        elements = elements.toList(),
        modeFontSize = modeFontSize,
        isFirstPage = isFirst,
    )

    /**
     * Three body-text lines that anchor [modeFontSize]=12 when combined with a
     * larger title element.  Placed well below y=200 so they do not interfere
     * with the elements under test.
     */
    private fun bodyAnchor(startY: Int = 300): List<TextElement> = listOf(
        el(72, startY,      "Body text line one."),
        el(72, startY + 14, "Body text line two."),
        el(72, startY + 28, "Body text line three."),
    )

    // ─── isPageNumber ─────────────────────────────────────────────────────────

    @Test fun `page number bare digit`() =
        assertTrue(DeterministicMarkdownConverter.isPageNumber("1"))

    @Test fun `page number padded digit`() =
        assertTrue(DeterministicMarkdownConverter.isPageNumber(" 2 "))

    @Test fun `page number with dashes`() =
        assertTrue(DeterministicMarkdownConverter.isPageNumber("- 3 -"))

    @Test fun `page number fraction`() =
        assertTrue(DeterministicMarkdownConverter.isPageNumber("3 / 10"))

    @Test fun `page number 4 digit`() =
        assertTrue(DeterministicMarkdownConverter.isPageNumber("1234"))

    @Test fun `not page number body text`() =
        assertFalse(DeterministicMarkdownConverter.isPageNumber("Introduction"))

    @Test fun `not page number long sentence`() =
        assertFalse(DeterministicMarkdownConverter.isPageNumber("This is a sentence with number 42 inside."))

    // ─── Heading detection ────────────────────────────────────────────────────

    @Test fun `title h1 on first page large bold`() {
        val elements = listOf(el(72, 100, "My Document", font = "bold", fontSize = 24)) + bodyAnchor()
        val result = DeterministicMarkdownConverter.convertPage(elements, modeFontSize = 12, isFirstPage = true)
        assertTrue(result.lines().any { it == "# My Document" })
    }

    @Test fun `no title h1 on second page`() {
        val elements = listOf(el(72, 100, "My Document", font = "bold", fontSize = 24)) + bodyAnchor()
        val result = DeterministicMarkdownConverter.convertPage(
            elements, modeFontSize = 12, isFirstPage = false, titleAlreadyUsed = false
        )
        assertFalse(result.lines().any { it.startsWith("# ") })
    }

    @Test fun `h2 bold numbered section`() {
        val result = page(el(72, 100, "1. Introduction", font = "bold"))
        assertTrue(result.contains("## 1. Introduction"))
    }

    @Test fun `h2 bold numbered section no space after period`() {
        val result = page(el(72, 100, "2.The Method", font = "bold"))
        assertTrue(result.contains("## 2.The Method"))
    }

    @Test fun `h2 bold all caps`() {
        val result = page(el(72, 100, "ABSTRACT", font = "bold"))
        assertTrue(result.contains("## ABSTRACT"))
    }

    @Test fun `no heading for normal font numbered line`() {
        val result = page(el(72, 100, "1. Just a list item", font = "normal"))
        assertFalse(result.contains("##"))
    }

    @Test fun `no heading for mixed-case normal font line`() {
        val result = page(el(72, 100, "Some body text.", font = "normal"))
        assertFalse(result.contains("#"))
    }

    // ─── Multi-line heading merge ─────────────────────────────────────────────

    @Test fun `multi-line title merged into single h1`() {
        val elements = listOf(
            el(72, 100, "A Very Long",   font = "bold", fontSize = 24),
            el(72, 124, "Document Title", font = "bold", fontSize = 24),
        ) + bodyAnchor()
        val result = DeterministicMarkdownConverter.convertPage(elements, modeFontSize = 12, isFirstPage = true)
        val headings = result.lines().filter { it.startsWith("# ") }
        assertEquals(1, headings.size)
        assertTrue(headings[0].contains("A Very Long"))
        assertTrue(headings[0].contains("Document Title"))
    }

    @Test fun `different font breaks heading merge`() {
        val elements = listOf(
            el(72, 100, "First Heading",  font = "bold", fontSize = 24),
            el(72, 124, "Second Heading", font = "bold", fontSize = 18),
        ) + bodyAnchor()
        val result = DeterministicMarkdownConverter.convertPage(elements, modeFontSize = 12, isFirstPage = true)
        // Both sizes qualify as headings but should not be merged
        val headings = result.lines().filter { it.startsWith("#") }
        assertEquals(2, headings.size)
    }

    // ─── Paragraph joining ────────────────────────────────────────────────────

    @Test fun `adjacent same-font lines joined into paragraph`() {
        val result = page(
            el(72, 100, "First line of"),
            el(72, 112, "the paragraph."),
        )
        assertTrue(result.contains("First line of the paragraph."))
    }

    @Test fun `large y-gap separates paragraphs`() {
        val result = page(
            el(72, 100, "Paragraph one."),
            el(72, 300, "Paragraph two."),
        )
        assertTrue(result.contains("Paragraph one."))
        assertTrue(result.contains("Paragraph two."))
        // They must appear as separate blocks (two occurrences in different lines)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
    }

    @Test fun `different fonts separate paragraphs`() {
        val result = page(
            el(72, 100, "Normal text.",  font = "normal"),
            el(72, 112, "Bold follows.", font = "bold"),
        )
        assertTrue(result.contains("Normal text."))
        assertTrue(result.contains("Bold follows."))
        val blocks = result.trim().split(Regex("\n\n+"))
        assertEquals(2, blocks.size)
    }

    // ─── Hyphenation repair ───────────────────────────────────────────────────

    @Test fun `trailing hyphen joined to next line`() {
        val result = page(
            el(72, 100, "infor-"),
            el(72, 112, "mation"),
        )
        assertTrue(result.contains("information"))
        assertFalse(result.contains("infor-"))
    }

    @Test fun `trailing hyphen not removed before uppercase`() {
        // "Anti-" + "Social" — uppercase initial means the hyphen is kept;
        // the paragraph joiner still inserts a space, giving "Anti- Social"
        val result = page(
            el(72, 100, "Anti-"),
            el(72, 112, "Social"),
        )
        assertTrue(result.contains("Anti-"))
        assertTrue(result.contains("Social"))
        assertFalse(result.contains("AntiSocial"))
    }

    // ─── Emphasis ─────────────────────────────────────────────────────────────

    @Test fun `italic body text wrapped in asterisks`() {
        val result = page(el(72, 100, "Some italic text", font = "italic"))
        assertTrue(result.contains("*Some italic text*"))
    }

    @Test fun `bold non-heading text wrapped in double asterisks`() {
        // "Metadata label:" is bold but not a heading candidate
        val result = page(el(72, 100, "Author: Jane Doe", font = "bold"))
        assertTrue(result.contains("**Author: Jane Doe**"))
    }

    @Test fun `bold-italic text wrapped in triple asterisks`() {
        val result = page(el(72, 100, "Critical point", font = "bold-italic"))
        assertTrue(result.contains("***Critical point***"))
    }

    // ─── Code blocks ──────────────────────────────────────────────────────────

    @Test fun `monospace line produces fenced code block`() {
        val result = page(el(72, 100, "def foo(): pass", font = "normal-mono"))
        assertTrue(result.contains("```"))
        assertTrue(result.contains("def foo(): pass"))
    }

    @Test fun `consecutive monospace lines share one code block`() {
        val result = page(
            el(72, 100, "def foo():",    font = "normal-mono"),
            el(72, 112, "return 42",     font = "normal-mono"),
        )
        // Exactly one opening and one closing fence (opening may have a language tag)
        val fenceCount = result.lines().count { it.trim().startsWith("```") }
        assertEquals(2, fenceCount)
        assertTrue(result.contains("def foo():"))
        assertTrue(result.contains("return 42"))
    }

    @Test fun `monospace gap too large splits into two code blocks`() {
        val result = page(
            el(72, 100, "block one", font = "normal-mono"),
            el(72, 300, "block two", font = "normal-mono"),
        )
        val fenceCount = result.lines().count { it.trim().startsWith("```") }
        assertEquals(4, fenceCount)  // two opens + two closes
    }

    // ─── Indented list items ──────────────────────────────────────────────────

    @Test fun `x-indented elements become list items`() {
        // Two lines at x=72 anchor the body margin; items at x=100 are indented
        val result = page(
            el(72, 100, "Intro line one."),
            el(72, 112, "Intro line two."),
            el(100, 200, "First item"),
            el(100, 212, "Second item"),
        )
        assertTrue(result.contains("- First item"))
        assertTrue(result.contains("- Second item"))
    }

    @Test fun `single indented element not forced into list`() {
        val result = page(
            el(72, 100, "Margin anchor one."),
            el(72, 112, "Margin anchor two."),
            el(100, 200, "Lone indented line"),
        )
        // A single element does not meet the ≥2 threshold for indent-based lists
        assertFalse(result.contains("- Lone indented line"))
    }

    @Test fun `far-right element not treated as indented list item`() {
        // Indented cap is bodyMargin+100; x=72+200=272 is beyond the cap
        val result = page(
            el(72,  100, "Left margin one."),
            el(72,  112, "Left margin two."),
            el(272, 200, "Right column line one"),
            el(272, 212, "Right column line two"),
        )
        // Should be a paragraph, not a list
        assertFalse(result.lines().any { it.startsWith("- Right column") })
    }

    // ─── Bullet / numbered list by text prefix ────────────────────────────────

    @Test fun `bullet character prefix creates list item`() {
        val result = page(el(72, 100, "• First bullet"))
        assertTrue(result.startsWith("- "))
        assertTrue(result.contains("First bullet"))
    }

    @Test fun `dash prefix creates list item`() {
        val result = page(el(72, 100, "- dash item"))
        assertTrue(result.startsWith("- "))
    }

    @Test fun `numbered prefix items grouped as list`() {
        val result = page(
            el(72, 100, "1. First item"),
            el(72, 112, "2. Second item"),
        )
        assertTrue(result.contains("- 1. First item"))
        assertTrue(result.contains("- 2. Second item"))
    }

    // ─── Advisory callouts ────────────────────────────────────────────────────

    @Test fun `bold note callout`() {
        val result = page(el(72, 100, "Note: pay attention here", font = "bold"))
        assertTrue(result.contains("> **Note:**"))
        assertTrue(result.contains("pay attention here"))
    }

    @Test fun `italic warning callout`() {
        val result = page(el(72, 100, "Warning: this may fail", font = "italic"))
        assertTrue(result.contains("> **Warning:**"))
    }

    @Test fun `normal font advisory not treated as callout`() {
        // Advisory detection requires bold or italic font
        val result = page(el(72, 100, "Note: something", font = "normal"))
        assertFalse(result.contains("> **Note:**"))
    }

    // ─── Epigraph block-quote ─────────────────────────────────────────────────

    @Test fun `two consecutive italic small lines become epigraph`() {
        val result = DeterministicMarkdownConverter.convertPage(
            elements = listOf(
                TextElement(72, 100, 300, 9, 9, "italic", "To be or not to be,"),
                TextElement(72, 109, 300, 9, 9, "italic", "that is the question."),
            ),
            modeFontSize = 12,
        )
        assertTrue(result.contains("> To be or not to be,"))
        assertTrue(result.contains("> that is the question."))
    }

    @Test fun `single italic small line not an epigraph`() {
        val result = DeterministicMarkdownConverter.convertPage(
            elements = listOf(
                TextElement(72, 100, 300, 9, 9, "italic", "Lone italic line."),
            ),
            modeFontSize = 12,
        )
        assertFalse(result.startsWith("> "))
    }

    @Test fun `epigraph with attribution line`() {
        // The renderer prepends "> — " to the attribution text, so the source
        // element should NOT already contain the dash.
        val result = DeterministicMarkdownConverter.convertPage(
            elements = listOf(
                TextElement(72, 100, 300, 9, 9, "italic", "Words to live by,"),
                TextElement(72, 109, 300, 9, 9, "italic", "every single day."),
                TextElement(72, 125, 200, 9, 9, "normal", "Aristotle"),
            ),
            modeFontSize = 12,
        )
        assertTrue(result.contains("> — Aristotle"))
    }

    // ─── Table detection ──────────────────────────────────────────────────────

    @Test fun `table rendered as GFM table`() {
        // Design note: the page column detector splits pages with large x-gaps.
        // A 3-column table whose columns are each tight clusters (x=50, 200, 350)
        // passes the coherence check on both sides of every split, which causes
        // the page to be split into 3 separate columns of 3 elements each —
        // too few (< 6) for table detection to fire.
        //
        // Using 4 tightly-spaced columns (x=50, 200, 280, 370) causes the
        // coherence check to fail on the left-most and right-most potential
        // split points (too many sub-clusters on one side), leaving only the
        // middle split valid.  Each resulting half has 6 elements: exactly the
        // minimum for table detection, which then fires correctly.
        val result = DeterministicMarkdownConverter.convertPage(
            elements = listOf(
                TextElement(50,  100, 100, 12, 12, "bold",   "Option"),
                TextElement(200, 100, 240, 12, 12, "bold",   "Status"),
                TextElement(280, 100, 330, 12, 12, "bold",   "Priority"),
                TextElement(370, 100, 420, 12, 12, "bold",   "Owner"),
                TextElement(50,  120, 100, 12, 12, "normal", "Alpha"),
                TextElement(200, 120, 240, 12, 12, "normal", "Running"),
                TextElement(280, 120, 330, 12, 12, "normal", "High"),
                TextElement(370, 120, 420, 12, 12, "normal", "Alice"),
                TextElement(50,  140, 100, 12, 12, "normal", "Beta"),
                TextElement(200, 140, 240, 12, 12, "normal", "Stopped"),
                TextElement(280, 140, 330, 12, 12, "normal", "Low"),
                TextElement(370, 140, 420, 12, 12, "normal", "Bob"),
            ),
            modeFontSize = 12,
        )
        assertTrue(result.contains("| --- |"), "Expected GFM table separator row")
        assertTrue(result.contains("Option") || result.contains("Status"))
        assertTrue(result.contains("Alpha") || result.contains("Running"))
    }

    @Test fun `two rows not enough for table`() {
        val result = DeterministicMarkdownConverter.convertPage(
            elements = listOf(
                TextElement(50,  100, 130, 12, 12, "bold",   "Name"),
                TextElement(200, 100, 280, 12, 12, "bold",   "Value"),
                TextElement(50,  120, 130, 12, 12, "normal", "Alice"),
                TextElement(200, 120, 280, 12, 12, "normal", "Score: A"),
            ),
            modeFontSize = 12,
        )
        assertFalse(result.contains("| --- |"))
    }

    // ─── ToC entry normalisation ──────────────────────────────────────────────

    @Test fun `toc underscore leader stripped and not a heading`() {
        val result = page(el(72, 100, "1. Introduction _____ 5", font = "bold"))
        assertFalse(result.contains("___"))
        assertFalse(result.contains("## "))
        assertTrue(result.contains("Introduction"))
    }

    // ─── Two-column layout ────────────────────────────────────────────────────

    @Test fun `two-column page processes each column top-to-bottom`() {
        // Left column: x=50, right column: x=350 — gap too wide to be a single column
        val result = DeterministicMarkdownConverter.convertPage(
            elements = listOf(
                TextElement(50,  100, 180, 12, 12, "bold",   "1. Left Heading"),
                TextElement(50,  120, 180, 12, 12, "normal", "Left body text."),
                TextElement(350, 100, 480, 12, 12, "bold",   "2. Right Heading"),
                TextElement(350, 120, 480, 12, 12, "normal", "Right body text."),
            ) + (0 until 8).flatMap { i ->
                listOf(
                    TextElement(50,  200 + i * 14, 180, 12, 12, "normal", "Left filler $i"),
                    TextElement(350, 200 + i * 14, 480, 12, 12, "normal", "Right filler $i"),
                )
            },
            modeFontSize = 12,
        )
        assertTrue(result.contains("Left body text."))
        assertTrue(result.contains("Right body text."))
        assertTrue(result.contains("## 1. Left Heading"))
        assertTrue(result.contains("## 2. Right Heading"))
    }

    // ─── convertDocument — cross-page title tracking ──────────────────────────

    @Test fun `title h1 used only on first page`() {
        val titleEl  = { TextElement(72, 100, 300, 22, 24, "bold", "My Document") }
        val page1 = listOf(titleEl()) +
            listOf(TextElement(72, 140, 400, 12, 12, "normal", "Body line one."),
                   TextElement(72, 152, 400, 12, 12, "normal", "Body line two."),
                   TextElement(72, 164, 400, 12, 12, "normal", "Body line three."))
        val page2 = listOf(TextElement(72, 100, 300, 22, 24, "bold", "Chapter One")) +
            listOf(TextElement(72, 140, 400, 12, 12, "normal", "More body text one."),
                   TextElement(72, 152, 400, 12, 12, "normal", "More body text two."),
                   TextElement(72, 164, 400, 12, 12, "normal", "More body text three."))

        val pages = DeterministicMarkdownConverter.convertDocument(
            pageElementsList = listOf(page1, page2),
            docModeFontSize = 12,
        )

        val allH1 = pages.flatMap { it.lines() }.filter { it.startsWith("# ") }
        assertEquals(1, allH1.size, "Title should appear as # exactly once")
        assertEquals("# My Document", allH1[0])
    }

    @Test fun `empty page returns empty string`() {
        val result = DeterministicMarkdownConverter.convertPage(emptyList(), modeFontSize = 12)
        assertEquals("", result)
    }

    @Test fun `page with only page number returns empty string`() {
        val result = page(el(289, 750, "- 3 -"))
        assertEquals("", result)
    }
}
