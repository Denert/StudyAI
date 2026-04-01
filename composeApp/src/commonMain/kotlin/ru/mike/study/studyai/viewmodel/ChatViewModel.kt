package ru.mike.study.studyai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ru.mike.study.studyai.api.OpenAiService
import ru.mike.study.studyai.api.ToolResult
import ru.mike.study.studyai.github.GitHubService
import ru.mike.study.studyai.github.PrReviewService
import ru.mike.study.studyai.github.ReviewedPrStore
import ru.mike.study.studyai.github.WebhookController
import ru.mike.study.studyai.data.Chat
import ru.mike.study.studyai.data.OpenAiFunction
import ru.mike.study.studyai.data.OpenAiTool
import ru.mike.study.studyai.data.ToolCall
import ru.mike.study.studyai.data.ChatBranch
import ru.mike.study.studyai.data.ChatMessage
import ru.mike.study.studyai.data.ChatSummaryData
import ru.mike.study.studyai.data.ContextStrategy
import ru.mike.study.studyai.data.FactData
import ru.mike.study.studyai.data.TaskStateData
import ru.mike.study.studyai.data.TaskPhase
import ru.mike.study.studyai.data.toChatMessage
import ru.mike.study.studyai.data.toData
import ru.mike.study.studyai.mcp.McpConfigService
import ru.mike.study.studyai.mcp.McpManager
import ru.mike.study.studyai.mcp.McpLogger
import ru.mike.study.studyai.mcp.WeatherNotificationClient
import ru.mike.study.studyai.config.AppSettings
import ru.mike.study.studyai.config.AppSettingsStore
import ru.mike.study.studyai.config.LlmProvider
import ru.mike.study.studyai.config.OllamaManager
import kotlinx.coroutines.Dispatchers
import ru.mike.study.studyai.rag.RagMode
import ru.mike.study.studyai.rag.RagService
import ru.mike.study.studyai.storage.ChatStorage
import java.util.UUID

class ChatViewModel(private val openAiApiKey: String, private val localApiKey: String = "") : ViewModel() {

    private val _appSettings = MutableStateFlow(AppSettingsStore.load())
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()

    private var openAiService = createOpenAiService()
    private val chatStorage = ChatStorage()
    private val memoryService = openAiService.getMemoryService()
    private val mcpManager = McpManager()
    private val weatherNotificationClient = WeatherNotificationClient()
    var ragService = createRagService()
        private set

    // PR Review
    private val reviewedPrStore = ReviewedPrStore()
    private var webhookController: WebhookController? = null
    private val _prReviewStatus = MutableStateFlow("")
    val prReviewStatus: StateFlow<String> = _prReviewStatus.asStateFlow()

    private fun createOpenAiService(): OpenAiService {
        val s = _appSettings.value
        val key = when (s.provider) {
            ru.mike.study.studyai.config.LlmProvider.OPENAI -> openAiApiKey
            ru.mike.study.studyai.config.LlmProvider.LOCAL -> localApiKey
            else -> ""
        }
        return OpenAiService(key, s.effectiveBaseUrl).also { service ->
            service.setMemoryConfig(s.systemPromptEnabled, s.invariantsEnabled, s.profileMemoryEnabled)
            service.setThinkingEnabled(s.thinkingEnabled)
        }
    }

    private fun createRagService(): RagService {
        val s = _appSettings.value
        val key = when (s.provider) {
            ru.mike.study.studyai.config.LlmProvider.OPENAI -> openAiApiKey
            ru.mike.study.studyai.config.LlmProvider.LOCAL -> localApiKey
            else -> openAiApiKey
        }
        return RagService(s, key)
    }

    fun saveLocalBaseUrl(url: String) {
        AppSettingsStore.save(_appSettings.value.copy(localBaseUrl = url))
    }

