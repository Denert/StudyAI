package ru.mike.study.studyai.rag.chunking

import ru.mike.study.studyai.rag.RagChunk
import java.util.UUID

class StructureChunker(
    private val maxChunkSize: Int = 1500
) : Chunker {

    override val strategyName = "structure"

    // Matches lines like "34 Глава 1. Знакомство с алгоритмами"
    // Page number (1-3 digits) followed by "Глава N."
    private val pageHeaderRegex = Regex("""^\d{1,3}\s+Глава\s+[\dЗзЧчДдПпШшВвСсОо]\d*[..]?\s*.+$""")

    override fun chunk(text: String, source: String): List<RagChunk> {
        val chunks = mutableListOf<RagChunk>()
        val lines = text.lines()

        var currentTitle = source
        var currentPageNum = ""
        val currentContent = StringBuilder()

        fun flushChunk() {
            val content = currentContent.toString().trim()
            if (content.isBlank()) return
            splitBySize(content, source, currentTitle, currentPageNum, chunks)
            currentContent.clear()
        }

        for (line in lines) {
            if (pageHeaderRegex.matches(line.trim())) {
                flushChunk()
                currentTitle = line.trim()
                currentPageNum = line.trim().substringBefore(" ")
                currentContent.append(line).append("\n")
            } else {
                currentContent.append(line).append("\n")
            }
        }
        flushChunk()

        // Fallback: no page headers found — split by fixed size
        if (chunks.isEmpty() && text.isNotBlank()) {
            return FixedSizeChunker(maxChunkSize).chunk(text, source)
        }

        return chunks
    }

    private fun splitBySize(
        content: String,
        source: String,
        title: String,
        section: String,
        chunks: MutableList<RagChunk>
    ) {
        if (content.length <= maxChunkSize) {
            chunks.add(makeChunk(content, source, title, section))
            return
        }
        val buffer = StringBuilder()
        for (para in content.split("\n\n")) {
            if (para.length > maxChunkSize) {
                // Flush current buffer first
                if (buffer.isNotBlank()) {
                    chunks.add(makeChunk(buffer.toString().trim(), source, title, section))
                    buffer.clear()
                }
                // Hard-split the oversized paragraph by characters
                var start = 0
                while (start < para.length) {
                    val end = minOf(start + maxChunkSize, para.length)
                    chunks.add(makeChunk(para.substring(start, end), source, title, section))
                    start = end
                }
            } else {
                if (buffer.length + para.length > maxChunkSize && buffer.isNotBlank()) {
                    chunks.add(makeChunk(buffer.toString().trim(), source, title, section))
                    buffer.clear()
                }
                buffer.append(para).append("\n\n")
            }
        }
        if (buffer.isNotBlank()) {
            chunks.add(makeChunk(buffer.toString().trim(), source, title, section))
        }
    }

    private fun makeChunk(content: String, source: String, title: String, section: String) = RagChunk(
        chunkId = UUID.randomUUID().toString(),
        source = source,
        title = title,
        section = section,
        content = content,
        strategy = strategyName,
        charCount = content.length
    )
}
