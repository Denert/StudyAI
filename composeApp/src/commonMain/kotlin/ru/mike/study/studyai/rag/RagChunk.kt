package ru.mike.study.studyai.rag

import kotlinx.serialization.Serializable

@Serializable
data class RagChunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val content: String,
    val strategy: String,
    val charCount: Int,
    val embedding: List<Float> = emptyList()
)
