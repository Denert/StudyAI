package ru.mike.study.studyai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.mike.study.studyai.api.OpenAiService
import ru.mike.study.studyai.data.Chat
import ru.mike.study.studyai.data.ChatBranch
import ru.mike.study.studyai.data.ChatMessage
import ru.mike.study.studyai.data.ChatSummaryData
import ru.mike.study.studyai.data.ContextStrategy
import ru.mike.study.studyai.data.FactData
import ru.mike.study.studyai.data.toChatMessage
import ru.mike.study.studyai.data.toData
import ru.mike.study.studyai.storage.ChatStorage
import java.util.UUID

class ChatViewModel(apiKey: String) : ViewModel() {

    private val openAiService = OpenAiService(apiKey)
    private val chatStorage = ChatStorage()
    private val memoryService = openAiService.getMemoryService()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    // Profiles
    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow("")
    val activeProfile: StateFlow<String> = _activeProfile.asStateFlow()

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

    // Strategy
    private val _strategy = MutableStateFlow(ContextStrategy.MEMORY_LAYERS)
    val strategy: StateFlow<ContextStrategy> = _strategy.asStateFlow()

    private val _slidingWindowSize = MutableStateFlow(10)
    val slidingWindowSize: StateFlow<Int> = _slidingWindowSize.asStateFlow()

    // Summaries (for SUMMARY strategy)
    private val _summaries = MutableStateFlow<List<ChatSummaryData>>(emptyList())
    val summaries: StateFlow<List<ChatSummaryData>> = _summaries.asStateFlow()

    // Facts (for STICKY_FACTS strategy)
    private val _facts = MutableStateFlow<List<FactData>>(emptyList())
    val facts: StateFlow<List<FactData>> = _facts.asStateFlow()

    // Branching
    private val _checkpointIndex = MutableStateFlow<Int?>(null)
    val checkpointIndex: StateFlow<Int?> = _checkpointIndex.asStateFlow()

    private val _showBranchSelector = MutableStateFlow(false)
    val showBranchSelector: StateFlow<Boolean> = _showBranchSelector.asStateFlow()

    private val _pendingBranchChat = MutableStateFlow<Chat?>(null)
    val pendingBranchChat: StateFlow<Chat?> = _pendingBranchChat.asStateFlow()

    init {
        loadProfiles()
        loadChats()
    }

    // ==================== PROFILES ====================

    private fun loadProfiles() {
        _profiles.value = memoryService.getAllProfiles()
        _activeProfile.value = memoryService.getActiveProfileName()
    }

    fun createProfile(name: String) {
        if (name.isBlank()) return
        memoryService.createProfile(name)
        loadProfiles()
    }

    fun deleteProfile(name: String) {
        if (memoryService.deleteProfile(name)) {
            loadProfiles()
        }
    }

    fun setActiveProfile(name: String) {
        memoryService.setActiveProfile(name)
        _activeProfile.value = name
    }

    fun getProfilesList(): List<String> = _profiles.value

    private fun loadChats() {
        _chats.value = chatStorage.getAllChats()
        if (_chats.value.isEmpty()) {
            createNewChat()
        } else {
            // Select first root chat (not a branch)
            val rootChat = _chats.value.firstOrNull { it.parentChatId == null }
            if (rootChat != null) {
                selectChatInternal(rootChat.id)
            } else {
                createNewChat()
            }
        }
    }

    fun createNewChat() {
        val chat = chatStorage.createNewChat("Chat ${_chats.value.size + 1}")
        _chats.value = listOf(chat) + _chats.value
        selectChatInternal(chat.id)
    }

    /**
     * Called when user clicks on a chat in sidebar
     * If chat has branches, show branch selector dialog
     */
    fun selectChat(chatId: String) {
        val chat = chatStorage.getChat(chatId) ?: return

        // Check actual branches, not just the branches field
        val actualBranches = getBranchesForChat(chatId)
        if (actualBranches.isNotEmpty()) {
            _pendingBranchChat.value = chat
            _showBranchSelector.value = true
        } else {
            selectChatInternal(chatId)
        }
    }

    /**
     * Select a specific branch
     */
    fun selectBranch(branchId: String) {
        _showBranchSelector.value = false
        _pendingBranchChat.value = null
        selectChatInternal(branchId)
    }

