package ch.obermuhlner.pdfmarkdown

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Evaluation test for [DeterministicMarkdownConverter].
 *
 * Serves two purposes:
 * 1. **Regression guard** — fails when word-coverage drops below the thresholds, so any
 *    accidental regression in the converter is caught by `./gradlew test`.
 * 2. **Agentic improvement loop** — on failure, prints rich diagnostics (missing words,
 *    ground-truth vs actual output) so an agent can identify the pattern and fix the
 *    converter without needing extra file reads.
 *
 * Run modes:
 * - Default (`./gradlew test`): evaluates [DEFAULT_SAMPLE_SIZE] PDF/MD pairs committed to
 *   `src/test/resources/eval/` — no download or setup required.
 * - Full (`./gradlew test -Deval.full=true`): evaluates all pairs in [FULL_EVAL_DIR].
 *   Requires the dataset to be downloaded first: `./gradlew downloadEvalData`
 * - Custom dir (`./gradlew test -Deval.dir=/path/to/dir`): evaluates all PDF/MD pairs in
 *   the given directory. Skipped when the directory does not exist.
 */
@Tag("eval")
class DeterministicMarkdownConverterEvalTest {

    companion object {
        /** Per-file coverage threshold — a single file below this fails the suite. */
        private const val MIN_PER_FILE_COVERAGE = 0.97

        /** Average coverage threshold across all evaluated files. */
        private const val MIN_AVERAGE_COVERAGE  = 0.99

        /** Per-file structural score threshold (average of per-type element-count ratios). */
        private const val MIN_PER_FILE_STRUCTURAL = 0.98

        /** Average structural score threshold across all evaluated files. */
        private const val MIN_AVERAGE_STRUCTURAL  = 0.98

        /** Number of files evaluated in normal (non-full) mode. */
        private const val DEFAULT_SAMPLE_SIZE   = Int.MAX_VALUE

        /** Committed test fixtures — always available, no download needed. */
        private val RESOURCES_EVAL_DIR: File by lazy {
            val url = DeterministicMarkdownConverterEvalTest::class.java.getResource("/eval")
            if (url != null) File(url.toURI()) else File("src/test/resources/eval")
        }

        /** Full dataset directory — populated by `./gradlew downloadEvalData`. */
        private val FULL_EVAL_DIR = File("data/pdf/synthetic/data/001")
    }

    @Test
    fun `word coverage on synthetic sample`() {
        val customDirProp = System.getProperty("eval.dir")
        val fullMode = System.getProperty("eval.full") == "true"

        val dataDir: File
        val maxFiles: Int
        when {
            customDirProp != null -> {
                dataDir = File(customDirProp)
                assumeTrue(
                    dataDir.isDirectory,
                    "Custom eval dir not found: ${dataDir.path}"
                )
                maxFiles = Int.MAX_VALUE
            }
            fullMode -> {
                assumeTrue(
                    FULL_EVAL_DIR.isDirectory,
                    "Full eval data not found at ${FULL_EVAL_DIR.path}. " +
                    "Run: ./gradlew downloadEvalData"
                )
                dataDir = FULL_EVAL_DIR
                maxFiles = Int.MAX_VALUE
            }
            else -> {
                dataDir = RESOURCES_EVAL_DIR
                maxFiles = DEFAULT_SAMPLE_SIZE
            }
        }

        val pairs = dataDir
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.extension == "pdf" }
            ?.toList()
            ?.sortedBy { it.name }
            ?.take(maxFiles)
            ?.mapNotNull { pdf ->
                val md = pdf.resolveSibling("${pdf.nameWithoutExtension}.md")
                if (md.exists()) pdf to md else null
            }
            .orEmpty()

        assumeTrue(pairs.isNotEmpty(),
            "No PDF+MD pairs found in ${dataDir.path} — skipping eval")

        data class Result(
            val name:       String,
            val coverage:   Double,
            val structural: StructuralScore,
            val actual:     String,
            val truth:      String,
        )

        val results = pairs.map { (pdf, mdFile) ->
            val (pages, mode) = extractFilteredPageElements(pdf)
            val actual = DeterministicMarkdownConverter.convertDocument(pages, mode)
                .joinToString("\n\n")
            val truth = mdFile.readText()
            Result(
                pdf.nameWithoutExtension,
                wordCoverage(truth, actual),
                structuralScore(truth, actual),
                actual,
                truth,
            )
        }

