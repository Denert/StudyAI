package ru.mike.study.studyai.config

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties

object ApiConfig {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient {
        install(ContentNegotiation) { json(json) }
    }

    @Serializable
    private data class OllamaTagsResponse(val models: List<OllamaModel> = emptyList())
    @Serializable
    private data class OllamaModel(val name: String)

    suspend fun fetchOllamaModels(baseUrl: String): List<String> = try {
        val response = httpClient.get("$baseUrl/api/tags")
        val body = response.bodyAsText()
        json.decodeFromString<OllamaTagsResponse>(body).models.map { it.name }
    } catch (e: Exception) {
        println("Failed to fetch Ollama models: ${e.message}")
        emptyList()
    }

    val apiKey: String by lazy {
        val props = Properties()
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "../../local.properties"
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                println("Found local.properties at: ${file.absolutePath}")
                file.inputStream().use { props.load(it) }
                break
            }
        }

        val key = props.getProperty("OPENAI_API_KEY") ?: ""
        println("API Key loaded: ${if (key.isNotEmpty()) "${key.take(10)}..." else "EMPTY"}")
        key
    }
}