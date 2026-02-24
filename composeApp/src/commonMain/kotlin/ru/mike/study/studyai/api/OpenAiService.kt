package ru.mike.study.studyai.api

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import ru.mike.study.studyai.data.MessageMetadata
import ru.mike.study.studyai.data.OpenAiMessage
import ru.mike.study.studyai.data.OpenAiRequest
import ru.mike.study.studyai.data.OpenAiResponse
import ru.mike.study.studyai.data.PricingCalculator

data class ChatResult(
    val content: String,
    val metadata: MessageMetadata
)

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

    suspend fun sendMessage(userMessage: String, temperature: Float = 1.0f, model: String? = null): Result<ChatResult> {
        return try {
            conversationHistory.add(OpenAiMessage(role = "user", content = userMessage))

            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
            val request = OpenAiRequest(
                model = requestModel,
                messages = conversationHistory.toList(),
                temperature = temperature
            )

            println("OpenAI Request: ${json.encodeToString(OpenAiRequest.serializer(), request)}")

            val startTime = System.currentTimeMillis()

            val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseTimeMs = System.currentTimeMillis() - startTime

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

            val modelName = response.model ?: requestModel
            val promptTokens = response.usage?.promptTokens ?: 0
            val completionTokens = response.usage?.completionTokens ?: 0

            val metadata = MessageMetadata(
                model = modelName,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = response.usage?.totalTokens ?: 0,
                responseTimeMs = responseTimeMs,
                temperature = temperature,
                costRub = PricingCalculator.calculateCostRub(modelName, promptTokens, completionTokens)
            )

            Result.success(ChatResult(assistantMessage, metadata))
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

    fun addToHistory(role: String, content: String) {
        conversationHistory.add(OpenAiMessage(role = role, content = content))
    }
}