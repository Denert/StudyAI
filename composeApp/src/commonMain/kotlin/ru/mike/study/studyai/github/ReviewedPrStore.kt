package ru.mike.study.studyai.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class ReviewedPrStore {

    private val storeFile = File(System.getProperty("user.home") + "/.studyai/reviewed-prs.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class ReviewedPr(val headSha: String, val reviewedAt: Long)

    @Serializable
    private data class Store(val entries: Map<String, ReviewedPr> = emptyMap())

    private fun load(): Store = try {
        if (storeFile.exists()) json.decodeFromString<Store>(storeFile.readText())
        else Store()
    } catch (e: Exception) {
        Store()
    }

    private fun save(store: Store) {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(json.encodeToString(Store.serializer(), store))
    }

    fun isAlreadyReviewed(repo: String, prNumber: Int, headSha: String): Boolean {
        val key = "$repo:$prNumber"
        return load().entries[key]?.headSha == headSha
    }

    fun markReviewed(repo: String, prNumber: Int, headSha: String) {
        val store = load()
        val key = "$repo:$prNumber"
        save(store.copy(entries = store.entries + (key to ReviewedPr(headSha, System.currentTimeMillis()))))
    }

    fun clearReview(repo: String, prNumber: Int) {
        val store = load()
        val key = "$repo:$prNumber"
        save(store.copy(entries = store.entries - key))
    }
}
