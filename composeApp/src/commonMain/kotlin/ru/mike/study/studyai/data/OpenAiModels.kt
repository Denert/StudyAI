package ru.mike.study.studyai.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiRequest(
    val model: String = "gpt-5-mini",
    val messages: List<OpenAiMessage>,
    @SerialName("max_completion_tokens")
    val maxTokens: Int = 1024
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String
)

@Serializable
data class OpenAiResponse(
    val choices: List<Choice>? = null,
    val error: OpenAiError? = null
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