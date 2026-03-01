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
import ru.mike.study.studyai.data.ContextStrategy
import ru.mike.study.studyai.data.FactData
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

data class FactsResult(
    val facts: List<FactData>,
    val tokenCount: Int
)

class OpenAiService(private val apiKey: String) {

    companion object {
        const val DEFAULT_SLIDING_WINDOW_SIZE = 10
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

    // All messages in conversation
    private val allMessages = mutableListOf<OpenAiMessage>()

    // Summaries (for SUMMARY strategy)
    private val summaries = mutableListOf<String>()

    // Facts (for STICKY_FACTS strategy)
    private val facts = mutableListOf<FactData>()

    // Current strategy settings
    private var currentStrategy: ContextStrategy = ContextStrategy.NONE
    private var slidingWindowSize: Int = DEFAULT_SLIDING_WINDOW_SIZE

    fun setStrategy(strategy: ContextStrategy) {
        currentStrategy = strategy
        println("★ Strategy changed to: $strategy")
    }

    fun setSlidingWindowSize(size: Int) {
        slidingWindowSize = size
        println("★ Sliding window size set to: $size")
    }

    fun setFacts(newFacts: List<FactData>) {
        facts.clear()
        facts.addAll(newFacts)
    }

    fun getFacts(): List<FactData> = facts.toList()

    suspend fun sendMessage(
        userMessage: String,
        temperature: Float = 1.0f,
        model: String? = null
    ): Result<ChatResult> {
        return try {
            allMessages.add(OpenAiMessage(role = "user", content = userMessage))

            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"

            // Build context based on current strategy
            val contextMessages = buildContextMessages()

            val request = OpenAiRequest(
                model = requestModel,
                messages = contextMessages,
                temperature = temperature
            )

            println("═══════════════════════════════════════════════════════════")
            println("OpenAI Request [Strategy: $currentStrategy] (${contextMessages.size} messages):")
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
                allMessages.removeAt(allMessages.lastIndex)
                return Result.failure(Exception("API Error: ${response.error.message}"))
            }

            val assistantMessage = response.choices?.firstOrNull()?.message?.content
                ?: "No response received"

            allMessages.add(OpenAiMessage(role = "assistant", content = assistantMessage))

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
            if (allMessages.isNotEmpty()) {
                allMessages.removeAt(allMessages.lastIndex)
            }
            Result.failure(e)
        }
    }

    /**
     * Build context messages based on current strategy
     */
    private fun buildContextMessages(): List<OpenAiMessage> {
        return when (currentStrategy) {
            ContextStrategy.NONE -> buildNoStrategyContext()
            ContextStrategy.SLIDING_WINDOW -> buildSlidingWindowContext()
            ContextStrategy.SUMMARY -> buildSummaryContext()
            ContextStrategy.STICKY_FACTS -> buildStickyFactsContext()
            ContextStrategy.BRANCHING -> buildNoStrategyContext() // Branching uses full context
        }
    }

    /**
     * NONE strategy: Send all messages as-is
     */
    private fun buildNoStrategyContext(): List<OpenAiMessage> {
        println("★ Strategy NONE: sending all ${allMessages.size} messages")
        return allMessages.toList()
    }

    /**
     * SLIDING_WINDOW strategy: Keep only last N messages
     */
    private fun buildSlidingWindowContext(): List<OpenAiMessage> {
        val messages = if (allMessages.size > slidingWindowSize) {
            allMessages.takeLast(slidingWindowSize)
        } else {
            allMessages.toList()
        }
        println("★ Strategy SLIDING_WINDOW: sending last ${messages.size} of ${allMessages.size} messages (window=$slidingWindowSize)")
        return messages
    }

    /**
     * SUMMARY strategy: Summaries + recent messages
     */
    private fun buildSummaryContext(): List<OpenAiMessage> {
        val contextMessages = mutableListOf<OpenAiMessage>()

        // Add summaries as system context
        if (summaries.isNotEmpty()) {
            val summaryContext = summaries.mapIndexed { index, summary ->
                "[Summary ${index + 1}]: $summary"
            }.joinToString("\n\n")

            contextMessages.add(OpenAiMessage(
                role = "system",
                content = "Previous conversation context:\n$summaryContext"
            ))
        }

        // Add recent messages (last N that weren't summarized)
        val recentCount = minOf(allMessages.size, slidingWindowSize)
        contextMessages.addAll(allMessages.takeLast(recentCount))

        println("★ Strategy SUMMARY: ${summaries.size} summaries + ${recentCount} recent messages = ${contextMessages.size} total")
        return contextMessages
    }

    /**
     * STICKY_FACTS strategy: Facts + last N messages
     */
    private fun buildStickyFactsContext(): List<OpenAiMessage> {
        val contextMessages = mutableListOf<OpenAiMessage>()

        // Add facts as system context
        if (facts.isNotEmpty()) {
            val factsContext = facts.joinToString("\n") { fact ->
                "- ${fact.key}: ${fact.value}"
            }

            contextMessages.add(OpenAiMessage(
                role = "system",
                content = "Important facts from conversation:\n$factsContext"
            ))

            println("")
            println("┌──────────────────────────────────────────────────────────┐")
            println("│  📌 FACTS INCLUDED IN CONTEXT:                          │")
            println("├──────────────────────────────────────────────────────────┤")
            facts.forEach { fact ->
                println("│  • ${fact.key}: ${fact.value}")
            }
            println("└──────────────────────────────────────────────────────────┘")
        }

        // Add last N messages
        val recentMessages = if (allMessages.size > slidingWindowSize) {
            allMessages.takeLast(slidingWindowSize)
        } else {
            allMessages.toList()
        }
        contextMessages.addAll(recentMessages)

        println("★ Strategy STICKY_FACTS: ${facts.size} facts + ${recentMessages.size} recent messages = ${contextMessages.size} total")
        return contextMessages
    }

