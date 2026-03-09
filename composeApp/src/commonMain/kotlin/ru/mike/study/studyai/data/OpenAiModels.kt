package ru.mike.study.studyai.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class OpenAiRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<OpenAiMessage>,
    val temperature: Float = 1.0f,
    val tools: List<OpenAiTool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null  // "auto", "none", or specific tool
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null  // For tool response messages
)

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunction
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null  // JSON Schema
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String  // JSON string
)

@Serializable
data class OpenAiResponse(
    val choices: List<Choice>? = null,
    val error: OpenAiError? = null,
    val usage: Usage? = null,
    val model: String? = null
)

@Serializable
data class Choice(
    val message: OpenAiMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null  // "stop", "tool_calls", etc.
)

@Serializable
data class OpenAiError(
    val message: String? = null,
    val type: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0
)