    fun updateSettings(settings: AppSettings) {
        _appSettings.value = settings
        AppSettingsStore.save(settings)
        openAiService = createOpenAiService()
        ragService = createRagService()
        startOllamaIfNeeded(settings)
        startWebhookIfNeeded(settings)
        // Re-apply current chat context to the new service
        val chat = _currentChat.value
        if (chat != null) {
            openAiService.setStrategy(_strategy.value)
            openAiService.setSlidingWindowSize(_slidingWindowSize.value)
            openAiService.setFacts(_facts.value)
            openAiService.setTaskState(_taskState.value)
            openAiService.setChatId(chat.id)
        }
    }

    private val _ragMode = MutableStateFlow(RagMode.MCP_TOOL)
    val ragMode: StateFlow<RagMode> = _ragMode.asStateFlow()

    fun setRagMode(mode: RagMode) { _ragMode.value = mode }

    /** Возвращает модель для запроса: явно выбранная > модель провайдера */
    private val effectiveModel: String?
        get() = _model.value.ifBlank { null }
            ?: when (_appSettings.value.provider) {
                ru.mike.study.studyai.config.LlmProvider.OLLAMA -> _appSettings.value.ollamaChatModel
                ru.mike.study.studyai.config.LlmProvider.LOCAL -> _appSettings.value.localChatModel.ifBlank { null }
                else -> null
            }

    private val _ragMinScore = MutableStateFlow(0.3f)
    val ragMinScore: StateFlow<Float> = _ragMinScore.asStateFlow()

    private val _ragTopK = MutableStateFlow(5)
    val ragTopK: StateFlow<Int> = _ragTopK.asStateFlow()

    private val _ragCandidateK = MutableStateFlow(20)
    val ragCandidateK: StateFlow<Int> = _ragCandidateK.asStateFlow()

    private val _ragFilterEnabled = MutableStateFlow(true)
    val ragFilterEnabled: StateFlow<Boolean> = _ragFilterEnabled.asStateFlow()

    private val _ragRewriteEnabled = MutableStateFlow(true)
    val ragRewriteEnabled: StateFlow<Boolean> = _ragRewriteEnabled.asStateFlow()

    fun setRagMinScore(v: Float) { _ragMinScore.value = v.coerceIn(0f, 1f) }
    fun setRagTopK(v: Int) { _ragTopK.value = v.coerceIn(1, 50) }
    fun setRagCandidateK(v: Int) { _ragCandidateK.value = v.coerceIn(1, 100) }
    fun setRagFilterEnabled(v: Boolean) { _ragFilterEnabled.value = v }
    fun setRagRewriteEnabled(v: Boolean) { _ragRewriteEnabled.value = v }

    private val json = Json { ignoreUnknownKeys = true }

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

    private val _temperature = MutableStateFlow(_appSettings.value.temperature)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _maxTokens = MutableStateFlow(_appSettings.value.maxTokens)
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _numCtx = MutableStateFlow(_appSettings.value.numCtx)
    val numCtx: StateFlow<Int> = _numCtx.asStateFlow()

    /** num_ctx передаётся только Ollama — OpenAI и LOCAL возвращают null (не знают этот параметр) */
    private val effectiveNumCtx: Int?
        get() = if (_appSettings.value.provider == ru.mike.study.studyai.config.LlmProvider.OLLAMA)
            _numCtx.value else null

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

    // Task State
    private val _taskState = MutableStateFlow(TaskStateData())
    val taskState: StateFlow<TaskStateData> = _taskState.asStateFlow()

    fun clearTaskState() {
        _taskState.value = TaskStateData()
        openAiService.setTaskState(TaskStateData())
        saveCurrentChat()
    }

    // Branching
    private val _checkpointIndex = MutableStateFlow<Int?>(null)
    val checkpointIndex: StateFlow<Int?> = _checkpointIndex.asStateFlow()

    private val _showBranchSelector = MutableStateFlow(false)
    val showBranchSelector: StateFlow<Boolean> = _showBranchSelector.asStateFlow()

