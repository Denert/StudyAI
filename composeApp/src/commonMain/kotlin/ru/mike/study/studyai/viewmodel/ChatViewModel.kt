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
import ru.mike.study.studyai.data.ChatSummaryData
import ru.mike.study.studyai.data.toChatMessage
import ru.mike.study.studyai.data.toData
import ru.mike.study.studyai.storage.ChatStorage
import java.util.UUID

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

    // Track summaries for current chat
    private val _summaries = MutableStateFlow<List<ChatSummaryData>>(emptyList())
    private var summarizedMessageCount = 0

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
        _summaries.value = chat?.summaries ?: emptyList()
        _model.value = chat?.model ?: ""
        _temperature.value = chat?.temperature ?: 1.0f

        // Calculate how many messages were summarized
        summarizedMessageCount = _summaries.value.sumOf { it.messageEndIndex - it.messageStartIndex + 1 }

        // Restore summaries in OpenAI service
        chat?.summaries?.forEach { summary ->
            openAiService.addSummary(summary.content)
        }

        // Restore conversation history in OpenAI service (only recent messages)
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
            summaries = _summaries.value,
            model = _model.value,
            temperature = _temperature.value
        )
        chatStorage.saveChat(updatedChat)
        _currentChat.value = updatedChat
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

                    // Check if we need to summarize old messages
                    checkAndSummarizeIfNeeded()
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

    private suspend fun checkAndSummarizeIfNeeded() {
        val summaryResult = openAiService.checkAndSummarize(_model.value.ifBlank { null })

        if (summaryResult != null) {
            // Create new summary data
            val startIndex = summarizedMessageCount
            val endIndex = startIndex + OpenAiService.BATCH_SIZE_FOR_SUMMARY - 1

            val newSummary = ChatSummaryData(
                id = UUID.randomUUID().toString(),
                messageStartIndex = startIndex,
                messageEndIndex = endIndex,
                content = summaryResult.content,
                tokenCount = summaryResult.tokenCount
            )

            _summaries.value = _summaries.value + newSummary
            summarizedMessageCount += OpenAiService.BATCH_SIZE_FOR_SUMMARY

            // Remove summarized messages from UI
            _messages.value = _messages.value.drop(OpenAiService.BATCH_SIZE_FOR_SUMMARY)

            println("Context management: Created summary #${_summaries.value.size}, removed ${OpenAiService.BATCH_SIZE_FOR_SUMMARY} old messages")
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        _summaries.value = emptyList()
        summarizedMessageCount = 0
        openAiService.clearHistory()
        saveCurrentChat()
    }
}