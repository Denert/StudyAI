package ru.mike.study.studyai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.mike.study.studyai.api.OpenAiService
import ru.mike.study.studyai.data.ChatMessage

class ChatViewModel(apiKey: String) : ViewModel() {

    private val openAiService = OpenAiService(apiKey)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank() || _isLoading.value) return

        viewModelScope.launch {
            val userMessage = ChatMessage(content = text, isFromUser = true)
            _messages.value = _messages.value + userMessage

            _isLoading.value = true
            val loadingMessage = ChatMessage(content = "", isFromUser = false, isLoading = true)
            _messages.value = _messages.value + loadingMessage

            val result = openAiService.sendMessage(text)

            _messages.value = _messages.value.dropLast(1)

            result.fold(
                onSuccess = { response ->
                    val aiMessage = ChatMessage(content = response, isFromUser = false)
                    _messages.value = _messages.value + aiMessage
                },
                onFailure = { error ->
                    val errorMessage = ChatMessage(
                        content = "Error: ${error.message ?: "Unknown error"}",
                        isFromUser = false
                    )
                    _messages.value = _messages.value + errorMessage
                }
            )

            _isLoading.value = false
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        openAiService.clearHistory()
    }
}