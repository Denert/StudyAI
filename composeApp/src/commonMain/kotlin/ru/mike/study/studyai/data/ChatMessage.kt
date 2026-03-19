package ru.mike.study.studyai.data

/**
 * Task phase for finite state machine
 * Flow: PLANNING → EXECUTION → VALIDATION → DONE
 */
enum class TaskPhase {
    PLANNING,    // Анализ и планирование задачи
    EXECUTION,   // Выполнение плана
    VALIDATION,  // Проверка результатов
    DONE         // Задача завершена
}

data class ChatMessage(
    val content: String,
    val isFromUser: Boolean,
    val isLoading: Boolean = false,
    val metadata: MessageMetadata? = null,
    val phase: TaskPhase? = null,           // Текущий этап задачи
    val phaseCompleted: Boolean = false,    // Этап завершён, ждём подтверждения
    val isSystemNotification: Boolean = false, // Системное уведомление (погода и т.д.)
    val isQueryRewrite: Boolean = false     // Переформулированный RAG-запрос
)

data class MessageMetadata(
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val responseTimeMs: Long,
    val temperature: Float,
    val costRub: Double = 0.0
)

object PricingCalculator {
    // Курс доллара к рублю
    private const val USD_TO_RUB = 95.0

    // Цены за 1M токенов в долларах (input, output)
    private val modelPrices = mapOf(
        // GPT-5 серия
        "gpt-5.2-pro" to Pair(21.00, 168.00),
        "gpt-5.2" to Pair(10.00, 40.00),
        "gpt-5.1" to Pair(5.00, 20.00),
        "gpt-5-mini" to Pair(0.30, 1.20),
        "gpt-5-nano" to Pair(0.10, 0.40),
        "gpt-5" to Pair(1.25, 10.00),

        // GPT-4.1 серия
        "gpt-4.1-mini" to Pair(0.40, 1.60),
        "gpt-4.1-nano" to Pair(0.10, 0.40),
        "gpt-4.1" to Pair(2.00, 8.00),

        // GPT-4o серия
        "gpt-4o-mini" to Pair(0.15, 0.60),
        "gpt-4o-audio" to Pair(2.50, 10.00),
        "gpt-4o" to Pair(2.50, 10.00),

        // GPT-4 серия
        "gpt-4-turbo" to Pair(10.00, 30.00),
        "gpt-4" to Pair(30.00, 60.00),

        // GPT-3.5 серия
        "gpt-3.5-turbo" to Pair(0.50, 1.50),

        // O-серия (reasoning models)
        "o4-mini" to Pair(1.10, 4.40),
        "o3-mini" to Pair(1.10, 4.40),
        "o3" to Pair(10.00, 40.00),
        "o1-mini" to Pair(3.00, 12.00),
        "o1-preview" to Pair(15.00, 60.00),
        "o1" to Pair(15.00, 60.00),

        // Embeddings
        "text-embedding-3-large" to Pair(0.13, 0.0),
        "text-embedding-3-small" to Pair(0.02, 0.0),
        "text-embedding-ada-002" to Pair(0.10, 0.0),

        // Image models (DALL-E)
        "dall-e-3" to Pair(40.00, 0.0),
        "dall-e-2" to Pair(20.00, 0.0),

        // Audio models
        "whisper-1" to Pair(0.006, 0.0), // per second
        "tts-1" to Pair(15.00, 0.0),
        "tts-1-hd" to Pair(30.00, 0.0)
    )

    fun calculateCostRub(model: String, promptTokens: Int, completionTokens: Int): Double {
        val prices = modelPrices.entries.find { model.contains(it.key, ignoreCase = true) }?.value
            ?: Pair(0.15, 0.60) // default to gpt-4o-mini prices

        val inputCostUsd = (promptTokens / 1_000_000.0) * prices.first
        val outputCostUsd = (completionTokens / 1_000_000.0) * prices.second
        val totalCostUsd = inputCostUsd + outputCostUsd

        return totalCostUsd * USD_TO_RUB
    }
}