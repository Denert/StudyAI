package ru.mike.study.studyai.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<OpenAiMessage>,
    val temperature: Float = 1.0f
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String
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
    val message: OpenAiMessage
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