    /**
     * Check if summarization is needed and perform it (for SUMMARY strategy)
     */
    suspend fun checkAndSummarize(model: String? = null): SummaryResult? {
        if (currentStrategy != ContextStrategy.SUMMARY) {
            return null
        }

        // Calculate how many messages are not yet summarized
        val summarizedCount = summaries.size * BATCH_SIZE_FOR_SUMMARY
        val unsummarizedCount = allMessages.size - summarizedCount

        // Need to summarize if we have more than slidingWindowSize + BATCH_SIZE_FOR_SUMMARY unsummarized
        if (unsummarizedCount <= slidingWindowSize + BATCH_SIZE_FOR_SUMMARY) {
            return null
        }

        // Take messages to summarize (from after last summary to batch size)
        val startIndex = summarizedCount
        val endIndex = startIndex + BATCH_SIZE_FOR_SUMMARY
        val messagesToSummarize = allMessages.subList(startIndex, endIndex)

        val summaryResult = generateSummary(messagesToSummarize, model)

        if (summaryResult != null) {
            summaries.add(summaryResult.content)
            println("★ Created summary #${summaries.size} for messages $startIndex-${endIndex - 1}")
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
                temperature = 0.3f
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
     * Extract facts from last user message (for STICKY_FACTS strategy)
     * Appends new facts to existing ones
     */
    suspend fun extractFacts(model: String? = null): FactsResult? {
        // Get last user message
        val lastUserMessage = allMessages.lastOrNull { it.role == "user" } ?: return null

        return try {
            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"

            val existingFactsText = if (facts.isNotEmpty()) {
                facts.joinToString("\n") { "- ${it.key}: ${it.value}" }
            } else {
                "Пока нет"
            }

            val factsPrompt = """Проанализируй сообщение пользователя и извлеки новые факты или обнови существующие.

Существующие факты:
$existingFactsText

Сообщение пользователя:
${lastUserMessage.content}

Если в сообщении есть новые важные факты - добавь их.
Если факт изменился - верни обновлённую версию.
Если новых фактов нет - верни только существующие факты без изменений.

Формат ответа (каждый факт на новой строке):
КЛЮЧ: значение

Категории фактов:
- Имя: как зовут пользователя
- Цель: что хочет пользователь
- Контекст: важный контекст задачи
- Ограничения: какие есть ограничения
- Предпочтения: что предпочитает пользователь
- Технологии: какие технологии используются

Только важные факты, кратко."""

            val request = OpenAiRequest(
                model = requestModel,
                messages = listOf(OpenAiMessage(role = "user", content = factsPrompt)),
                temperature = 0.3f
            )

            println("")
            println("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
            println("┃  📋 FACTS EXTRACTION REQUEST                              ┃")
            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            println("┃  Model: $requestModel")
            println("┃  Existing facts: ${facts.size}")
            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            println("┃  USER MESSAGE:")
            println("┃  ──────────────")
            lastUserMessage.content.lines().forEach { line ->
                println("┃  $line")
            }
            println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
            println("")

            val startTime = System.currentTimeMillis()

            val httpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseTimeMs = System.currentTimeMillis() - startTime
            val responseText = httpResponse.bodyAsText()
            val response = json.decodeFromString<OpenAiResponse>(responseText)

            println("")
            println("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
            println("┃  📋 FACTS EXTRACTION RESPONSE                             ┃")
            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            println("┃  Response time: ${responseTimeMs}ms")
            println("┃  Tokens: ${response.usage?.totalTokens ?: 0} (prompt: ${response.usage?.promptTokens ?: 0}, completion: ${response.usage?.completionTokens ?: 0})")

            if (response.error != null) {
                println("┃  ❌ ERROR: ${response.error.message}")
                println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
                return null
            }

            val factsContent = response.choices?.firstOrNull()?.message?.content ?: return null
            val tokenCount = response.usage?.totalTokens ?: 0

            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            println("┃  LLM RESPONSE:")
            println("┃  ─────────────")
            factsContent.lines().forEach { line ->
                println("┃  $line")
            }

            // Parse facts from response
            val newFacts = parseFacts(factsContent)

            // Update internal facts
            facts.clear()
            facts.addAll(newFacts)

            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            println("┃  ✅ PARSED FACTS (${newFacts.size}):")
            println("┃  ─────────────────")
            newFacts.forEachIndexed { index, fact ->
                println("┃  ${index + 1}. ${fact.key}: ${fact.value}")
            }
            println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
            println("")

            FactsResult(newFacts, tokenCount)
        } catch (e: Exception) {
            println("Facts extraction failed: ${e.message}")
            null
        }
    }

    /**
     * Parse facts from LLM response
     */
    private fun parseFacts(response: String): List<FactData> {
        val factsList = mutableListOf<FactData>()

        response.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && trimmed.contains(":")) {
                val colonIndex = trimmed.indexOf(":")
                val key = trimmed.substring(0, colonIndex).trim().trimStart('-', '•', '*', ' ')
                val value = trimmed.substring(colonIndex + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    factsList.add(FactData(key = key, value = value))
                }
            }
        }

        return factsList
    }

    fun clearHistory() {
        allMessages.clear()
        summaries.clear()
        facts.clear()
    }

    fun addToHistory(role: String, content: String) {
        allMessages.add(OpenAiMessage(role = role, content = content))
    }

    fun addSummary(summary: String) {
        summaries.add(summary)
    }

    fun getAllMessagesCount(): Int = allMessages.size

    fun getSummariesCount(): Int = summaries.size

    fun getFactsCount(): Int = facts.size
}
