package ru.mike.study.studyai.data

data class ChatMessage(
    val content: String,
    val isFromUser: Boolean,
    val isLoading: Boolean = false
)