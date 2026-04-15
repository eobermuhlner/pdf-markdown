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
- `--title-min-ratio <ratio>` - Minimum font size ratio vs. body text for document title (default: 1.10)
- `--heading-medium-min-ratio <ratio>` - Minimum font size ratio for bold medium text to be a heading (default: 1.05)
- `--list-min-indent <n>` - Minimum indent for list items (default: 20)
- `--list-max-indent <n>` - Maximum indent for list items (default: 100)
- `--table-min-rows <n>` - Minimum rows for table detection (default: 3)
- `--table-min-column-gap <n>` - Minimum gap between table columns (default: 30)

## Configuration

pdf-markdown supports configuration files for fine-tuning conversion rules without specifying CLI flags each time.

### Configuration File

Create a `.pdf-markdown.yaml` file in your project directory or home directory:

```yaml
# Conversion mode (optional)
mode: readable  # or: rag

# Rule tuning parameters
ruleTuning:
  # Heading detection
  titleMinRatio: 1.10              # Min font size ratio for document title (H1)
  headingMediumMinRatio: 1.05       # Min ratio for bold medium text to be heading (H5)

  # Paragraph detection
  paragraphMaxYGapMultiplier: 2.0   # Max gap between paragraph lines (as multiple of line height)
  paragraphMaxXDistance: 150        # Max x-distance before considering different layout zones

  # List detection
  listMinIndent: 20                # Min x-indent for list items
  listMaxIndent: 100               # Max x-indent for list items
  listYGapMultiplier: 3.0          # Max gap between list items
  listXVariance: 5                 # Max x-distance variation between list items

  # Table detection
  tableMinColumnGap: 30            # Min gap between table columns
  tableMinRows: 3                  # Min rows for table detection
  tableRowTolerance: 12             # Y-position tolerance for grouping rows

  # Column detection
  columnDetectionMinElements: 6     # Min elements on page for column detection
  columnHistogramBuckets: 50        # Number of histogram buckets
  columnMarginFraction: 0.10       # Page margin fraction to exclude
  columnMinGapBuckets: 3           # Min empty buckets for column gap
  columnMinSizeFraction: 0.15      # Min column size as fraction
  columnMinSizeAbsolute: 4           # Min column size (absolute)
  columnCoherenceXDistance: 30     # Max x-distance for column coherence
  columnCoherenceMinFraction: 0.50  # Min fraction for coherence

  # Drop-initial detection
  dropInitialMinRows: 2             # Min rows for drop-initial
  dropInitialMaxLength: 3           # Max character length of drop-initial
  dropInitialYDistanceFraction: 0.5 # Max y-distance to companion
  dropInitialXDistanceMultiplier: 3.0 # Max x-distance to companion

  # Code block detection
  codeYGapMultiplier: 2.0          # Max gap between code lines

  # Epigraph detection
  epigraphYGapMultiplier: 3.0      # Max gap between epigraph lines
  epigraphAttributionYGapMultiplier: 4.0 # Max gap to attribution
  epigraphAttributionMaxLength: 70  # Max attribution line length
```

### Configuration Precedence

Configuration values are merged in the following order (highest wins):

1. CLI flags (command-line arguments)
2. Project-level config (`.pdf-markdown.yaml` in current or parent directory)
3. User-level config (`~/.pdf-markdown.yaml`)
4. Defaults (built-in sensible values)

### Programmatic Configuration

```kotlin
import ch.obermuhlner.pdfmarkdown.{PdfMarkdown, ConversionOptions, RuleTuning}
import java.io.File

// Use default readable mode
val markdown = PdfMarkdown.toMarkdown(File("document.pdf"))

// Customize with rule tuning
val options = ConversionOptions(
    stripInlineFormatting = true,
    includeToc = false,
    ruleTuning = RuleTuning(
        listMinIndent = 30,
        tableMinRows = 4,
        titleMinRatio = 1.15
    )
)
val markdown = PdfMarkdown.toMarkdown(File("document.pdf"), options = options)

// Use RAG preset with custom tuning
val ragOptions = ConversionOptions.RAG.copy(
    ruleTuning = ConversionOptions.RAG.ruleTuning.copy(
        tableMinRows = 5
    )
)
```

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

## CI/CD

This project uses GitHub Actions for continuous integration and release builds.

### Workflows

- **Build** — Runs on every push/PR to `master`. Builds and tests the project.
- **Release** — Runs when a version tag is pushed. Creates a GitHub Release with multi-platform artifacts.

### Creating a Release

```bash
# 1. Ensure you're on master and have the latest
git checkout master
git pull origin master

# 2. Build and test locally
./gradlew build -Pversion=0.1.0

# 3. Create a version tag (format: v{major}.{minor}.{patch})
git tag v0.1.0

# 4. Push the tag to trigger the release workflow
git push --tags
```

The release workflow builds on Ubuntu and creates a GitHub Release with these artifacts:

| Artifact | Description | Usage |
|----------|-------------|-------|
| `pdf-markdown-{VERSION}.zip` | CLI application | `unzip && cd pdf-markdown-{VERSION} && ./bin/pdf-markdown ...` |
| `pdf-markdown-{VERSION}.jar` | Thin JAR | Add as Gradle/Maven dependency |
| `pdf-markdown-{VERSION}-sources.jar` | Library sources | For IDE dependency navigation |
| `pdf-markdown-{VERSION}-javadoc.jar` | API documentation | For IDE offline docs |

### Requirements

- JDK 11+
- Gradle (wrapper included)

## License

MIT