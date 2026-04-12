# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build (compiles + creates fat JAR + CLI distribution)
./gradlew build

# Build thin JAR only (for library use)
./gradlew jar -Pversion=0.1.0

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "ch.obermuhlner.pdfmarkdown.PdfMarkdownTest"

# Run a single test by name (use wildcards for backtick-named tests)
./gradlew test --tests "ch.obermuhlner.pdfmarkdown.PdfMarkdownTest.mergeElements*"

# Run the CLI
./gradlew run --args="markdown input.pdf"
./gradlew run --args="xml input.pdf"
./gradlew run --args="markdown input.pdf output.md --max-pages 5"

# Publish to local staging directory (build/staging-deploy)
./gradlew publishMavenJavaPublicationToStagingRepository

# Create release (tag format: v{major}.{minor}.{patch})
git tag v0.1.0
git push --tags
```

## Architecture

The pipeline has three stages:

**1. Extraction — `PositionalTextStripper.kt`**
Subclasses PDFBox's `PDFTextStripper` to capture each text run as a `TextElement` (x, y, endX, height, fontSize, font style, text). After extraction, `mergeElements()` coalesces adjacent runs sharing the same font and y-position into single elements. Font style is normalised to one of: `normal`, `bold`, `italic`, `bold-italic`, plus a `-mono` suffix for monospace fonts.

**2. Filtering — `PdfMarkdown.kt`**
`extractFilteredPageElements()` runs extraction per page, then strips repeated headers/footers by detecting `(x, y, trimmedText)` triples that appear on ≥ ⌈N/3⌉ pages. It also computes the document-wide mode font size (most frequent fontSize), used throughout as the "body size" baseline.

**3. Conversion — `DeterministicMarkdownConverter.kt`**
`convertDocument()` processes per-page `TextElement` lists into `Block` objects (`Heading`, `Paragraph`, `ListItems`, `CodeBlock`, `Advisory`, `Epigraph`, `Table`) and renders them to Markdown strings. Key heuristics:
- Font size ratio vs. mode font size → heading level
- x-position relative to `bodyMargin` → list indentation vs. epigraph
- Monospace font → fenced code block
- ≥2 x-clusters × ≥3 rows → table
- Two-column layout detection splits elements into left/right halves before processing
- ToC entries are detected by triple-underscore leaders and stripped to plain text

**Public API — `PdfMarkdown.kt`**
The `PdfMarkdown` object is the sole public entry point:
- `PdfMarkdown.toMarkdown(file, maxPages)` — full pipeline to Markdown
- `PdfMarkdown.toXml(file, maxPages)` — extraction only, emitting positional XML (useful for debugging or LLM-based post-processing)

**CLI — `Main.kt`**
Built with Clikt. Two subcommands (`markdown`, `xml`) wrap the `PdfMarkdown` API. The CLI distribution can be built with `./gradlew distZip` and run directly after unzipping.

## Key design constraint

All conversion is fully deterministic — no LLM, no network. Heuristics are tuned for typical structured PDFs (technical docs, books). The intermediate XML format (`toXml`) is a stable interchange format intended for LLM-based post-processing when deterministic rules are insufficient.
