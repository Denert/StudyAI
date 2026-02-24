package ru.mike.study.studyai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.mike.study.studyai.api.OpenAiService
import ru.mike.study.studyai.data.Chat
import ru.mike.study.studyai.data.ChatMessage
import ru.mike.study.studyai.data.toChatMessage
import ru.mike.study.studyai.data.toData
import ru.mike.study.studyai.storage.ChatStorage

class ChatViewModel(apiKey: String) : ViewModel() {

    private val openAiService = OpenAiService(apiKey)
    private val chatStorage = ChatStorage()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _currentChat = MutableStateFlow<Chat?>(null)
    val currentChat: StateFlow<Chat?> = _currentChat.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _temperature = MutableStateFlow(1.0f)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _model = MutableStateFlow("")
    val model: StateFlow<String> = _model.asStateFlow()

    init {
        loadChats()
    }

    private fun loadChats() {
        _chats.value = chatStorage.getAllChats()
        if (_chats.value.isEmpty()) {
            createNewChat()
        } else {
            selectChat(_chats.value.first().id)
        }
    }

    fun createNewChat() {
        val chat = chatStorage.createNewChat("Chat ${_chats.value.size + 1}")
        _chats.value = listOf(chat) + _chats.value
        selectChat(chat.id)
    }

    fun selectChat(chatId: String) {
        saveCurrentChat()
        openAiService.clearHistory()

        val chat = chatStorage.getChat(chatId)
        _currentChat.value = chat
        _messages.value = chat?.messages?.map { it.toChatMessage() } ?: emptyList()
        _model.value = chat?.model ?: ""
        _temperature.value = chat?.temperature ?: 1.0f

        // Restore conversation history in OpenAI service
        chat?.messages?.forEach { msg ->
            if (msg.isFromUser) {
                openAiService.addToHistory("user", msg.content)
            } else {
                openAiService.addToHistory("assistant", msg.content)
            }
        }
    }

    fun deleteChat(chatId: String) {
        chatStorage.deleteChat(chatId)
        _chats.value = _chats.value.filter { it.id != chatId }

        if (_currentChat.value?.id == chatId) {
            if (_chats.value.isNotEmpty()) {
                selectChat(_chats.value.first().id)
            } else {
                createNewChat()
            }
        }
    }

    fun renameChat(chatId: String, newName: String) {
        val chat = chatStorage.getChat(chatId)?.copy(name = newName)
        if (chat != null) {
            chatStorage.saveChat(chat)
            _chats.value = _chats.value.map { if (it.id == chatId) chat else it }
            if (_currentChat.value?.id == chatId) {
                _currentChat.value = chat
            }
        }
    }

    private fun saveCurrentChat() {
        val chat = _currentChat.value ?: return
        val updatedChat = chat.copy(
            messages = _messages.value.filter { !it.isLoading }.map { it.toData() },
            model = _model.value,
            temperature = _temperature.value
        )
        chatStorage.saveChat(updatedChat)
        _chats.value = _chats.value.map { if (it.id == chat.id) updatedChat else it }
    }

    fun setTemperature(value: Float) {
        _temperature.value = value.coerceIn(0f, 2f)
    }

    fun setModel(value: String) {
        _model.value = value
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _isLoading.value) return

        viewModelScope.launch {
            val userMessage = ChatMessage(content = text, isFromUser = true)
            _messages.value = _messages.value + userMessage

            _isLoading.value = true
            val loadingMessage = ChatMessage(content = "", isFromUser = false, isLoading = true)
            _messages.value = _messages.value + loadingMessage

            val result = openAiService.sendMessage(text, _temperature.value, _model.value.ifBlank { null })

            _messages.value = _messages.value.dropLast(1)

            result.fold(
                onSuccess = { chatResult ->
                    val aiMessage = ChatMessage(
                        content = chatResult.content,
                        isFromUser = false,
                        metadata = chatResult.metadata
                    )
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
            saveCurrentChat()

            // Update chat name from first message if it's default
            if (_currentChat.value?.name?.startsWith("Chat ") == true && _messages.value.size == 2) {
                val shortName = text.take(30) + if (text.length > 30) "..." else ""
                renameChat(_currentChat.value!!.id, shortName)
            }
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        openAiService.clearHistory()
        saveCurrentChat()
    }
}