    /**
     * Continue with main chat (not a branch)
     */
    fun selectMainChat(chatId: String) {
        _showBranchSelector.value = false
        _pendingBranchChat.value = null
        selectChatInternal(chatId)
    }

    fun dismissBranchSelector() {
        _showBranchSelector.value = false
        _pendingBranchChat.value = null
    }

    private fun selectChatInternal(chatId: String) {
        saveCurrentChat()
        openAiService.clearHistory()

        val chat = chatStorage.getChat(chatId)
        _currentChat.value = chat
        _messages.value = chat?.messages?.map { it.toChatMessage() } ?: emptyList()
        _summaries.value = chat?.summaries ?: emptyList()
        _facts.value = chat?.facts ?: emptyList()
        _model.value = chat?.model ?: ""
        _temperature.value = chat?.temperature ?: 1.0f
        _strategy.value = chat?.strategy ?: ContextStrategy.MEMORY_LAYERS
        _slidingWindowSize.value = chat?.slidingWindowSize ?: 10
        _checkpointIndex.value = null // Always start with checkbox unchecked

        // Configure OpenAI service
        openAiService.setStrategy(_strategy.value)
        openAiService.setSlidingWindowSize(_slidingWindowSize.value)
        openAiService.setFacts(_facts.value)
        openAiService.setChatId(chatId)

        // Restore summaries in OpenAI service
        chat?.summaries?.forEach { summary ->
            openAiService.addSummary(summary.content)
        }

        // Restore conversation history
        chat?.messages?.forEach { msg ->
            if (msg.isFromUser) {
                openAiService.addToHistory("user", msg.content)
            } else {
                openAiService.addToHistory("assistant", msg.content)
            }
        }
    }

