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
import ru.mike.study.studyai.data.TaskStateData
import ru.mike.study.studyai.data.OpenAiMessage
import ru.mike.study.studyai.data.OpenAiRequest
import ru.mike.study.studyai.data.OpenAiResponse
import ru.mike.study.studyai.data.OpenAiTool
import ru.mike.study.studyai.data.PricingCalculator
import ru.mike.study.studyai.data.ToolCall
import ru.mike.study.studyai.memory.MemoryService

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

data class MemoryExtractionResult(
    val success: Boolean,
    val phase: String? = null,         // PLANNING, EXECUTION, VALIDATION, DONE
    val phaseCompleted: Boolean = false,
    val invariants: List<String> = emptyList()
)

class OpenAiService(
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com"
) {
    private val chatEndpoint = "$baseUrl/v1/chat/completions"

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
    private var taskState: TaskStateData = TaskStateData()

    // Current strategy settings
    private var currentStrategy: ContextStrategy = ContextStrategy.MEMORY_LAYERS
    private var slidingWindowSize: Int = DEFAULT_SLIDING_WINDOW_SIZE

    // Memory layers (for MEMORY_LAYERS strategy)
    private val memoryService = MemoryService()
    private var currentChatId: String = ""
    private var memorySystemPromptEnabled: Boolean = true
    private var memoryInvariantsEnabled: Boolean = true
    private var memoryProfileEnabled: Boolean = true

    fun setChatId(chatId: String) {
        currentChatId = chatId
    }

    fun getMemoryService(): MemoryService = memoryService

    fun setStrategy(strategy: ContextStrategy) {
        currentStrategy = strategy
        println("★ Strategy changed to: $strategy")
    }

    fun setSlidingWindowSize(size: Int) {
        slidingWindowSize = size
        println("★ Sliding window size set to: $size")
    }

    fun setMemoryConfig(systemPrompt: Boolean, invariants: Boolean, profile: Boolean) {
        memorySystemPromptEnabled = systemPrompt
        memoryInvariantsEnabled = invariants
        memoryProfileEnabled = profile
    }

    fun setFacts(newFacts: List<FactData>) {
        facts.clear()
        facts.addAll(newFacts)
    }

    fun setTaskState(state: TaskStateData) {
        taskState = state
    }

    private fun buildTaskStateSystemMessage(): OpenAiMessage? {
        if (taskState.isEmpty) return null
        val sb = StringBuilder("СОСТОЯНИЕ ЗАДАЧИ:\n")
        if (taskState.goal.isNotBlank()) {
            sb.append("Цель: ${taskState.goal}\n")
        }
        if (taskState.clarifications.isNotEmpty()) {
            sb.append("\nЧто уточнено:\n")
            taskState.clarifications.forEach { sb.append("- $it\n") }
        }
        if (taskState.constraints.isNotEmpty()) {
            sb.append("\nОграничения и термины:\n")
            taskState.constraints.forEach { sb.append("- $it\n") }
        }
        return OpenAiMessage(role = "system", content = sb.toString().trimEnd())
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

            val httpResponse = client.post(chatEndpoint) {
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
            ContextStrategy.MEMORY_LAYERS -> buildMemoryLayersContext()
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
     * MEMORY_LAYERS strategy: 3-layer memory system
     * - Long-term: User profile, preferences, knowledge (global)
     * - Working: Sub-tasks/topics within dialog (per-chat)
     * - Short-term: FULL dialog (all messages)
     */
    private fun buildMemoryLayersContext(): List<OpenAiMessage> {
        val contextMessages = mutableListOf<OpenAiMessage>()

        // Build memory context from long-term and working memory (includes system instructions)
        val memoryContext = memoryService.buildMemoryContext(
            currentChatId,
            systemPromptEnabled = memorySystemPromptEnabled,
            invariantsEnabled = memoryInvariantsEnabled,
            profileMemoryEnabled = memoryProfileEnabled
        )

        if (memoryContext.isNotBlank()) {
            contextMessages.add(OpenAiMessage(
                role = "system",
                content = memoryContext
            ))

            println("")
            println("┌──────────────────────────────────────────────────────────┐")
            println("│  🧠 MEMORY LAYERS INCLUDED IN CONTEXT:                  │")
            println("├──────────────────────────────────────────────────────────┤")
            memoryContext.lines().take(15).forEach { line ->
                println("│  $line")
            }
            if (memoryContext.lines().size > 15) {
                println("│  ... (${memoryContext.lines().size - 15} more lines)")
            }
            println("└──────────────────────────────────────────────────────────┘")
        }

        // SHORT-TERM MEMORY: Add ALL messages (full dialog)
        contextMessages.addAll(allMessages.toList())

        memoryService.logMemoryState(currentChatId)
        println("★ Strategy MEMORY_LAYERS: memory context + ${allMessages.size} messages (full dialog) = ${contextMessages.size} total")
        return contextMessages
    }

    /**
     * Extract and update memory layers from conversation
     * Called after each assistant response
     * Returns phase information for FSM
     */
    suspend fun extractMemoryUpdates(model: String? = null): MemoryExtractionResult {
        if (currentChatId.isEmpty()) {
            println("⚠ Cannot extract memory: no chat ID set")
            return MemoryExtractionResult(success = false)
        }

        val lastUserMessage = allMessages.lastOrNull { it.role == "user" }?.content
            ?: return MemoryExtractionResult(success = false)
        val lastAssistantMessage = allMessages.lastOrNull { it.role == "assistant" }?.content
            ?: return MemoryExtractionResult(success = false)

        return try {
            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"

            // Get current memory state
            val profile = memoryService.readActiveProfile()
            val working = memoryService.readWorkingMemory(currentChatId)

            val currentLongTermStr = buildString {
                appendLine("Profile: ${profile.name}")
                appendLine("Data: ${profile.data.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                appendLine("Preferences: ${profile.preferences.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                appendLine("Knowledge: ${profile.knowledge.joinToString("; ")}")
                appendLine("Decisions: ${profile.decisions.joinToString("; ")}")
            }

            val currentWorkingStr = buildString {
                if (working.currentTask != null) {
                    appendLine("Current task: ${working.currentTask?.name}")
                    appendLine("Task context: ${working.currentTask?.context?.joinToString("; ") ?: "none"}")
                } else {
                    appendLine("No active task")
                }
                appendLine("Active tasks: ${working.activeTasks.joinToString(", ") { it.name }}")
                appendLine("Completed tasks: ${working.completedTasks.joinToString(", ") { it.name }}")
            }

            val extractionPrompt = """Проанализируй последний обмен сообщениями и определи, что сохранить в память.

ТЕКУЩИЙ ПРОФИЛЬ (глобальная память):
$currentLongTermStr

ТЕКУЩАЯ РАБОЧАЯ ПАМЯТЬ (подзадачи диалога):
$currentWorkingStr

ПОСЛЕДНЕЕ СООБЩЕНИЕ ПОЛЬЗОВАТЕЛЯ:
$lastUserMessage

ОТВЕТ АССИСТЕНТА:
$lastAssistantMessage

Определи:

1. ПРОФИЛЬ (сохраняется навсегда):
   - Данные: имя, роль, профессия пользователя
   - Предпочтения: язык, стиль общения
   - Знания: важные факты о проекте, технологиях
   - Решения: важные решения, принятые в диалогах

2. РАБОЧАЯ ПАМЯТЬ (подзадачи в диалоге):
   - Это НОВАЯ подзадача или продолжение текущей?
   - Название подзадачи (кратко, 3-5 слов)
   - Контекст подзадачи (ключевые решения, артефакты)
   - Текущая подзадача ЗАВЕРШЕНА?

3. ФАЗА ЗАДАЧИ (конечный автомат):
   - PLANNING: анализ, планирование, исследование
   - EXECUTION: реализация, написание кода, выполнение
   - VALIDATION: проверка, тестирование, ревью
   - DONE: задача завершена

   Определи текущую фазу и завершена ли она.

4. ИНВАРИАНТЫ (правила, которые ВСЕГДА должны соблюдаться):
   - Если пользователь явно указал правило/ограничение - запиши его
   - Примеры: "всегда использовать TypeScript", "не добавлять новые зависимости", "код должен быть на русском"
   - Записывай только явные указания пользователя

Ответь СТРОГО в формате:

PROFILE_DATA:
ключ: значение

PROFILE_PREFERENCES:
ключ: значение

PROFILE_KNOWLEDGE:
- факт

PROFILE_DECISIONS:
- решение

NEW_TASK:
yes/no

TASK_NAME:
название подзадачи

TASK_CONTEXT:
- ключевое решение или факт

TASK_COMPLETED:
yes/no

TASK_PHASE:
PLANNING/EXECUTION/VALIDATION/DONE

PHASE_COMPLETED:
yes/no

INVARIANTS:
- правило (если есть новые)"""

            val request = OpenAiRequest(
                model = requestModel,
                messages = listOf(OpenAiMessage(role = "user", content = extractionPrompt)),
                temperature = 0.2f
            )

            println("")
            println("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
            println("┃  🧠 MEMORY EXTRACTION REQUEST                             ┃")
            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            println("┃  Model: $requestModel")
            println("┃  Current task: ${working.currentTask?.name ?: "none"}")
            println("┃  User message: ${lastUserMessage.take(50)}...")
            println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")

            val startTime = System.currentTimeMillis()

            val httpResponse = client.post(chatEndpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseTimeMs = System.currentTimeMillis() - startTime
            val responseText = httpResponse.bodyAsText()
            val response = json.decodeFromString<OpenAiResponse>(responseText)

            if (response.error != null) {
                println("┃  ❌ Memory extraction error: ${response.error.message}")
                return MemoryExtractionResult(success = false)
            }

            val content = response.choices?.firstOrNull()?.message?.content
                ?: return MemoryExtractionResult(success = false)

            println("")
            println("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
            println("┃  🧠 MEMORY EXTRACTION RESPONSE                            ┃")
            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            println("┃  Response time: ${responseTimeMs}ms")
            println("┃  Tokens: ${response.usage?.totalTokens ?: 0}")
            println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
            content.lines().forEach { line ->
                println("┃  $line")
            }
            println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")

            // Parse and apply memory updates, get phase info
            val parseResult = parseAndApplyMemoryUpdates(content)

            MemoryExtractionResult(
                success = true,
                phase = parseResult.phase,
                phaseCompleted = parseResult.phaseCompleted,
                invariants = parseResult.invariants
            )
        } catch (e: Exception) {
            println("Memory extraction failed: ${e.message}")
            MemoryExtractionResult(success = false)
        }
    }

    /**
     * Parse result for memory updates
     */
    data class ParseResult(
        val phase: String?,
        val phaseCompleted: Boolean,
        val invariants: List<String>
    )

    /**
     * Parse LLM response and update memory layers
     */
    private fun parseAndApplyMemoryUpdates(content: String): ParseResult {
        var currentSection = ""
        val dataUpdates = mutableMapOf<String, String>()
        val preferenceUpdates = mutableMapOf<String, String>()
        val knowledgeUpdates = mutableListOf<String>()
        val decisionUpdates = mutableListOf<String>()
        val invariantUpdates = mutableListOf<String>()
        var isNewTask = false
        var taskName = ""
        val taskContext = mutableListOf<String>()
        var taskCompleted = false

        // Parse TASK_PHASE using regex for reliability
        val phaseRegex = Regex("""TASK_PHASE:\s*(PLANNING|EXECUTION|VALIDATION|DONE)""", RegexOption.IGNORE_CASE)
        val taskPhase = phaseRegex.find(content)?.groupValues?.get(1)?.uppercase()

        // Parse PHASE_COMPLETED using regex
        val phaseCompletedRegex = Regex("""PHASE_COMPLETED:\s*(yes|no)""", RegexOption.IGNORE_CASE)
        val phaseCompleted = phaseCompletedRegex.find(content)?.groupValues?.get(1)?.lowercase() == "yes"

        println("┃  🔍 Parsed TASK_PHASE: $taskPhase, PHASE_COMPLETED: $phaseCompleted")

        content.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("PROFILE_DATA:") -> currentSection = "data"
                trimmed.startsWith("PROFILE_PREFERENCES:") -> currentSection = "preferences"
                trimmed.startsWith("PROFILE_KNOWLEDGE:") -> currentSection = "knowledge"
                trimmed.startsWith("PROFILE_DECISIONS:") -> currentSection = "decisions"
                trimmed.startsWith("NEW_TASK:") -> {
                    isNewTask = trimmed.lowercase().contains("yes")
                    currentSection = ""
                }
                trimmed.startsWith("TASK_NAME:") -> {
                    currentSection = "taskname"
                    val value = trimmed.removePrefix("TASK_NAME:").trim()
                    if (value.isNotEmpty()) taskName = value
                }
                trimmed.startsWith("TASK_CONTEXT:") -> currentSection = "taskcontext"
                trimmed.startsWith("TASK_COMPLETED:") -> {
                    taskCompleted = trimmed.lowercase().contains("yes")
                    currentSection = ""
                }
                trimmed.startsWith("TASK_PHASE:") -> currentSection = ""
                trimmed.startsWith("PHASE_COMPLETED:") -> currentSection = ""
                trimmed.startsWith("INVARIANTS:") -> currentSection = "invariants"
                trimmed.contains(":") && currentSection in listOf("data", "preferences") -> {
                    val colonIdx = trimmed.indexOf(":")
                    val key = trimmed.substring(0, colonIdx).trim().trimStart('-', ' ')
                    val value = trimmed.substring(colonIdx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        when (currentSection) {
                            "data" -> dataUpdates[key] = value
                            "preferences" -> preferenceUpdates[key] = value
                        }
                    }
                }
                trimmed.startsWith("-") -> {
                    val item = trimmed.removePrefix("-").trim()
                    if (item.isNotEmpty()) {
                        when (currentSection) {
                            "knowledge" -> knowledgeUpdates.add(item)
                            "decisions" -> decisionUpdates.add(item)
                            "taskcontext" -> taskContext.add(item)
                            "invariants" -> invariantUpdates.add(item)
                        }
                    }
                }
                currentSection == "taskname" && trimmed.isNotEmpty() && !trimmed.startsWith("TASK") -> {
                    if (taskName.isEmpty()) taskName = trimmed
                }
            }
        }

        // Apply updates
        println("")
        println("┌──────────────────────────────────────────────────────────┐")
        println("│  💾 APPLYING MEMORY UPDATES:                            │")
        println("├──────────────────────────────────────────────────────────┤")

        // Profile updates
        if (dataUpdates.isNotEmpty() || preferenceUpdates.isNotEmpty() ||
            knowledgeUpdates.isNotEmpty() || decisionUpdates.isNotEmpty()) {
            println("│  👤 PROFILE:")
            dataUpdates.forEach { (k, v) -> println("│     Data: $k = $v") }
            preferenceUpdates.forEach { (k, v) -> println("│     Preference: $k = $v") }
            knowledgeUpdates.forEach { println("│     Knowledge: $it") }
            decisionUpdates.forEach { println("│     Decision: $it") }

            memoryService.updateActiveProfile(
                dataUpdates = dataUpdates,
                preferenceUpdates = preferenceUpdates,
                newKnowledge = knowledgeUpdates,
                newDecisions = decisionUpdates
            )
        }

        // Working memory (sub-tasks) updates
        val working = memoryService.readWorkingMemory(currentChatId)

        if (isNewTask && taskName.isNotEmpty()) {
            // Create new task
            println("│  📋 WORKING: New task created")
            println("│     ⚡ NEW TASK: $taskName")
            taskContext.forEach { println("│     Context: $it") }

            memoryService.addTask(currentChatId, taskName, taskContext)

        } else if (taskContext.isNotEmpty() && working.currentTask != null) {
            // Update current task context
            println("│  📋 WORKING: Updating current task")
            println("│     Task: ${working.currentTask?.name}")
            taskContext.forEach { println("│     + Context: $it") }

            memoryService.updateCurrentTask(currentChatId, taskContext)
        }

        if (taskCompleted && working.currentTask != null) {
            println("│  📋 WORKING: Task completed")
            println("│     ✓ ${working.currentTask?.name}")

            memoryService.completeTask(currentChatId)
        }

        // Phase info
        if (taskPhase != null) {
            println("│  🔄 PHASE: $taskPhase ${if (phaseCompleted) "✓ COMPLETED" else ""}")
        }

        // Invariants
        if (invariantUpdates.isNotEmpty()) {
            println("│  📌 INVARIANTS:")
            invariantUpdates.forEach { invariant ->
                println("│     + $invariant")
                memoryService.addAutoInvariant(invariant)
            }
        }

        println("└──────────────────────────────────────────────────────────┘")

        return ParseResult(taskPhase, phaseCompleted, invariantUpdates)
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

            val httpResponse = client.post(chatEndpoint) {
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
            lastUserMessage.content?.lines()?.forEach { line ->
                println("┃  $line")
            }
            println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
            println("")

            val startTime = System.currentTimeMillis()

            val httpResponse = client.post(chatEndpoint) {
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

    // ==================== TOOLS SUPPORT ====================

    /**
     * Send message with MCP tools support
     * Returns either a final response or a tool call request
     */
    suspend fun sendMessageWithTools(
        userMessage: String,
        tools: List<OpenAiTool>,
        temperature: Float = 1.0f,
        model: String? = null,
        ragSystemContext: String? = null
    ): Result<ChatResultWithTools> {
        return try {
            allMessages.add(OpenAiMessage(role = "user", content = userMessage))

            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
            val baseContextMessages = buildContextMessages()
            val taskStateMsg = buildTaskStateSystemMessage()
            val contextMessages = buildList {
                taskStateMsg?.let { add(it) }
                if (ragSystemContext != null) add(OpenAiMessage(role = "system", content = ragSystemContext))
                addAll(baseContextMessages)
            }

            val request = OpenAiRequest(
                model = requestModel,
                messages = contextMessages,
                temperature = temperature,
                tools = if (tools.isNotEmpty()) tools else null,
                toolChoice = if (tools.isNotEmpty()) "auto" else null
            )

            println("═══════════════════════════════════════════════════════════")
            println("OpenAI Request with Tools (${tools.size} tools):")
            println("  → Endpoint: $chatEndpoint")
            println("═══════════════════════════════════════════════════════════")

            val startTime = System.currentTimeMillis()

            val httpResponse = client.post(chatEndpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseTimeMs = System.currentTimeMillis() - startTime
            val responseText = httpResponse.bodyAsText()

            println("───────────────────────────────────────────────────────────")
            println("OpenAI Response (${responseTimeMs}ms):")
            println("───────────────────────────────────────────────────────────")

            val response = json.decodeFromString<OpenAiResponse>(responseText)

            if (response.error != null) {
                allMessages.removeAt(allMessages.lastIndex)
                return Result.failure(Exception("API Error: ${response.error.message}"))
            }

            val choice = response.choices?.firstOrNull()
            val message = choice?.message
            val finishReason = choice?.finishReason

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

            // Check if there are tool calls
            // Ollama may return finish_reason "stop" even for tool calls, so check toolCalls directly
            if (message?.toolCalls != null && message.toolCalls.isNotEmpty()) {
                println("🔧 Tool calls requested: ${message.toolCalls.map { it.function.name }}")

                // Add assistant message with tool calls to history
                allMessages.add(message)

                Result.success(ChatResultWithTools(
                    content = message.content,
                    metadata = metadata,
                    toolCalls = message.toolCalls,
                    isToolCall = true
                ))
            } else {
                val content = message?.content ?: "No response received"
                allMessages.add(OpenAiMessage(role = "assistant", content = content))

                Result.success(ChatResultWithTools(
                    content = content,
                    metadata = metadata,
                    toolCalls = null,
                    isToolCall = false
                ))
            }
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
     * Continue conversation after tool call with tool results
     */
    suspend fun continueWithToolResults(
        toolResults: List<ToolResult>,
        temperature: Float = 1.0f,
        model: String? = null
    ): Result<ChatResultWithTools> {
        return try {
            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"

            // Add tool result messages
            for (result in toolResults) {
                allMessages.add(OpenAiMessage(
                    role = "tool",
                    content = result.content,
                    toolCallId = result.toolCallId
                ))
            }

            val contextMessages = buildContextMessages()

            val request = OpenAiRequest(
                model = requestModel,
                messages = contextMessages,
                temperature = temperature
            )

            println("═══════════════════════════════════════════════════════════")
            println("OpenAI Continue with Tool Results (${toolResults.size} results):")
            println("═══════════════════════════════════════════════════════════")

            val startTime = System.currentTimeMillis()

            val httpResponse = client.post(chatEndpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val responseTimeMs = System.currentTimeMillis() - startTime
            val responseText = httpResponse.bodyAsText()

            val response = json.decodeFromString<OpenAiResponse>(responseText)

            if (response.error != null) {
                return Result.failure(Exception("API Error: ${response.error.message}"))
            }

            val choice = response.choices?.firstOrNull()
            val message = choice?.message
            val finishReason = choice?.finishReason

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

            // Check if there are more tool calls
            if (message?.toolCalls != null && message.toolCalls.isNotEmpty()) {
                println("🔧 More tool calls requested: ${message.toolCalls.map { it.function.name }}")
                allMessages.add(message)

                Result.success(ChatResultWithTools(
                    content = message.content,
                    metadata = metadata,
                    toolCalls = message.toolCalls,
                    isToolCall = true
                ))
            } else {
                val content = message?.content ?: "No response received"
                allMessages.add(OpenAiMessage(role = "assistant", content = content))

                Result.success(ChatResultWithTools(
                    content = content,
                    metadata = metadata,
                    toolCalls = null,
                    isToolCall = false
                ))
            }
        } catch (e: Exception) {
            println("OpenAI Error: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun extractTaskState(model: String? = null): TaskStateData? {
        val lastUserMessage = allMessages.lastOrNull { it.role == "user" } ?: return null
        val lastAssistantMessage = allMessages.lastOrNull { it.role == "assistant" } ?: return null

        return try {
            val requestModel = model?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"

            val currentGoal = taskState.goal.ifBlank { "Не определена" }
            val currentClarifications = if (taskState.clarifications.isEmpty()) "Нет"
                else taskState.clarifications.joinToString("\n") { "- $it" }
            val currentConstraints = if (taskState.constraints.isEmpty()) "Нет"
                else taskState.constraints.joinToString("\n") { "- $it" }

            val prompt = """Проанализируй последний обмен сообщениями и обнови состояние задачи.

ТЕКУЩЕЕ СОСТОЯНИЕ:
Цель: $currentGoal
Уточнения: $currentClarifications
Ограничения и термины: $currentConstraints

СООБЩЕНИЕ ПОЛЬЗОВАТЕЛЯ:
${lastUserMessage.content}

ОТВЕТ АССИСТЕНТА:
${lastAssistantMessage.content}

Обнови состояние задачи. Если нового нет — верни текущее без изменений.

Ответь СТРОГО в формате:

GOAL:
[одно предложение — чего хочет достичь пользователь, или "Не определена"]

CLARIFICATIONS:
- [что уточнил пользователь]

CONSTRAINTS:
- [ограничение или термин]

Если уточнений или ограничений нет — оставь секцию пустой (без дефисов)."""

            val request = OpenAiRequest(
                model = requestModel,
                messages = listOf(OpenAiMessage(role = "user", content = prompt)),
                temperature = 0.2f
            )

            val httpResponse = client.post(chatEndpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            val content = json.decodeFromString<OpenAiResponse>(httpResponse.bodyAsText())
                .choices?.firstOrNull()?.message?.content ?: return null

            println("★ TaskState extraction response:\n$content")

            val goalMatch = Regex("""GOAL:\s*\n(.+)""").find(content)
            val goal = goalMatch?.groupValues?.get(1)?.trim()
                ?.takeIf { it != "Не определена" } ?: taskState.goal

            val clarificationsSection = Regex("""CLARIFICATIONS:\s*\n(.*?)(?=\nCONSTRAINTS:|\z)""", RegexOption.DOT_MATCHES_ALL)
                .find(content)?.groupValues?.get(1) ?: ""
            val clarifications = clarificationsSection.lines()
                .map { it.trim().removePrefix("- ").trim() }
                .filter { it.isNotBlank() }

            val constraintsSection = Regex("""CONSTRAINTS:\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)
                .find(content)?.groupValues?.get(1) ?: ""
            val constraints = constraintsSection.lines()
                .map { it.trim().removePrefix("- ").trim() }
                .filter { it.isNotBlank() }

            TaskStateData(goal = goal, clarifications = clarifications, constraints = constraints)
        } catch (e: Exception) {
            println("TaskState extraction failed: ${e.message}")
            null
        }
    }
}

/**
 * Chat result that may contain tool calls
 */
data class ChatResultWithTools(
    val content: String?,
    val metadata: MessageMetadata,
    val toolCalls: List<ToolCall>?,
    val isToolCall: Boolean
)

/**
 * Tool execution result
 */
data class ToolResult(
    val toolCallId: String,
    val content: String
)