        // ── Summary table (always printed) ────────────────────────────────────
        val avg = results.map { it.coverage }.average()
        val avgStructural = results.map { it.structural.overall }.average()  // overall already excludes -1 sentinels
        println()
        println("=== DeterministicMarkdownConverter eval (${results.size} files) ===")
        println("%-20s  %8s  %10s  %8s  %8s  %8s  %8s".format(
            "File", "Coverage", "Structural", "Headings", "Tables", "Lists", "Code"))
        println("-".repeat(84))
        fun fmt(v: Double) = if (v < 0.0) "%8s".format("n/a") else "%7.1f%%".format(v * 100)
        results.sortedBy { it.name }.forEach { r ->
            val wMark = if (r.coverage < MIN_PER_FILE_COVERAGE) "!" else " "
            val sMark = if (r.structural.overall < MIN_PER_FILE_STRUCTURAL) "!" else " "
            println("%-20s  %7.1f%% %s %8.1f%% %s %s  %s  %s  %s".format(
                r.name,
                r.coverage * 100, wMark,
                r.structural.overall * 100, sMark,
                fmt(r.structural.headings),
                fmt(r.structural.tables),
                fmt(r.structural.lists),
                fmt(r.structural.code),
            ))
        }
        println("-".repeat(84))
        val wAvgMark = if (avg < MIN_AVERAGE_COVERAGE) "!" else " "
        val sAvgMark = if (avgStructural < MIN_AVERAGE_STRUCTURAL) "!" else " "
        fun avgFmt(values: List<Double>): String {
            val valid = values.filter { it >= 0.0 }
            return if (valid.isEmpty()) "%8s".format("n/a") else "%7.1f%%".format(valid.average() * 100)
        }
        println("%-20s  %7.1f%% %s %8.1f%% %s %s  %s  %s  %s".format(
            "Average",
            avg * 100, wAvgMark,
            avgStructural * 100, sAvgMark,
            avgFmt(results.map { it.structural.headings }),
            avgFmt(results.map { it.structural.tables }),
            avgFmt(results.map { it.structural.lists }),
            avgFmt(results.map { it.structural.code }),
        ))

        // ── Failure details (printed when a file is below any per-file threshold) ──
        val perFileFailures = results.filter {
            it.coverage < MIN_PER_FILE_COVERAGE || it.structural.overall < MIN_PER_FILE_STRUCTURAL
        }
        if (perFileFailures.isNotEmpty()) {
            println()
            println("=== FAILURES ===")
            for (r in perFileFailures.sortedBy { it.name }) {
                val missing = missingWords(r.truth, r.actual).take(30)
                println()
                println("--- ${r.name}  (coverage=${"%.1f".format(r.coverage * 100)}%," +
                    " structural=${"%.1f".format(r.structural.overall * 100)}%) ---")
                println("Missing words (top 30): ${missing.joinToString(", ")}")
                // Per-type missing word details for structural failures
                if (r.structural.overall < MIN_PER_FILE_STRUCTURAL) {
                    val missingH = missingWords(extractLines(r.truth, LineType.HEADING), extractLines(r.actual, LineType.HEADING)).take(20)
                    val missingT = missingWords(extractLines(r.truth, LineType.TABLE), extractLines(r.actual, LineType.TABLE)).take(20)
                    val missingL = missingWords(extractLines(r.truth, LineType.LIST), extractLines(r.actual, LineType.LIST)).take(20)
                    val missingC = missingWords(extractLines(r.truth, LineType.CODE), extractLines(r.actual, LineType.CODE)).take(20)
                    if (missingH.isNotEmpty()) println("  Missing heading words: ${missingH.joinToString(", ")}")
                    if (missingT.isNotEmpty()) println("  Missing table words:   ${missingT.joinToString(", ")}")
                    if (missingL.isNotEmpty()) println("  Missing list words:    ${missingL.joinToString(", ")}")
                    if (missingC.isNotEmpty()) println("  Missing code words:    ${missingC.joinToString(", ")}")
                }
                println("--- Ground truth (first 30 lines) ---")
                r.truth.lines().take(30).forEach { println(it) }
                println("--- Actual output (first 30 lines) ---")
                r.actual.lines().take(30).forEach { println(it) }
                println("---")
            }
        }