    fun deleteChat(chatId: String) {
        // Also delete all branches
        val branchIds = _chats.value
            .filter { it.parentChatId == chatId }
            .map { it.id }

        branchIds.forEach { branchId ->
            chatStorage.deleteChat(branchId)
        }
        chatStorage.deleteChat(chatId)

        _chats.value = _chats.value.filter { it.id != chatId && it.parentChatId != chatId }

        if (_currentChat.value?.id == chatId || branchIds.contains(_currentChat.value?.id)) {
            if (_chats.value.isNotEmpty()) {
                val rootChat = _chats.value.firstOrNull { it.parentChatId == null }
                if (rootChat != null) {
                    selectChatInternal(rootChat.id)
                } else {
                    createNewChat()
                }
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
            facts = _facts.value,
            strategy = _strategy.value,
            slidingWindowSize = _slidingWindowSize.value,
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

    fun setStrategy(strategy: ContextStrategy) {
        _strategy.value = strategy
        openAiService.setStrategy(strategy)
        saveCurrentChat()
    }

    fun setSlidingWindowSize(size: Int) {
        _slidingWindowSize.value = size.coerceIn(1, 100)
        openAiService.setSlidingWindowSize(_slidingWindowSize.value)
        saveCurrentChat()
    }

    // ==================== FACTS MANAGEMENT ====================

    fun updateFact(key: String, value: String) {
        val existingIndex = _facts.value.indexOfFirst { it.key == key }
        val newFact = FactData(key = key, value = value)

        _facts.value = if (existingIndex >= 0) {
            _facts.value.toMutableList().apply { set(existingIndex, newFact) }
        } else {
            _facts.value + newFact
        }
        openAiService.setFacts(_facts.value)
        saveCurrentChat()
    }

    fun deleteFact(key: String) {
        _facts.value = _facts.value.filter { it.key != key }
        openAiService.setFacts(_facts.value)
        saveCurrentChat()
    }

    // ==================== BRANCHING ====================

    fun setCheckpoint() {
        if (_currentChat.value?.canCreateBranch != true) return
        _checkpointIndex.value = _messages.value.size
        saveCurrentChat()
    }

    fun clearCheckpoint() {
        _checkpointIndex.value = null
        saveCurrentChat()
    }

    /**
     * Generate automatic branch name based on parent's branch structure
     * Root branches: 1, 2, 3...
     * Sub-branches: 1.1, 1.2, 2.1, 2.2...
     */
    private fun generateBranchName(parentChat: Chat, branchNumber: Int): String {
        return if (parentChat.branchName != null) {
            "${parentChat.branchName}.$branchNumber"
        } else {
            "$branchNumber"
        }
    }

    /**
     * Create a single branch from current chat
     */
    private fun createBranch(branchNumber: Int): Chat {
        val currentChat = _currentChat.value!!
        val branchName = generateBranchName(currentChat, branchNumber)
        val branchId = UUID.randomUUID().toString()

        return Chat(
            id = branchId,
            name = "Branch $branchName",
            messages = _messages.value.map { it.toData() },
            summaries = _summaries.value,
            facts = _facts.value,
            parentChatId = currentChat.id,
            branchName = branchName,
            branchDepth = currentChat.branchDepth + 1,
            strategy = _strategy.value,
            slidingWindowSize = _slidingWindowSize.value,
            model = _model.value,
            temperature = _temperature.value
        )
    }

    fun getBranchesForChat(chatId: String): List<Chat> {
        return _chats.value.filter { it.parentChatId == chatId }
    }

    /**
     * Get all branches recursively (including nested branches)
     * Returns pairs of (Chat, indentLevel) for UI display
     */
    fun getAllBranchesRecursive(chatId: String, indentLevel: Int = 0): List<Pair<Chat, Int>> {
        val directBranches = _chats.value.filter { it.parentChatId == chatId }
        val result = mutableListOf<Pair<Chat, Int>>()

        for (branch in directBranches) {
            result.add(branch to indentLevel)
            result.addAll(getAllBranchesRecursive(branch.id, indentLevel + 1))
        }

        return result
    }

    /**
     * Delete a branch and its sub-branches recursively
     */
    fun deleteBranch(branchId: String) {
        val branch = chatStorage.getChat(branchId) ?: return
        val parentId = branch.parentChatId ?: return

        // Delete sub-branches recursively
        val subBranches = _chats.value.filter { it.parentChatId == branchId }
        subBranches.forEach { subBranch ->
            deleteBranch(subBranch.id)
        }

        // Delete the branch from storage
        chatStorage.deleteChat(branchId)

        // Remove branch reference from parent
        val parent = chatStorage.getChat(parentId)
        if (parent != null) {
            val updatedParent = parent.copy(
                branches = parent.branches.filter { it.id != branchId }
            )
            chatStorage.saveChat(updatedParent)
            _chats.value = _chats.value.map { if (it.id == parentId) updatedParent else it }
        }

        // Remove from chats list
        _chats.value = _chats.value.filter { it.id != branchId }

        // If current chat is the deleted branch, switch to parent
        if (_currentChat.value?.id == branchId) {
            selectChatInternal(parentId)
        }
    }

    // ==================== SEND MESSAGE ====================

    fun sendMessage(text: String) {
        if (text.isBlank() || _isLoading.value) return

        val shouldBranch = _checkpointIndex.value != null &&
                _strategy.value == ContextStrategy.BRANCHING &&
                _currentChat.value?.canCreateBranch == true

        if (shouldBranch) {
            sendMessageWithBranching(text)
        } else {
            sendMessageNormal(text)
        }
    }

    /**
     * Send message with branching - creates 2 branches with different LLM responses
     */
    private fun sendMessageWithBranching(text: String) {
        val currentChat = _currentChat.value ?: return

        viewModelScope.launch {
            _isLoading.value = true

            // Add user message to current view
            val userMessage = ChatMessage(content = text, isFromUser = true)
            _messages.value = _messages.value + userMessage

            val loadingMessage = ChatMessage(content = "", isFromUser = false, isLoading = true)
            _messages.value = _messages.value + loadingMessage

            // Create 2 branches
            val branch1 = createBranch(currentChat.branches.size + 1)
            val branch2 = createBranch(currentChat.branches.size + 2)

            // Send 2 parallel requests
            val result1 = openAiService.sendMessage(text, _temperature.value, _model.value.ifBlank { null })

            // Clear and restore history for second request (to get different response)
            openAiService.clearHistory()
            _messages.value.dropLast(1).forEach { msg ->
                if (msg.isFromUser) {
                    openAiService.addToHistory("user", msg.content)
                } else if (!msg.isLoading) {
                    openAiService.addToHistory("assistant", msg.content)
                }
            }
            val result2 = openAiService.sendMessage(text, _temperature.value, _model.value.ifBlank { null })

            _messages.value = _messages.value.dropLast(1) // Remove loading

            // Process results and save branches
            val branch1WithResponse = result1.fold(
                onSuccess = { chatResult ->
                    val aiMessage = ChatMessage(content = chatResult.content, isFromUser = false, metadata = chatResult.metadata)
                    branch1.copy(messages = branch1.messages + userMessage.toData() + aiMessage.toData())
                },
                onFailure = { error ->
                    val errorMsg = ChatMessage(content = "Error: ${error.message}", isFromUser = false)
                    branch1.copy(messages = branch1.messages + userMessage.toData() + errorMsg.toData())
                }
            )

            val branch2WithResponse = result2.fold(
                onSuccess = { chatResult ->
                    val aiMessage = ChatMessage(content = chatResult.content, isFromUser = false, metadata = chatResult.metadata)
                    branch2.copy(messages = branch2.messages + userMessage.toData() + aiMessage.toData())
                },
                onFailure = { error ->
                    val errorMsg = ChatMessage(content = "Error: ${error.message}", isFromUser = false)
                    branch2.copy(messages = branch2.messages + userMessage.toData() + errorMsg.toData())
                }
            )

            // Save branches
            chatStorage.saveChat(branch1WithResponse)
            chatStorage.saveChat(branch2WithResponse)

            // Update parent with branch references
            val updatedParent = currentChat.copy(
                branches = currentChat.branches + listOf(
                    ChatBranch(id = branch1WithResponse.id, name = branch1WithResponse.branchName ?: "", checkpointIndex = _messages.value.size),
                    ChatBranch(id = branch2WithResponse.id, name = branch2WithResponse.branchName ?: "", checkpointIndex = _messages.value.size)
                )
            )
            chatStorage.saveChat(updatedParent)

            // Update chats list
            _chats.value = _chats.value.map {
                if (it.id == currentChat.id) updatedParent else it
            } + branch1WithResponse + branch2WithResponse

            // Clear checkpoint
            _checkpointIndex.value = null

            // Switch to branch 1
            _currentChat.value = branch1WithResponse
            _messages.value = branch1WithResponse.messages.map { it.toChatMessage() }

            // Restore OpenAI history for branch 1
            openAiService.clearHistory()
            branch1WithResponse.messages.forEach { msg ->
                if (msg.isFromUser) {
                    openAiService.addToHistory("user", msg.content)
                } else {
                    openAiService.addToHistory("assistant", msg.content)
                }
            }

            _isLoading.value = false

            println("Created 2 branches: ${branch1WithResponse.branchName} and ${branch2WithResponse.branchName}")
        }
    }

    /**
     * Normal message send without branching
     */
    private fun sendMessageNormal(text: String) {
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

                    // Strategy-specific post-processing
                    when (_strategy.value) {
                        ContextStrategy.SUMMARY -> checkAndSummarizeIfNeeded()
                        ContextStrategy.STICKY_FACTS -> extractFactsIfNeeded()
                        ContextStrategy.MEMORY_LAYERS -> extractMemoryLayersIfNeeded()
                        else -> { /* No post-processing */ }
                    }
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
            val newSummary = ChatSummaryData(
                id = UUID.randomUUID().toString(),
                messageStartIndex = _summaries.value.size * OpenAiService.BATCH_SIZE_FOR_SUMMARY,
                messageEndIndex = (_summaries.value.size + 1) * OpenAiService.BATCH_SIZE_FOR_SUMMARY - 1,
                content = summaryResult.content,
                tokenCount = summaryResult.tokenCount
            )
            _summaries.value = _summaries.value + newSummary
            println("Context management: Created summary #${_summaries.value.size}")
        }
    }

    private suspend fun extractFactsIfNeeded() {
        // Extract facts after every assistant response
        val factsResult = openAiService.extractFacts(_model.value.ifBlank { null })
        if (factsResult != null) {
            _facts.value = factsResult.facts
            openAiService.setFacts(_facts.value)
            println("Context management: Updated facts (${_facts.value.size} facts)")
        }
    }

    private suspend fun extractMemoryLayersIfNeeded() {
        // Extract and update memory layers after every assistant response
        val success = openAiService.extractMemoryUpdates(_model.value.ifBlank { null })
        if (success) {
            println("Context management: Memory layers updated")
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        _summaries.value = emptyList()
        _facts.value = emptyList()
        _checkpointIndex.value = null
        openAiService.clearHistory()
        saveCurrentChat()
    }
}
