package ch.obermuhlner.pdfmarkdown

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object PdfImageConverter {

    fun renderPage(file: File, pageIndex: Int, dpi: Int): BufferedImage {
        Loader.loadPDF(file).use { doc ->
            val renderer = PDFRenderer(doc)
            return renderer.renderImageWithDPI(pageIndex, dpi.toFloat())
        }
    }

    fun renderAllPages(file: File, maxPages: Int = Int.MAX_VALUE, dpi: Int = 72): List<BufferedImage> {
        Loader.loadPDF(file).use { doc ->
            val renderer = PDFRenderer(doc)
            val pagesToProcess = minOf(doc.numberOfPages, maxPages)
            return (0 until pagesToProcess).map { pageIndex ->
                renderer.renderImageWithDPI(pageIndex, dpi.toFloat())
            }
        }
    }

    fun writeImage(image: BufferedImage, file: File, format: String = "PNG") {
        ImageIO.write(image, format, file)
    }

    fun writePageImages(
        pdfFile: File,
        outputDir: File,
        maxPages: Int = Int.MAX_VALUE,
        dpi: Int = 72,
        format: String = "PNG",
    ): List<File> {
        val baseName = pdfFile.nameWithoutExtension
        Loader.loadPDF(pdfFile).use { doc ->
            val renderer = PDFRenderer(doc)
            val pagesToProcess = minOf(doc.numberOfPages, maxPages)
            val createdFiles = mutableListOf<File>()

            for (pageIndex in 0 until pagesToProcess) {
                val image = renderer.renderImageWithDPI(pageIndex, dpi.toFloat())
                val pageNum = pageIndex + 1
                val paddedNum = pageNum.toString().padStart(3, '0')
                val outputFile = File(outputDir, "${baseName}_page_$paddedNum.$format")
                ImageIO.write(image, format, outputFile)
                createdFiles.add(outputFile)
            }
            return createdFiles
        }
    }
}
