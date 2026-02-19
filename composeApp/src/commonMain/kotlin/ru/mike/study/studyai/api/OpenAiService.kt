package ru.mike.study.studyai.api

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import ru.mike.study.studyai.data.OpenAiMessage
import ru.mike.study.studyai.data.OpenAiRequest
import ru.mike.study.studyai.data.OpenAiResponse

class OpenAiService(private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    private val conversationHistory = mutableListOf<OpenAiMessage>()

    suspend fun sendMessage(userMessage: String, temperature: Float = 1.0f): Result<String> {
        return try {
            conversationHistory.add(OpenAiMessage(role = "user", content = userMessage))

            val request = OpenAiRequest(
                messages = conversationHistory.toList(),
                temperature = temperature
            )

            println("OpenAI Request: ${json.encodeToString(OpenAiRequest.serializer(), request)}")

            val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseText = httpResponse.bodyAsText()
            println("OpenAI Response: $responseText")

            val response = json.decodeFromString<OpenAiResponse>(responseText)

            if (response.error != null) {
                conversationHistory.removeLast()
                return Result.failure(Exception("API Error: ${response.error.message}"))
            }

            val assistantMessage = response.choices?.firstOrNull()?.message?.content
                ?: "No response received"

            conversationHistory.add(OpenAiMessage(role = "assistant", content = assistantMessage))

            Result.success(assistantMessage)
        } catch (e: Exception) {
            println("OpenAI Error: ${e.message}")
            e.printStackTrace()
            if (conversationHistory.isNotEmpty()) {
                conversationHistory.removeLast()
            }
            Result.failure(e)
        }
    }

    fun clearHistory() {
        conversationHistory.clear()
    }
}