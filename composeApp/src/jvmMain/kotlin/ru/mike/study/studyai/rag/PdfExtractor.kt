package ru.mike.study.studyai.rag

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

actual fun extractPdfText(file: File): String {
    return PDDocument.load(file).use { doc ->
        PDFTextStripper().getText(doc)
    }
}