        // ── Assertions ────────────────────────────────────────────────────────
        val failMsg = buildString {
            if (results.any { it.coverage < MIN_PER_FILE_COVERAGE }) {
                val failures = results.filter { it.coverage < MIN_PER_FILE_COVERAGE }
                appendLine("${failures.size} file(s) below per-file word-coverage threshold " +
                    "(${"%.0f".format(MIN_PER_FILE_COVERAGE * 100)}%):")
                failures.sortedBy { it.name }
                    .forEach { appendLine("  ${it.name}: ${"%.1f".format(it.coverage * 100)}%") }
            }
            if (avg < MIN_AVERAGE_COVERAGE) {
                appendLine("Average word coverage ${"%.1f".format(avg * 100)}% " +
                    "below threshold ${"%.0f".format(MIN_AVERAGE_COVERAGE * 100)}%")
            }
            if (results.any { it.structural.overall < MIN_PER_FILE_STRUCTURAL }) {
                val failures = results.filter { it.structural.overall < MIN_PER_FILE_STRUCTURAL }
                appendLine("${failures.size} file(s) below per-file structural threshold " +
                    "(${"%.0f".format(MIN_PER_FILE_STRUCTURAL * 100)}%):")
                failures.sortedBy { it.name }
                    .forEach { appendLine("  ${it.name}: ${"%.1f".format(it.structural.overall * 100)}%" +
                        " (headings=${"%.1f".format(it.structural.headings * 100)}%," +
                        " tables=${"%.1f".format(it.structural.tables * 100)}%," +
                        " lists=${"%.1f".format(it.structural.lists * 100)}%," +
                        " code=${"%.1f".format(it.structural.code * 100)}%)") }
            }
            if (avgStructural < MIN_AVERAGE_STRUCTURAL) {
                appendLine("Average structural score ${"%.1f".format(avgStructural * 100)}% " +
                    "below threshold ${"%.0f".format(MIN_AVERAGE_STRUCTURAL * 100)}%")
            }
        }.trimEnd()

        assertTrue(failMsg.isEmpty(), failMsg)
    }

    // ── Structural scoring ────────────────────────────────────────────────────

    data class StructuralScore(
        val headings: Double,
        val tables:   Double,
        val lists:    Double,
        val code:     Double,
    ) {
        /** Average across all element types that have at least one occurrence in truth. */
        val overall: Double get() {
            val values = listOf(headings, tables, lists, code).filter { it >= 0.0 }
            return if (values.isEmpty()) 1.0 else values.average()
        }
    }

    private enum class LineType { HEADING, TABLE, LIST, CODE }

    /**
     * Extracts lines of the given structural type from a Markdown string.
     * Code blocks are extracted as the content between fence markers.
     */
    private fun extractLines(text: String, type: LineType): String {
        val lines = text.lines()
        return when (type) {
            LineType.HEADING -> lines.filter { it.matches(Regex("^#{1,6} .*")) }.joinToString("\n")
            LineType.TABLE   -> lines.filter { it.trimStart().startsWith("|") &&
                                               !it.trimStart().matches(Regex("^\\|[-| :]+\\|\\s*")) }
                                     .joinToString("\n")
            LineType.LIST    -> lines.filter { it.matches(Regex("^[-*] .*")) ||
                                               it.matches(Regex("^\\d+[.)].+")) }.joinToString("\n")
            LineType.CODE    -> {
                val sb = StringBuilder()
                var inCode = false
                for (line in lines) {
                    if (line.trimStart().startsWith("```")) { inCode = !inCode; continue }
                    if (inCode) sb.appendLine(line)
                }
                sb.toString()
            }
        }
    }

    /**
     * Returns element-count ratios for each structural type.
     * A ratio of -1.0 means the truth has zero occurrences (excluded from overall score).
     */
    private fun structuralScore(truth: String, actual: String): StructuralScore {
        fun countRatio(truthText: String, actualText: String): Double {
            val tCount = truthText.lines().count { it.isNotBlank() }
            if (tCount == 0) return -1.0
            val aCount = actualText.lines().count { it.isNotBlank() }
            return (aCount.toDouble() / tCount).coerceAtMost(1.0)
        }
        return StructuralScore(
            headings = countRatio(extractLines(truth, LineType.HEADING), extractLines(actual, LineType.HEADING)),
            tables   = countRatio(extractLines(truth, LineType.TABLE),   extractLines(actual, LineType.TABLE)),
            lists    = countRatio(extractLines(truth, LineType.LIST),    extractLines(actual, LineType.LIST)),
            code     = countRatio(extractLines(truth, LineType.CODE),    extractLines(actual, LineType.CODE)),
        )
    }

    // ── Word coverage helpers ─────────────────────────────────────────────────

    private fun wordCoverage(groundTruth: String, actual: String): Double {
        val truthWords = extractWords(groundTruth)
        if (truthWords.isEmpty()) return 1.0
        val actualWords = extractWords(actual)
        return truthWords.count { it in actualWords }.toDouble() / truthWords.size
    }

    private fun missingWords(groundTruth: String, actual: String): List<String> {
        val actualWords = extractWords(actual)
        return extractWords(groundTruth)
            .filter { it !in actualWords }
            .distinct()
            .sorted()
    }

    private fun extractWords(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .toSet()
}
