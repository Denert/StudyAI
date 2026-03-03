package ru.mike.study.studyai.data

import kotlinx.serialization.Serializable

@Serializable
enum class ContextStrategy {
    NONE,           // No strategy - send all messages as-is
    SLIDING_WINDOW, // Keep last N messages only
    SUMMARY,        // Summarize old messages
    STICKY_FACTS,   // Extract key-value facts + last N messages
    BRANCHING,      // Create branches from checkpoints
    MEMORY_LAYERS   // 3-layer memory: short-term, working, long-term
}

@Serializable
data class Chat(
    val id: String,
    val name: String,
    val messages: List<ChatMessageData> = emptyList(),
    val summaries: List<ChatSummaryData> = emptyList(),
    val facts: List<FactData> = emptyList(),
    val branches: List<ChatBranch> = emptyList(),
    val parentChatId: String? = null,
    val branchName: String? = null,
    val branchDepth: Int = 0,
    val checkpointIndex: Int? = null,
    val strategy: ContextStrategy = ContextStrategy.NONE,
    val slidingWindowSize: Int = 10,
    val createdAt: Long = System.currentTimeMillis(),
    val model: String = "",
    val temperature: Float = 1.0f
) {
    val totalTokens: Int
        get() = messages.sumOf { it.metadata?.totalTokens ?: 0 }

    val totalCostRub: Double
        get() = messages.sumOf { it.metadata?.costRub ?: 0.0 }

    val summaryTokens: Int
        get() = summaries.sumOf { it.tokenCount }

    val hasBranches: Boolean
        get() = branches.isNotEmpty()

    val canCreateBranch: Boolean
        get() = branchDepth < 3
}

@Serializable
data class FactData(
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ChatBranch(
    val id: String,
    val name: String,
    val checkpointIndex: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class ChatSummaryData(
    val id: String,
    val messageStartIndex: Int,
    val messageEndIndex: Int,
    val content: String,
    val tokenCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class ChatMessageData(
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: MessageMetadataData? = null
)

@Serializable
data class MessageMetadataData(
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val responseTimeMs: Long,
    val temperature: Float,
    val costRub: Double
)

fun ChatMessageData.toChatMessage(): ChatMessage {
    return ChatMessage(
        content = content,
        isFromUser = isFromUser,
        metadata = metadata?.let {
            MessageMetadata(
                model = it.model,
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
                responseTimeMs = it.responseTimeMs,
                temperature = it.temperature,
                costRub = it.costRub
            )
        }
    )
}

fun ChatMessage.toData(): ChatMessageData {
    return ChatMessageData(
        content = content,
        isFromUser = isFromUser,
        metadata = metadata?.let {
            MessageMetadataData(
                model = it.model,
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
                responseTimeMs = it.responseTimeMs,
                temperature = it.temperature,
                costRub = it.costRub
            )
        }
    )
}
