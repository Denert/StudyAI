package ru.mike.study.studyai.rag.chunking

import ru.mike.study.studyai.rag.RagChunk

interface Chunker {
    val strategyName: String
    fun chunk(text: String, source: String): List<RagChunk>
}
