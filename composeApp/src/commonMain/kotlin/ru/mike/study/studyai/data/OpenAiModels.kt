package ru.mike.study.studyai.data

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