package ru.mike.study.studyai.api

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import ru.mike.study.studyai.data.MessageMetadata
import ru.mike.study.studyai.data.OpenAiMessage
import ru.mike.study.studyai.data.OpenAiRequest
import ru.mike.study.studyai.data.OpenAiResponse
import ru.mike.study.studyai.data.PricingCalculator

data class ChatResult(
    val content: String,
    val metadata: MessageMetadata
)

data class SummaryResult(
    val content: String,
    val tokenCount: Int
)

class OpenAiService(private val apiKey: String) {

    companion object {
        const val RECENT_MESSAGES_COUNT = 10
        const val BATCH_SIZE_FOR_SUMMARY = 10
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonPretty = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
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

    // Full conversation history (recent messages only)
    private val recentMessages = mutableListOf<OpenAiMessage>()

    // Summaries of older messages
    private val summaries = mutableListOf<String>()

    suspend fun sendMessage(
        userMessage: String,
        temperature: Float = 1.0f,
        model: String? = null
    ): Result<ChatResult> {
        return try {
            recentMessages.add(OpenAiMessage(role = "user", content = userMessage))

            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"

            // Build context: summaries + recent messages
            val contextMessages = buildContextMessages()

            val request = OpenAiRequest(
                model = requestModel,
                messages = contextMessages,
                temperature = temperature
            )

            println("═══════════════════════════════════════════════════════════")
            println("OpenAI Request (${contextMessages.size} messages):")
            println(jsonPretty.encodeToString(OpenAiRequest.serializer(), request))
            println("═══════════════════════════════════════════════════════════")

            val startTime = System.currentTimeMillis()

            val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseTimeMs = System.currentTimeMillis() - startTime

            val responseText = httpResponse.bodyAsText()
            println("───────────────────────────────────────────────────────────")
            println("OpenAI Response:")
            try {
                val responseJson = json.parseToJsonElement(responseText)
                println(jsonPretty.encodeToString(JsonElement.serializer(), responseJson))
            } catch (_: Exception) {
                println(responseText)
            }
            println("───────────────────────────────────────────────────────────")

            val response = json.decodeFromString<OpenAiResponse>(responseText)

            if (response.error != null) {
                recentMessages.removeAt(recentMessages.lastIndex)
                return Result.failure(Exception("API Error: ${response.error.message}"))
            }

            val assistantMessage = response.choices?.firstOrNull()?.message?.content
                ?: "No response received"

            recentMessages.add(OpenAiMessage(role = "assistant", content = assistantMessage))

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
            if (recentMessages.isNotEmpty()) {
                recentMessages.removeAt(recentMessages.lastIndex)
            }
            Result.failure(e)
        }
    }

    /**
     * Check if summarization is needed and perform it
     * Returns SummaryResult if summary was created, null otherwise
     */
    suspend fun checkAndSummarize(model: String? = null): SummaryResult? {
        // Need to summarize if we have more than RECENT_MESSAGES_COUNT + BATCH_SIZE_FOR_SUMMARY
        if (recentMessages.size <= RECENT_MESSAGES_COUNT + BATCH_SIZE_FOR_SUMMARY) {
            return null
        }

        // Take first BATCH_SIZE_FOR_SUMMARY messages to summarize
        val messagesToSummarize = recentMessages.take(BATCH_SIZE_FOR_SUMMARY)

        val summaryResult = generateSummary(messagesToSummarize, model)

        if (summaryResult != null) {
            // Add to summaries list
            summaries.add(summaryResult.content)

            // Remove summarized messages from recent
            repeat(BATCH_SIZE_FOR_SUMMARY) {
                if (recentMessages.isNotEmpty()) {
                    recentMessages.removeAt(0)
                }
            }

            println("Created summary for $BATCH_SIZE_FOR_SUMMARY messages. Summaries count: ${summaries.size}, Recent messages: ${recentMessages.size}")
        }

        return summaryResult
    }

    /**
     * Generate summary for a batch of messages
     */
    private suspend fun generateSummary(
        messages: List<OpenAiMessage>,
        model: String? = null
    ): SummaryResult? {
        return try {
            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"

            val conversationText = messages.joinToString("\n") { msg ->
                "${if (msg.role == "user") "User" else "Assistant"}: ${msg.content}"
            }

            val summaryPrompt = """Сделай краткое summary следующего диалога.
Сохрани ключевые факты, решения и контекст.
Пиши кратко, но информативно (2-4 предложения).

Диалог:
$conversationText

Summary:"""

            val request = OpenAiRequest(
                model = requestModel,
                messages = listOf(OpenAiMessage(role = "user", content = summaryPrompt)),
                temperature = 0.3f // Lower temperature for more consistent summaries
            )

            println("▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓")
            println("SUMMARY Request (${messages.size} messages to summarize):")
            println(jsonPretty.encodeToString(OpenAiRequest.serializer(), request))
            println("▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓")

            val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseText = httpResponse.bodyAsText()

            println("░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░")
            println("SUMMARY Response:")
            try {
                val responseJson = json.parseToJsonElement(responseText)
                println(jsonPretty.encodeToString(JsonElement.serializer(), responseJson))
            } catch (_: Exception) {
                println(responseText)
            }
            println("░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░")

            val response = json.decodeFromString<OpenAiResponse>(responseText)

            if (response.error != null) {
                println("Summary generation error: ${response.error.message}")
                return null
            }

            val summaryContent = response.choices?.firstOrNull()?.message?.content ?: return null
            val tokenCount = response.usage?.totalTokens ?: 0

            println("✓ Summary created: $summaryContent")

            SummaryResult(summaryContent, tokenCount)
        } catch (e: Exception) {
            println("Summary generation failed: ${e.message}")
            null
        }
    }

    /**
     * Build context messages for API request
     */
    private fun buildContextMessages(): List<OpenAiMessage> {
        val contextMessages = mutableListOf<OpenAiMessage>()

        // Add summaries as system context if any exist
        if (summaries.isNotEmpty()) {
            val summaryContext = summaries.mapIndexed { index, summary ->
                "[Summary ${index + 1}]: $summary"
            }.joinToString("\n\n")

            contextMessages.add(OpenAiMessage(
                role = "system",
                content = "Previous conversation context:\n$summaryContext"
            ))

            println("★★★ Including ${summaries.size} summaries in request ★★★")
        }

        // Add recent messages
        contextMessages.addAll(recentMessages)

        println("★★★ Context: ${summaries.size} summaries + ${recentMessages.size} recent messages = ${contextMessages.size} total ★★★")

        return contextMessages
    }

    fun clearHistory() {
        recentMessages.clear()
        summaries.clear()
    }

    fun addToHistory(role: String, content: String) {
        recentMessages.add(OpenAiMessage(role = role, content = content))
    }

    fun addSummary(summary: String) {
        summaries.add(summary)
    }

    fun getRecentMessagesCount(): Int = recentMessages.size

    fun getSummariesCount(): Int = summaries.size
}