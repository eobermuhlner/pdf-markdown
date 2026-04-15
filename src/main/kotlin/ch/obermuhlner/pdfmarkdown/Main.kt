package ch.obermuhlner.pdfmarkdown

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.double
import java.io.File

fun main(args: Array<String>) = PdfMarkdownCli()
    .subcommands(XmlCommand(), MarkdownCommand(), ImagesCommand())
    .main(args)

class PdfMarkdownCli : CliktCommand(
    name = "pdf-markdown",
    invokeWithoutSubcommand = false,
    help = """Convert PDF files to Markdown, XML, or images.

Subcommands:

  markdown <input.pdf> [output.md] [options]
    Convert PDF to Markdown using deterministic rule-based conversion.
    Default mode is 'readable' (human-friendly). Use 'rag' for RAG pipelines.

  xml <input.pdf> [output.xml] [options]
    Convert PDF to intermediate positional XML format for LLM processing.

  images <input.pdf> [options]
    Convert PDF pages to PNG images.
    Default output directory is the current working directory.
    Default DPI is 72. Images are named: {pdfname}_page_001.png

Configuration:

  Configuration is loaded from YAML files in the following order (highest first):
    - Project level: .pdf-markdown.yaml in current or parent directory
    - User level: ~/.pdf-markdown.yaml

  See .pdf-markdown.yaml.example for configuration options.

Options:

  --max-pages N  Limit number of pages to process (all pages by default)
  --mode         For markdown: 'readable' (default) or 'rag'
  --dpi N        For images: resolution in dots per inch (default: 72)
""",
) {
    override fun run() = Unit
}

class XmlCommand : CliktCommand(
    name = "xml",
    help = "Convert a PDF to the intermediate positional XML format.",
) {
    private val inputFile: File by argument(help = "Input PDF file").file(mustExist = true, canBeDir = false)
    private val outputFile: File? by argument(help = "Output file (default: stdout)").file().optional()
    private val maxPages: Int? by option("--max-pages", help = "Maximum number of pages to process").int()

    override fun run() {
        val xml = PdfMarkdown.toXml(inputFile, maxPages ?: Int.MAX_VALUE)
        write(xml, outputFile)
    }
}

class MarkdownCommand : CliktCommand(
    name = "markdown",
    help = "Convert a PDF to Markdown using deterministic rule-based conversion (no LLM required).",
) {
    private val inputFile: File by argument(help = "Input PDF file").file(mustExist = true, canBeDir = false)
    private val outputFile: File? by argument(help = "Output file (default: stdout)").file().optional()
    private val maxPages: Int? by option("--max-pages", help = "Maximum number of pages to process").int()
    private val mode: String by option(
        "--mode",
        help = "Output mode: 'readable' (default, human-friendly) or 'rag' (semantic, for RAG pipelines)"
    ).choice("readable", "rag").default("readable")

    // Rule tuning options (as strings to allow optional empty values)
    private val titleMinRatio: String? by option(
        "--title-min-ratio",
        help = "Minimum font size ratio vs. body text for document title (default: 1.10)"
    )

    private val headingMediumMinRatio: String? by option(
        "--heading-medium-min-ratio",
        help = "Minimum font size ratio for bold medium text to be a heading (default: 1.05)"
    )

    private val listMinIndent: String? by option(
        "--list-min-indent",
        help = "Minimum indent for list items (default: 20)"
    )

    private val listMaxIndent: String? by option(
        "--list-max-indent",
        help = "Maximum indent for list items (default: 100)"
    )

    private val tableMinRows: String? by option(
        "--table-min-rows",
        help = "Minimum rows for table detection (default: 3)"
    )

    private val tableMinColumnGap: String? by option(
        "--table-min-column-gap",
        help = "Minimum gap between table columns (default: 30)"
    )

    override fun run() {
        val overrides = mutableMapOf<String, Any?>("mode" to mode)

        titleMinRatio?.toDoubleOrNull()?.let { overrides["titleMinRatio"] = it }
        headingMediumMinRatio?.toDoubleOrNull()?.let { overrides["headingMediumMinRatio"] = it }
        listMinIndent?.toIntOrNull()?.let { overrides["listMinIndent"] = it }
        listMaxIndent?.toIntOrNull()?.let { overrides["listMaxIndent"] = it }
        tableMinRows?.toIntOrNull()?.let { overrides["tableMinRows"] = it }
        tableMinColumnGap?.toIntOrNull()?.let { overrides["tableMinColumnGap"] = it }

        val options = ConfigLoader.loadConfig(overrides = overrides)
        val markdown = PdfMarkdown.toMarkdown(inputFile, maxPages ?: Int.MAX_VALUE, options)
        write(markdown, outputFile)
    }
}

class ImagesCommand : CliktCommand(
    name = "images",
    help = "Convert PDF pages to PNG images.",
) {
    private val inputFile: File by argument(help = "Input PDF file").file(mustExist = true, canBeDir = false)
    private val outputDir: File? by option("--output-dir", help = "Output directory (default: current directory)").file(canBeDir = true)
    private val maxPages: Int? by option("--max-pages", help = "Maximum number of pages to process").int()
    private val dpi: Int by option("--dpi", help = "Resolution in dots per inch (default: 72)").int().default(72)

    override fun run() {
        val effectiveOutputDir = outputDir ?: File(".")
        effectiveOutputDir.mkdirs()
        val files = PdfMarkdown.toImageFiles(inputFile, maxPages ?: Int.MAX_VALUE, effectiveOutputDir, dpi)
        echo("Created ${files.size} image(s) in ${effectiveOutputDir.absolutePath}")
    }
}

private fun write(content: String, outputFile: File?) {
    if (outputFile != null) outputFile.writeText(content)
    else System.out.write(content.toByteArray(Charsets.UTF_8))
}
