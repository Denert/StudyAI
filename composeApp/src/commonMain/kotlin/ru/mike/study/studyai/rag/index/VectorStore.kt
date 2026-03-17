package ru.mike.study.studyai.rag.index

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.mike.study.studyai.rag.RagChunk
import java.io.File
import kotlin.math.sqrt

class VectorStore(
    baseDir: String = System.getProperty("user.home") + "/.studyai/rag-index"
) {
    private val dir = File(baseDir).also { it.mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun save(chunks: List<RagChunk>, strategy: String) {
        File(dir, "${strategy}_index.json").writeText(json.encodeToString(chunks))
    }

    fun load(strategy: String): List<RagChunk> {
        val file = File(dir, "${strategy}_index.json")
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun search(queryEmbedding: List<Float>, strategy: String, topK: Int = 5): List<Pair<RagChunk, Float>> {
        return load(strategy)
            .filter { it.embedding.isNotEmpty() }
            .map { it to cosineSimilarity(queryEmbedding, it.embedding) }
            .sortedByDescending { it.second }
            .take(topK)
    }

    fun hasIndex(strategy: String): Boolean = File(dir, "${strategy}_index.json").exists()

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
