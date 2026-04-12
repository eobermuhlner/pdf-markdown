package ch.obermuhlner.pdfmarkdown

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import java.io.File

fun main(args: Array<String>) = PdfMarkdownCli()
    .subcommands(XmlCommand(), MarkdownCommand())
    .main(args)

class PdfMarkdownCli : CliktCommand(
    name = "pdf-markdown",
    invokeWithoutSubcommand = false,
    help = "Convert PDF files to Markdown or intermediate XML.",
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

    override fun run() {
        val markdown = PdfMarkdown.toMarkdown(inputFile, maxPages ?: Int.MAX_VALUE)
        write(markdown, outputFile)
    }
}

private fun write(content: String, outputFile: File?) {
    if (outputFile != null) outputFile.writeText(content)
    else System.out.write(content.toByteArray(Charsets.UTF_8))
}
