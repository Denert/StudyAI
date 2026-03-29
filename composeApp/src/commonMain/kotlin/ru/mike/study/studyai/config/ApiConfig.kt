package ru.mike.study.studyai.config

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties

object ApiConfig {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 15_000 }
    }

    @Serializable
    private data class OllamaTagsResponse(val models: List<OllamaModel> = emptyList())
    @Serializable
    private data class OllamaModel(val name: String)

    @Serializable
    private data class OpenAiModelsResponse(val data: List<OpenAiModel> = emptyList())
    @Serializable
    private data class OpenAiModel(val id: String)

    suspend fun fetchOllamaModels(baseUrl: String): List<String> = try {
        val response = httpClient.get("$baseUrl/api/tags")
        val body = response.bodyAsText()
        json.decodeFromString<OllamaTagsResponse>(body).models.map { it.name }
    } catch (e: Exception) {
        println("Failed to fetch Ollama models: ${e.message}")
        emptyList()
    }

    suspend fun fetchLocalModels(baseUrl: String, apiKey: String): List<String> = try {
        val response = httpClient.get("$baseUrl/v1/models") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        val body = response.bodyAsText()
        json.decodeFromString<OpenAiModelsResponse>(body).data.map { it.id }
    } catch (e: Exception) {
        println("Failed to fetch local models: ${e.message}")
        emptyList()
    }

    suspend fun checkLocalHealth(baseUrl: String, apiKey: String): Boolean = try {
        val response = httpClient.get("$baseUrl/health") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        response.status.isSuccess()
    } catch (e: Exception) {
        println("Local health check failed: ${e.message}")
        false
    }

    private val localProperties: Properties by lazy {
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
        props
    }

    val apiKey: String by lazy {
        val key = localProperties.getProperty("OPENAI_API_KEY") ?: ""
        println("API Key loaded: ${if (key.isNotEmpty()) "${key.take(10)}..." else "EMPTY"}")
        key
    }

    val localApiKey: String by lazy {
        val key = localProperties.getProperty("LOCAL_API_KEY") ?: ""
        println("Local API Key loaded: ${if (key.isNotEmpty()) "${key.take(8)}..." else "EMPTY"}")
        key
    }
}