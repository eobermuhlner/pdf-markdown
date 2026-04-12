# pdf-markdown

A Kotlin library and CLI tool that converts PDF files to Markdown using deterministic, rule-based heuristics. No LLM, no network access, no API keys required.

## What it does

PDF text extraction is messy. PDFBox gives you a flat list of positioned text runs — no paragraphs, no structure, just glyphs and coordinates. This library reconstructs document structure from those coordinates and produces reasonable Markdown.

It handles:
- Headings (detected by font size ratio relative to the document body size)
- Bold, italic, bold-italic inline formatting
- Fenced code blocks (detected by monospace font)
- Bullet and numbered lists
- Tables (detected by x-position clustering)
- Two-column page layouts
- Block quotes / epigraphs
- Advisory callouts (Note:, Warning:, Tip:, etc.)
- Table of contents entries (stripped to plain text)
- Repeated headers and footers stripped across pages

It does not handle: images, complex nested tables, PDFs that are scanned images, heavily stylised layouts, or documents where structure is encoded purely visually with no font-size variation.

## Use cases

### Human-readable Markdown

The most direct use case: you have PDFs (documentation, reports, papers) and you want to read or edit the content without a PDF viewer, diff it in git, or publish it somewhere that accepts Markdown. The output preserves headings, lists, tables, and code blocks, so the document remains navigable.

### Semantic Markdown for RAG pipelines

Plain text extraction from PDFs throws away structure. A chunk that starts mid-sentence in the middle of a section has no context about what it belongs to. Markdown preserves that structure, which makes a material difference in retrieval quality:

- Heading hierarchy is preserved, so a chunk can carry its section title
- Code blocks are fenced and kept intact rather than merged into surrounding prose
- Tables survive as Markdown tables rather than collapsing into undifferentiated lines
- Bold and italic markup is retained, which signals emphasis and terminology
- Headers and footers are stripped, so repeated boilerplate does not pollute every chunk

The result is that a text splitter operating on the Markdown output can produce chunks with meaningful boundaries and enough context for an embedding model to understand what each chunk is about.

For documents where the deterministic conversion is not accurate enough, the intermediate XML output (`toXml`) provides full positional and typographic metadata that can be fed to an LLM for a higher-quality conversion pass before chunking.

## Getting started

### As a library

Add to your Gradle build:

```kotlin
dependencies {
    implementation("ch.obermuhlner:pdf-markdown:0.1.0")
}
```

```kotlin
import ch.obermuhlner.pdfmarkdown.PdfMarkdown
import java.io.File

val markdown = PdfMarkdown.toMarkdown(File("document.pdf"))
println(markdown)
```

To limit processing to the first N pages:

```kotlin
val markdown = PdfMarkdown.toMarkdown(File("document.pdf"), maxPages = 10)
```

### As a CLI

The CLI can be run in two ways:

**Using Gradle (no build required):**

```bash
./gradlew run --args="markdown document.pdf"
./gradlew run --args="markdown document.pdf output.md"
./gradlew run --args="markdown document.pdf --max-pages 5"
./gradlew run --args="markdown document.pdf --mode rag"
```

Dump the intermediate positional XML (useful for debugging extraction or feeding into an LLM):

```bash
./gradlew run --args="xml document.pdf"
./gradlew run --args="xml document.pdf output.xml --max-pages 10"
```

**Using the installed distribution:**

Install and run:

```bash
./gradlew installDist
./build/install/pdf-markdown/bin/pdf-markdown markdown document.pdf
./build/install/pdf-markdown/bin/pdf-markdown markdown document.pdf output.md
./build/install/pdf-markdown/bin/pdf-markdown markdown document.pdf --max-pages 5
./build/install/pdf-markdown/bin/pdf-markdown markdown document.pdf --mode rag
./build/install/pdf-markdown/bin/pdf-markdown xml document.pdf
```

**Using the fat JAR:**

Build the fat JAR first:

```bash
./gradlew build
```

Then run:

```bash
java -jar build/libs/pdf-markdown-0.1.0.jar markdown document.pdf
java -jar build/libs/pdf-markdown-0.1.0.jar markdown document.pdf output.md
java -jar build/libs/pdf-markdown-0.1.0.jar markdown document.pdf --max-pages 5
java -jar build/libs/pdf-markdown-0.1.0.jar markdown document.pdf --mode rag
java -jar build/libs/pdf-markdown-0.1.0.jar xml document.pdf
```

### CLI Options

- `--max-pages <n>` - Limit the number of pages to process (both `markdown` and `xml` commands)
- `--mode <readable|rag>` - Output mode for `markdown` command: `readable` (default, human-friendly) or `rag` (optimized for RAG pipelines)

## Intermediate XML format

The `toXml()` method (and the `xml` CLI subcommand) emit a positional XML representation of the extracted text before any Markdown conversion takes place:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<document>
  <page number="1">
    <text x="56" y="74" r="312" cx="184" s="xx-large" fs="24" f="bold" h="28">Introduction</text>
    <text x="56" y="110" r="540" cx="298" s="medium" fs="12" f="normal" h="14">This is body text.</text>
  </page>
</document>
```

Attributes: `x`/`r` = left/right x, `cx` = center x, `y` = baseline y, `s` = font size as CSS keyword (`xx-small` through `xx-large`), `fs` = raw font size in points, `f` = font style (`normal`, `bold`, `italic`, `bold-italic`, with optional `-mono` suffix), `h` = line height.

This format is stable and intended as an interchange format — you can pipe it to an LLM if the deterministic conversion is not good enough for your documents.

## How conversion works

1. **Extraction**: PDFBox's text stripper is subclassed to capture each text run as a positioned record with font metadata. Adjacent runs on the same line sharing the same font are merged.

2. **Filtering**: The document-wide mode font size is computed (most frequent font size = body text). Repeated elements appearing on at least one-third of pages are stripped as headers/footers.

3. **Conversion**: Per-page elements are classified into blocks using spatial and typographic heuristics. Two-column layouts are detected and processed column-by-column. Tables are detected by finding rows of elements whose x-positions cluster into two or more distinct columns.

## Building

```bash
./gradlew build      # compile + test + fat JAR
./gradlew test       # tests only
```

## Requirements

- JDK 11+
- Gradle (wrapper included)

## License

MIT