    private val _pendingBranchChat = MutableStateFlow<Chat?>(null)
    val pendingBranchChat: StateFlow<Chat?> = _pendingBranchChat.asStateFlow()

    // Task Phase FSM
    private val _currentPhase = MutableStateFlow<TaskPhase?>(null)
    val currentPhase: StateFlow<TaskPhase?> = _currentPhase.asStateFlow()

    private val _awaitingPhaseConfirmation = MutableStateFlow(false)
    val awaitingPhaseConfirmation: StateFlow<Boolean> = _awaitingPhaseConfirmation.asStateFlow()

    init {
        loadProfiles()
        loadChats()
        initWeatherNotifications()
        startOllamaIfNeeded(_appSettings.value)
        startWebhookIfNeeded(_appSettings.value)
    }

    override fun onCleared() {
        super.onCleared()
        webhookController?.stop()
    }

    private fun startWebhookIfNeeded(settings: AppSettings) {
        webhookController?.stop()
        webhookController = null
        if (!settings.prReviewEnabled || settings.projectPath.isBlank()) return

        val ownerRepo = GitHubService.parseOwnerRepo(settings.projectPath)
            ?.let { (owner, repo) -> "$owner/$repo" } ?: run {
            println("PrWatcher: could not detect GitHub repo from projectPath")
            _prReviewStatus.value = "Не удалось определить репозиторий из пути проекта"
            return
        }

        val key = when (settings.provider) {
            ru.mike.study.studyai.config.LlmProvider.OPENAI -> openAiApiKey
            ru.mike.study.studyai.config.LlmProvider.LOCAL -> localApiKey
            else -> ""
        }
        val prReviewService = PrReviewService(
            gitHubService = GitHubService(ru.mike.study.studyai.config.ApiConfig.githubToken),
            ragService = ragService,
            appSettings = settings,
            apiKey = key,
            reviewedPrStore = reviewedPrStore,
            onStatusUpdate = { _prReviewStatus.value = it },
            onChatMessage = { message ->
                addSystemMessage("🔔 **PR Review**\n$message")
            }
        )
        webhookController = WebhookController(
            projectPath = settings.projectPath,
            ownerRepo = ownerRepo,
            scope = viewModelScope,
            githubService = GitHubService(ru.mike.study.studyai.config.ApiConfig.githubToken),
            reviewedPrStore = reviewedPrStore,
            onEvent = { branch, sha ->
                prReviewService.reviewByBranch(ownerRepo, branch, sha)
            }
        )
        webhookController?.start()
    }

    private fun startOllamaIfNeeded(settings: AppSettings) {
        if (settings.provider == LlmProvider.OLLAMA) {
            viewModelScope.launch(Dispatchers.IO) {
                OllamaManager.ensureRunning(settings.ollamaBaseUrl)
            }
        }
    }

    private fun initWeatherNotifications() {
        val isWeatherServerEnabled = McpConfigService().getServers()
            .any { it.enabled && it.args.any { arg -> arg.contains("weather-scheduler-mcp") } }

        if (!isWeatherServerEnabled) {
            McpLogger.log("[WS Client] Weather scheduler MCP is disabled, skipping WebSocket connection")
            return
        }

        // Connect to WebSocket server for weather notifications
        weatherNotificationClient.connect(scope = viewModelScope)

        // Listen for notifications and add them to chat
        viewModelScope.launch {
            weatherNotificationClient.notifications.collect { notification ->
                if (notification.type == "weather") {
                    addSystemMessage(notification.message)
                }
            }
        }
    }

