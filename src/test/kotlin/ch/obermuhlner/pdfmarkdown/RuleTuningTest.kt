package ch.obermuhlner.pdfmarkdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuleTuningTest {

    @Test fun `default values are sensible`() {
        val tuning = RuleTuning()

        assertEquals(1.10, tuning.titleMinRatio)
        assertEquals(1.05, tuning.headingMediumMinRatio)
        assertEquals(2.0, tuning.paragraphMaxYGapMultiplier)
        assertEquals(150, tuning.paragraphMaxXDistance)
        assertEquals(20, tuning.listMinIndent)
        assertEquals(100, tuning.listMaxIndent)
        assertEquals(30, tuning.tableMinColumnGap)
        assertEquals(3, tuning.tableMinRows)
    }

    @Test fun `withOverrides applies single override`() {
        val tuning = RuleTuning()
        val modified = tuning.withOverrides(mapOf("titleMinRatio" to 1.25))

        assertEquals(1.25, modified.titleMinRatio)
        assertEquals(1.05, modified.headingMediumMinRatio)
    }

    @Test fun `withOverrides applies multiple overrides`() {
        val tuning = RuleTuning()
        val modified = tuning.withOverrides(mapOf(
            "listMinIndent" to 30,
            "listMaxIndent" to 150,
            "tableMinRows" to 5
        ))

        assertEquals(30, modified.listMinIndent)
        assertEquals(150, modified.listMaxIndent)
        assertEquals(5, modified.tableMinRows)
    }

    @Test fun `withOverrides ignores unknown keys`() {
        val tuning = RuleTuning()
        val modified = tuning.withOverrides(mapOf(
            "unknownKey" to "value",
            "titleMinRatio" to 1.5
        ))

        assertEquals(1.5, modified.titleMinRatio)
    }

    @Test fun `withOverrides uses default when override value is null`() {
        val tuning = RuleTuning()
        val modified = tuning.withOverrides(mapOf("titleMinRatio" to null))

        assertEquals(1.10, modified.titleMinRatio)
    }

    @Test fun `withOverrides handles string conversion`() {
        val tuning = RuleTuning()
        val modified = tuning.withOverrides(mapOf(
            "titleMinRatio" to "1.15",
            "listMinIndent" to "25",
            "columnMinSizeAbsolute" to "6"
        ))

        assertEquals(1.15, modified.titleMinRatio)
        assertEquals(25, modified.listMinIndent)
        assertEquals(6, modified.columnMinSizeAbsolute)
    }

    @Test fun `default bullet prefix chars include common bullet characters`() {
        val tuning = RuleTuning()
        assertEquals("•■*□·-", tuning.bulletPrefixChars)
    }

    @Test fun `default advisory labels include common callout types`() {
        val tuning = RuleTuning()
        assertEquals("Note|Warning|Tip|Important|Caution|Remark", tuning.advisoryLabels)
    }

    @Test fun `withOverrides applies custom bullet prefix chars`() {
        val tuning = RuleTuning()
        val modified = tuning.withOverrides(mapOf("bulletPrefixChars" to "►▶➤"))

        assertEquals("►▶➤", modified.bulletPrefixChars)
    }

    @Test fun `withOverrides applies custom advisory labels`() {
        val tuning = RuleTuning()
        val modified = tuning.withOverrides(mapOf("advisoryLabels" to "Info|Error|Debug"))

        assertEquals("Info|Error|Debug", modified.advisoryLabels)
    }
}

class ConversionOptionsTest {

    @Test fun `READABLE preset has sensible defaults`() {
        val options = ConversionOptions.READABLE

        assertEquals(false, options.stripInlineFormatting)
        assertEquals(true, options.includeToc)
        assertEquals(ConversionOptions.EpigraphFormat.BLOCKQUOTE, options.epigraphFormat)
        assertEquals(ConversionOptions.AdvisoryFormat.BLOCKQUOTE, options.advisoryFormat)
        assertEquals(false, options.normalizeTableSpans)
    }

    @Test fun `RAG preset is optimized for pipelines`() {
        val options = ConversionOptions.RAG

        assertEquals(true, options.stripInlineFormatting)
        assertEquals(false, options.includeToc)
        assertEquals(ConversionOptions.EpigraphFormat.PLAIN, options.epigraphFormat)
        assertEquals(ConversionOptions.AdvisoryFormat.PLAIN, options.advisoryFormat)
        assertEquals(true, options.normalizeTableSpans)
    }

    @Test fun `custom options override defaults`() {
        val options = ConversionOptions(
            stripInlineFormatting = true,
            includeToc = false,
            ruleTuning = RuleTuning(listMinIndent = 30, tableMinRows = 4)
        )

        assertEquals(true, options.stripInlineFormatting)
        assertEquals(false, options.includeToc)
        assertEquals(30, options.ruleTuning.listMinIndent)
        assertEquals(4, options.ruleTuning.tableMinRows)
    }

    @Test fun `RAG preset has default rule tuning`() {
        val options = ConversionOptions.RAG

        assertNotNull(options.ruleTuning)
        assertEquals(20, options.ruleTuning.listMinIndent)
    }
}
