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
        private const val MIN_PER_FILE_COVERAGE = 0.65

        /** Average coverage threshold across all evaluated files. */
        private const val MIN_AVERAGE_COVERAGE  = 0.75

        /** Number of files evaluated in normal (non-full) mode. */
        private const val DEFAULT_SAMPLE_SIZE   = 15

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
            ?.listFiles { f -> f.extension == "pdf" }
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
            val name:     String,
            val coverage: Double,
            val actual:   String,
            val truth:    String,
        )

        val results = pairs.map { (pdf, mdFile) ->
            val (pages, mode) = extractFilteredPageElements(pdf)
            val actual = DeterministicMarkdownConverter.convertDocument(pages, mode)
                .joinToString("\n\n")
            val truth = mdFile.readText()
            Result(pdf.nameWithoutExtension, wordCoverage(truth, actual), actual, truth)
        }

        // ── Summary table (always printed) ────────────────────────────────────
        val avg = results.map { it.coverage }.average()
        println()
        println("=== DeterministicMarkdownConverter eval (${results.size} files) ===")
        println("%-12s  %8s".format("File", "Coverage"))
        println("-".repeat(24))
        results.sortedBy { it.name }.forEach { r ->
            val marker = if (r.coverage < MIN_PER_FILE_COVERAGE) " !" else "  "
            println("%-12s  %7.1f%%%s".format(r.name, r.coverage * 100, marker))
        }
        println("-".repeat(24))
        val avgMarker = if (avg < MIN_AVERAGE_COVERAGE) " !" else "  "
        println("%-12s  %7.1f%%%s".format("Average", avg * 100, avgMarker))

        // ── Failure details (printed when a file is below the per-file threshold) ──
        val perFileFailures = results.filter { it.coverage < MIN_PER_FILE_COVERAGE }
        if (perFileFailures.isNotEmpty()) {
            println()
            println("=== FAILURES ===")
            for (r in perFileFailures.sortedBy { it.name }) {
                val missing = missingWords(r.truth, r.actual).take(30)
                println()
                println("--- ${r.name}  (coverage=${"%.1f".format(r.coverage * 100)}%) ---")
                println("Missing words (top 30): ${missing.joinToString(", ")}")
                println("--- Ground truth (first 30 lines) ---")
                r.truth.lines().take(30).forEach { println(it) }
                println("--- Actual output (first 30 lines) ---")
                r.actual.lines().take(30).forEach { println(it) }
                println("---")
            }
        }

        // ── Assertions ────────────────────────────────────────────────────────
        val failMsg = buildString {
            if (perFileFailures.isNotEmpty()) {
                appendLine("${perFileFailures.size} file(s) below per-file threshold " +
                    "(${"%.0f".format(MIN_PER_FILE_COVERAGE * 100)}%):")
                perFileFailures.sortedBy { it.name }
                    .forEach { appendLine("  ${it.name}: ${"%.1f".format(it.coverage * 100)}%") }
            }
            if (avg < MIN_AVERAGE_COVERAGE) {
                appendLine("Average coverage ${"%.1f".format(avg * 100)}% " +
                    "below threshold ${"%.0f".format(MIN_AVERAGE_COVERAGE * 100)}%")
            }
        }.trimEnd()

        assertTrue(failMsg.isEmpty(), failMsg)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