    /**
     * Add a system notification message to the current chat
     */
    private fun addSystemMessage(content: String) {
        val systemMessage = ChatMessage(
            content = content,
            isFromUser = false,
            isSystemNotification = true
        )
        _messages.value = _messages.value + systemMessage
        saveCurrentChat()
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

    /**
     * Start a new task - enters PLANNING phase
     */
    fun startTask() {
        _currentPhase.value = TaskPhase.PLANNING
        _awaitingPhaseConfirmation.value = false
    }

    /**
     * Mark current phase as completed, await user confirmation
     */
    fun completeCurrentPhase() {
        _awaitingPhaseConfirmation.value = true
        // Update last message to show phase completed
        updateLastMessagePhaseCompleted()
    }

    /**
     * User confirmed - transition to next phase
     */
    fun confirmPhaseTransition() {
        _awaitingPhaseConfirmation.value = false
        val nextPhase = getNextPhase(_currentPhase.value)
        _currentPhase.value = nextPhase

        // Clear phaseCompleted flag on last message
        clearLastMessagePhaseCompleted()

        // If task is done, reset
        if (nextPhase == TaskPhase.DONE) {
            // Task completed, can start new one
        }
    }

    /**
     * User rejected - stay in current phase or go back
     */
    fun rejectPhaseTransition() {
        _awaitingPhaseConfirmation.value = false
        clearLastMessagePhaseCompleted()

        // For VALIDATION rejection, go back to EXECUTION
        if (_currentPhase.value == TaskPhase.VALIDATION) {
            _currentPhase.value = TaskPhase.EXECUTION
        }
        // Otherwise stay in current phase
    }

    /**
     * Reset task state
     */
    fun resetTaskPhase() {
        _currentPhase.value = null
        _awaitingPhaseConfirmation.value = false
    }

    private fun getNextPhase(current: TaskPhase?): TaskPhase? {
        return when (current) {
            TaskPhase.PLANNING -> TaskPhase.EXECUTION
            TaskPhase.EXECUTION -> TaskPhase.VALIDATION
            TaskPhase.VALIDATION -> TaskPhase.DONE
            TaskPhase.DONE -> null
            null -> TaskPhase.PLANNING
        }
    }

    private fun updateLastMessagePhaseCompleted() {
        val messages = _messages.value.toMutableList()
        if (messages.isNotEmpty()) {
            val lastIndex = messages.lastIndex
            val lastMsg = messages[lastIndex]
            if (!lastMsg.isFromUser) {
                messages[lastIndex] = lastMsg.copy(
                    phase = _currentPhase.value,
                    phaseCompleted = true
                )
                _messages.value = messages
            }
        }
    }

    private fun clearLastMessagePhaseCompleted() {
        val messages = _messages.value.toMutableList()
        if (messages.isNotEmpty()) {
            val lastIndex = messages.lastIndex
            val lastMsg = messages[lastIndex]
            if (!lastMsg.isFromUser && lastMsg.phaseCompleted) {
                messages[lastIndex] = lastMsg.copy(phaseCompleted = false)
                _messages.value = messages
            }
        }
    }

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
        webhookController?.checkNow()
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
        _taskState.value = chat?.taskState ?: TaskStateData()
        _model.value = chat?.model ?: ""
        _temperature.value = chat?.temperature ?: 1.0f
        _strategy.value = chat?.strategy ?: ContextStrategy.MEMORY_LAYERS
        _slidingWindowSize.value = chat?.slidingWindowSize ?: 10
        _checkpointIndex.value = null // Always start with checkbox unchecked

        // Restore phase from last message or reset
        val lastAssistantMsg = _messages.value.lastOrNull { !it.isFromUser }
        _currentPhase.value = lastAssistantMsg?.phase
        _awaitingPhaseConfirmation.value = lastAssistantMsg?.phaseCompleted ?: false

        // Configure OpenAI service
        openAiService.setStrategy(_strategy.value)
        openAiService.setSlidingWindowSize(_slidingWindowSize.value)
        openAiService.setFacts(_facts.value)
        openAiService.setTaskState(_taskState.value)
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
            taskState = _taskState.value,
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

    fun saveModelParams(temperature: Float, maxTokens: Int, numCtx: Int, thinkingEnabled: Boolean) {
        _temperature.value = temperature.coerceIn(0f, 2f)
        _maxTokens.value = maxTokens
        _numCtx.value = numCtx
        val updated = _appSettings.value.copy(
            temperature = temperature.coerceIn(0f, 2f),
            maxTokens = maxTokens,
            numCtx = numCtx,
            thinkingEnabled = thinkingEnabled
        )
        _appSettings.value = updated
        AppSettingsStore.save(updated)
        openAiService.setThinkingEnabled(thinkingEnabled)
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

    fun sendMessage(rawText: String) {
        if (rawText.isBlank() || _isLoading.value) return

        // /help command: switch to RAG_PLUS_MODEL and inject project system prompt
        val isHelp = rawText.trimStart().startsWith("/help")
        val text = if (isHelp) rawText.trimStart().removePrefix("/help").trim().ifBlank { "Расскажи о проекте" } else rawText
        if (isHelp) {
            _ragMode.value = RagMode.RAG_PLUS_MODEL
        }

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
            val result1 = openAiService.sendMessage(text, _temperature.value, effectiveModel, _maxTokens.value, effectiveNumCtx)

            // Clear and restore history for second request (to get different response)
            openAiService.clearHistory()
            _messages.value.dropLast(1).forEach { msg ->
                if (msg.isFromUser) {
                    openAiService.addToHistory("user", msg.content)
                } else if (!msg.isLoading) {
                    openAiService.addToHistory("assistant", msg.content)
                }
            }
            val result2 = openAiService.sendMessage(text, _temperature.value, effectiveModel, _maxTokens.value, effectiveNumCtx)

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

            try {
                // Connect to MCP servers and get tools
                // Ollama doesn't support OpenAI-style function calling — skip tools to avoid JSON responses
                val isOllama = _appSettings.value.provider == ru.mike.study.studyai.config.LlmProvider.OLLAMA
                mcpManager.connectAll()
                val mcpTools = if (isOllama) emptyList() else mcpManager.getAllTools()
                val openAiTools = mcpTools.map { toolWithServer ->
                    OpenAiTool(
                        function = OpenAiFunction(
                            name = toolWithServer.tool.name,
                            description = toolWithServer.tool.description,
                            parameters = toolWithServer.tool.inputSchema
                        )
                    )
                }.toMutableList()

                // RAG mode handling
                val ragFormatInstruction = """
                    ОБЯЗАТЕЛЬНЫЙ ФОРМАТ ОТВЕТА (строго соблюдай структуру):

                    ### Ответ
                    [Твой ответ на вопрос пользователя]

                    ### Источники
                    - [название файла / раздел] (релевантность: X.XX)

                    ### Цитаты
                    > "точная цитата из документа"
                    — [источник]

                    ВАЖНО: Если предоставленный контекст пуст, не содержит релевантной информации или оценки релевантности низкие — в разделе "Ответ" напиши "Не знаю." и задай пользователю уточняющие вопросы, которые помогут найти нужную информацию. В разделах "Источники" и "Цитаты" в таком случае напиши "Не найдено".
                """.trimIndent()

                val ragSystemContext: String? = when (_ragMode.value) {
                    RagMode.NO_RAG -> null
                    RagMode.RAG_ONLY -> {
                        openAiTools.clear() // no external tools in RAG_ONLY mode
                        val ctx = if (_ragFilterEnabled.value) {
                            val (rewritten, filtered) = ragService.getContextFiltered(
                                text, "fixed",
                                topK = _ragTopK.value,
                                candidateK = _ragCandidateK.value,
                                minScore = _ragMinScore.value,
                                model = effectiveModel ?: "gpt-4o-mini",
                                rewriteEnabled = _ragRewriteEnabled.value
                            )
                            if (rewritten != text) {
                                val rewriteMsg = ChatMessage(content = rewritten, isFromUser = true, isQueryRewrite = true)
                                _messages.value = _messages.value.dropLast(1) + rewriteMsg + _messages.value.last()
                            }
                            filtered
                        } else {
                            ragService.getContext(text, "fixed")
                        }
                        val contextBlock = if (ctx.isBlank())
                            "КОНТЕКСТ ИЗ ДОКУМЕНТОВ:\nДокументы не найдены или не прошли порог релевантности."
                        else
                            "ВАЖНО: отвечай ТОЛЬКО на основе следующих документов. Не используй собственные знания.\n\nКОНТЕКСТ ИЗ ДОКУМЕНТОВ:\n$ctx"
                        "$ragFormatInstruction\n\n$contextBlock"
                    }
                    RagMode.RAG_PLUS_MODEL -> {
                        val ctx = if (_ragFilterEnabled.value) {
                            val (rewritten, filtered) = ragService.getContextFiltered(
                                text, "fixed",
                                topK = _ragTopK.value,
                                candidateK = _ragCandidateK.value,
                                minScore = _ragMinScore.value,
                                model = effectiveModel ?: "gpt-4o-mini",
                                rewriteEnabled = _ragRewriteEnabled.value
                            )
                            if (rewritten != text) {
                                val rewriteMsg = ChatMessage(content = rewritten, isFromUser = true, isQueryRewrite = true)
                                _messages.value = _messages.value.dropLast(1) + rewriteMsg + _messages.value.last()
                            }
                            filtered
                        } else {
                            ragService.getContext(text, "fixed")
                        }
                        val contextBlock = if (ctx.isBlank())
                            "КОНТЕКСТ ИЗ ДОКУМЕНТОВ:\nДокументы не найдены."
                        else
                            "КОНТЕКСТ ИЗ ДОКУМЕНТОВ (используй как приоритетный источник, можешь дополнять своими знаниями):\n$ctx"
                        val projectPrefix = if (_appSettings.value.projectPath.isNotBlank()) {
                            val projectPath = _appSettings.value.projectPath
                            val projectName = projectPath.trimEnd('/').substringAfterLast('/').ifBlank { "проекта" }
                            val readme = java.io.File(projectPath, "README.md")
                                .takeIf { it.exists() }
                                ?.readText()
                                ?.take(3000)
                                ?.let { "\n\nREADME проекта:\n$it" } ?: ""
                            "Ты ассистент разработчика проекта $projectName. Отвечай на вопросы о структуре, архитектуре и коде проекта, используя документацию.$readme\n\n"
                        } else ""
                        "$projectPrefix$ragFormatInstruction\n\n$contextBlock"
                    }
                    RagMode.MCP_TOOL -> {
                        if (isOllama) {
                            // Ollama doesn't support tool calling — inject RAG context directly
                            val ctx = ragService.getContext(text, "fixed")
                            val contextBlock = if (ctx.isBlank())
                                "КОНТЕКСТ ИЗ ДОКУМЕНТОВ:\nДокументы не найдены."
                            else
                                "КОНТЕКСТ ИЗ ДОКУМЕНТОВ (используй как приоритетный источник):\n$ctx"
                            "$ragFormatInstruction\n\n$contextBlock"
                        } else {
                            // Add search_docs as a synthetic tool for OpenAI-compatible models
                            openAiTools.add(
                                OpenAiTool(
                                    function = OpenAiFunction(
                                        name = "search_docs",
                                        description = "Поиск по локальному индексу документов. Используй когда нужно найти информацию из загруженных .md файлов.",
                                        parameters = json.parseToJsonElement(
                                            """{"type":"object","properties":{"query":{"type":"string","description":"Поисковый запрос"},"strategy":{"type":"string","enum":["fixed","structure"],"description":"Стратегия индексирования (по умолчанию fixed)"}},"required":["query"]}"""
                                        )
                                    )
                                )
                            )
                            ragFormatInstruction
                        }
                    }
                }

                // Add project tools in any non-Ollama mode when projectPath is configured
                if (!isOllama && _appSettings.value.projectPath.isNotBlank()) {
                    openAiTools.add(OpenAiTool(function = OpenAiFunction(
                        name = "git_branch",
                        description = "Возвращает список веток git в проекте.",
                        parameters = json.parseToJsonElement("""{"type":"object","properties":{}}""")
                    )))
                    openAiTools.add(OpenAiTool(function = OpenAiFunction(
                        name = "git_status",
                        description = "Возвращает статус изменённых файлов в проекте (git status --short).",
                        parameters = json.parseToJsonElement("""{"type":"object","properties":{}}""")
                    )))
                    openAiTools.add(OpenAiTool(function = OpenAiFunction(
                        name = "git_diff",
                        description = "Возвращает краткий diff изменений в проекте (git diff HEAD --stat).",
                        parameters = json.parseToJsonElement("""{"type":"object","properties":{}}""")
                    )))
                    openAiTools.add(OpenAiTool(function = OpenAiFunction(
                        name = "list_files",
                        description = "Возвращает список .kt файлов проекта.",
                        parameters = json.parseToJsonElement("""{"type":"object","properties":{}}""")
                    )))
                }

                McpLogger.log("Sending message with ${openAiTools.size} tools (RAG mode: ${_ragMode.value})")

                // Send message with tools (inject RAG context if needed)
                var result = openAiService.sendMessageWithTools(
                    text,
                    openAiTools,
                    _temperature.value,
                    effectiveModel,
                    ragSystemContext,
                    _maxTokens.value,
                    effectiveNumCtx
                )

                // Handle tool calls loop
                while (result.isSuccess && result.getOrNull()?.isToolCall == true) {
                    val chatResult = result.getOrNull()!!
                    val toolCalls = chatResult.toolCalls ?: break

                    McpLogger.log("Processing ${toolCalls.size} tool calls")

                    // Execute each tool call
                    val toolResults = mutableListOf<ToolResult>()
                    for (toolCall in toolCalls) {
                        val toolName = toolCall.function.name
                        val arguments = try {
                            json.decodeFromString<JsonObject>(toolCall.function.arguments)
                        } catch (e: Exception) {
                            null
                        }

                        McpLogger.log("Executing tool: $toolName")

                        // Handle synthetic search_docs tool
                        val resultContent = if (toolName == "search_docs") {
                            val query = arguments?.get("query")?.toString()?.trim('"') ?: text
                            val strategy = arguments?.get("strategy")?.toString()?.trim('"') ?: "fixed"
                            val ctx = ragService.getContext(query, strategy)
                            if (ctx.isBlank()) "Документы по запросу не найдены." else ctx
                        } else if (toolName in listOf("git_branch", "git_status", "git_diff", "list_files")) {
                            runProjectTool(toolName, _appSettings.value.projectPath)
                        } else {
                            val toolResult = mcpManager.callTool(toolName, arguments)
                            toolResult.fold(
                                onSuccess = { it.content.firstOrNull()?.text ?: "Success" },
                                onFailure = { "Error: ${it.message}" }
                            )
                        }

                        toolResults.add(ToolResult(toolCallId = toolCall.id, content = resultContent))
                        McpLogger.log("Tool $toolName result: ${resultContent.take(100)}")
                    }

                    // Continue conversation with tool results
                    result = openAiService.continueWithToolResults(
                        toolResults,
                        _temperature.value,
                        effectiveModel
                    )
                }

                _messages.value = _messages.value.dropLast(1)

                result.fold(
                    onSuccess = { chatResult ->
                        val aiMessage = ChatMessage(
                            content = chatResult.content ?: "",
                            isFromUser = false,
                            metadata = chatResult.metadata
                        )
                        _messages.value = _messages.value + aiMessage

                        val settings = _appSettings.value
                        val isNotOllama = settings.provider != ru.mike.study.studyai.config.LlmProvider.OLLAMA
                        if (isNotOllama && settings.contextStrategyEnabled) {
                            when (_strategy.value) {
                                ContextStrategy.SUMMARY -> checkAndSummarizeIfNeeded()
                                ContextStrategy.STICKY_FACTS -> extractFactsIfNeeded()
                                ContextStrategy.MEMORY_LAYERS -> extractMemoryLayersIfNeeded()
                                else -> { /* No post-processing */ }
                            }
                        }
                        if (isNotOllama && settings.taskStateExtractionEnabled) {
                            extractTaskStateIfNeeded()
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
            } catch (e: Exception) {
                _messages.value = _messages.value.dropLast(1)
                val errorMessage = ChatMessage(
                    content = "Error: ${e.message ?: "Unknown error"}",
                    isFromUser = false
                )
                _messages.value = _messages.value + errorMessage
            }

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
        val summaryResult = openAiService.checkAndSummarize(effectiveModel)

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
        val factsResult = openAiService.extractFacts(effectiveModel)
        if (factsResult != null) {
            _facts.value = factsResult.facts
            openAiService.setFacts(_facts.value)
            println("Context management: Updated facts (${_facts.value.size} facts)")
        }
    }

    private suspend fun extractMemoryLayersIfNeeded() {
        // Extract and update memory layers after every assistant response
        val result = openAiService.extractMemoryUpdates(effectiveModel)
        if (result.success) {
            println("Context management: Memory layers updated")

            // Determine phase: from LLM response or default to current/PLANNING
            val phase = if (result.phase != null) {
                try {
                    TaskPhase.valueOf(result.phase)
                } catch (e: Exception) {
                    _currentPhase.value ?: TaskPhase.PLANNING
                }
            } else {
                _currentPhase.value ?: TaskPhase.PLANNING
            }

            _currentPhase.value = phase

            // Update last assistant message with phase info
            val currentMessages = _messages.value
            if (currentMessages.isNotEmpty()) {
                val lastMsg = currentMessages.last()
                if (!lastMsg.isFromUser) {
                    val updatedMsg = lastMsg.copy(
                        phase = phase,
                        phaseCompleted = result.phaseCompleted
                    )
                    // Create new list to trigger StateFlow update
                    _messages.value = currentMessages.dropLast(1) + updatedMsg

                    if (result.phaseCompleted) {
                        _awaitingPhaseConfirmation.value = true
                    }

                    println("Context management: Updated message with Phase = $phase, completed = ${result.phaseCompleted}")
                }
            }
        }
    }

    private suspend fun extractTaskStateIfNeeded() {
        val result = openAiService.extractTaskState(effectiveModel)
        if (result != null) {
            _taskState.value = result
            openAiService.setTaskState(result)
            println("TaskState updated: goal=${result.goal}, clarifications=${result.clarifications.size}, constraints=${result.constraints.size}")
        }
    }

    private fun runProjectTool(toolName: String, projectPath: String): String {
        if (projectPath.isBlank()) return "Путь к проекту не задан."
        return try {
            val (cmd, args) = when (toolName) {
                "git_branch" -> "git" to listOf("-C", projectPath, "branch", "-a")
                "git_status" -> "git" to listOf("-C", projectPath, "status", "--short")
                "git_diff"   -> "git" to listOf("-C", projectPath, "diff", "HEAD", "--stat")
                "list_files" -> "find" to listOf(projectPath, "-name", "*.kt", "-not", "-path", "*/build/*")
                else -> return "Неизвестный инструмент: $toolName"
            }
            val process = ProcessBuilder(listOf(cmd) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifBlank { "(пусто)" }
        } catch (e: Exception) {
            "Ошибка выполнения $toolName: ${e.message}"
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
