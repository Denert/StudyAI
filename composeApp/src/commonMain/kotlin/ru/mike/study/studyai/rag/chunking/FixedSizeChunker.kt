package ru.mike.study.studyai.rag.chunking

import ru.mike.study.studyai.rag.RagChunk
import java.util.UUID

class FixedSizeChunker(
    private val chunkSize: Int = 1500,
    private val overlap: Int = 200
) : Chunker {

    override val strategyName = "fixed"

    override fun chunk(text: String, source: String): List<RagChunk> {
        val chunks = mutableListOf<RagChunk>()
        var start = 0
        var index = 0

        while (start < text.length) {
            var end = minOf(start + chunkSize, text.length)

            // Try to split at paragraph boundary
            if (end < text.length) {
                val paragraphEnd = text.lastIndexOf("\n\n", end)
                if (paragraphEnd > start + chunkSize / 2) {
                    end = paragraphEnd
                }
            }

            val content = text.substring(start, end).trim()
            if (content.isNotBlank()) {
                chunks.add(
                    RagChunk(
                        chunkId = UUID.randomUUID().toString(),
                        source = source,
                        title = "Чанк ${index + 1}",
                        section = "chunk_${index + 1}",
                        content = content,
                        strategy = strategyName,
                        charCount = content.length
                    )
                )
                index++
            }

            start = if (end < text.length) maxOf(end - overlap, start + 1) else text.length
        }

        return chunks
    }
}
