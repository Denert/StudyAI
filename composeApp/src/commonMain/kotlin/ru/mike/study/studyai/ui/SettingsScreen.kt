package ru.mike.study.studyai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import ru.mike.study.studyai.config.ApiConfig
import ru.mike.study.studyai.config.AppSettings
import ru.mike.study.studyai.config.LlmProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    openAiApiKey: String,
    localApiKey: String,
    githubTokenSet: Boolean = false,
    prReviewStatus: String = "",
    onSave: (AppSettings) -> Unit,
    onLocalBaseUrlChange: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var provider by remember(settings) { mutableStateOf(settings.provider) }
    var ollamaBaseUrl by remember(settings) { mutableStateOf(settings.ollamaBaseUrl) }
    var ollamaChatModel by remember(settings) { mutableStateOf(settings.ollamaChatModel) }
    var ollamaEmbeddingModel by remember(settings) { mutableStateOf(settings.ollamaEmbeddingModel) }
    var localBaseUrl by remember(settings) { mutableStateOf(settings.localBaseUrl) }
    var localChatModel by remember(settings) { mutableStateOf(settings.localChatModel) }
    var localEmbeddingModel by remember(settings) { mutableStateOf(settings.localEmbeddingModel) }
    var projectPath by remember(settings) { mutableStateOf(settings.projectPath) }
    var systemPromptEnabled by remember(settings) { mutableStateOf(settings.systemPromptEnabled) }
    var invariantsEnabled by remember(settings) { mutableStateOf(settings.invariantsEnabled) }
    var profileMemoryEnabled by remember(settings) { mutableStateOf(settings.profileMemoryEnabled) }
    var contextStrategyEnabled by remember(settings) { mutableStateOf(settings.contextStrategyEnabled) }
    var taskStateExtractionEnabled by remember(settings) { mutableStateOf(settings.taskStateExtractionEnabled) }
    var prReviewEnabled by remember(settings) { mutableStateOf(settings.prReviewEnabled) }

    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var showEmbeddingDropdown by remember { mutableStateOf(false) }
    var showLocalModelDropdown by remember { mutableStateOf(false) }
    var showLocalEmbeddingDropdown by remember { mutableStateOf(false) }
    val localEmbeddingModels = listOf("nomic-embed-text", "mxbai-embed-large")
    var localHealthStatus by remember { mutableStateOf<Boolean?>(null) }
    var isCheckingHealth by remember { mutableStateOf(false) }

    fun loadModels() {
        scope.launch {
            isLoadingModels = true
            modelsError = ""
            val models = ApiConfig.fetchOllamaModels(ollamaBaseUrl)
            if (models.isEmpty()) {
                modelsError = "Модели не найдены. Проверьте, что Ollama запущена по адресу $ollamaBaseUrl"
            } else {
                availableModels = models
            }
            isLoadingModels = false
        }
    }

    fun loadLocalModels() {
        scope.launch {
            isLoadingModels = true
            modelsError = ""
            val models = ApiConfig.fetchLocalModels(localBaseUrl, localApiKey)
            if (models.isEmpty()) {
                modelsError = "Модели не найдены. Проверьте URL и ключ."
            } else {
                availableModels = models
            }
            isLoadingModels = false
        }
    }

    fun checkHealth() {
        scope.launch {
            isCheckingHealth = true
            localHealthStatus = ApiConfig.checkLocalHealth(localBaseUrl, localApiKey)
            isCheckingHealth = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Настройки", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // Provider toggle
                Text("Провайдер LLM", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == LlmProvider.OPENAI,
                        onClick = { provider = LlmProvider.OPENAI },
                        label = { Text("OpenAI") }
                    )
                    FilterChip(
                        selected = provider == LlmProvider.OLLAMA,
                        onClick = {
                            provider = LlmProvider.OLLAMA
                            if (availableModels.isEmpty()) loadModels()
                        },
                        label = { Text("Ollama (локальная)") }
                    )
                    FilterChip(
                        selected = provider == LlmProvider.LOCAL,
                        onClick = { provider = LlmProvider.LOCAL },
                        label = { Text("Local (Tunnel)") }
                    )
                }

                HorizontalDivider()

                // Provider-specific settings
                when (provider) {
                    LlmProvider.OPENAI -> {
                        Text("API Key", style = MaterialTheme.typography.labelMedium)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (openAiApiKey.isNotBlank())
                                    "${openAiApiKey.take(12)}…"
                                else "Не задан (проверьте local.properties)",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (openAiApiKey.isNotBlank())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    LlmProvider.OLLAMA -> {
                        OutlinedTextField(
                            value = ollamaBaseUrl,
                            onValueChange = { ollamaBaseUrl = it },
                            label = { Text("Ollama URL") },
                            supportingText = { Text("По умолчанию: http://localhost:11434") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { loadModels() }) {
                                    if (isLoadingModels) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = "Загрузить модели")
                                    }
                                }
                            }
                        )

                        if (modelsError.isNotBlank()) {
                            Text(modelsError, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }

                        // Chat model
                        ExposedDropdownMenuBox(
                            expanded = showModelDropdown,
                            onExpandedChange = {
                                if (availableModels.isNotEmpty()) showModelDropdown = it
                            }
                        ) {
                            OutlinedTextField(
                                value = ollamaChatModel,
                                onValueChange = { ollamaChatModel = it },
                                label = { Text("Модель для чата") },
                                supportingText = { Text("Пример: llama3.1:8b") },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModelDropdown) }
                            )
                            if (availableModels.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = showModelDropdown,
                                    onDismissRequest = { showModelDropdown = false }
                                ) {
                                    availableModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                ollamaChatModel = model
                                                showModelDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Embedding model
                        ExposedDropdownMenuBox(
                            expanded = showEmbeddingDropdown,
                            onExpandedChange = {
                                if (availableModels.isNotEmpty()) showEmbeddingDropdown = it
                            }
                        ) {
                            OutlinedTextField(
                                value = ollamaEmbeddingModel,
                                onValueChange = { ollamaEmbeddingModel = it },
                                label = { Text("Модель для эмбеддингов (RAG)") },
                                supportingText = { Text("Пример: nomic-embed-text") },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEmbeddingDropdown) }
                            )
                            if (availableModels.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = showEmbeddingDropdown,
                                    onDismissRequest = { showEmbeddingDropdown = false }
                                ) {
                                    availableModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                ollamaEmbeddingModel = model
                                                showEmbeddingDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Warning about re-indexing
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "⚠ При смене провайдера RAG-индекс нужно пересоздать заново — откройте RAG → Документы → Индексировать. Старые индексы OpenAI сохранятся.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    LlmProvider.LOCAL -> {
                        // API Key (read-only from local.properties)
                        Text("API Key (LOCAL_API_KEY)", style = MaterialTheme.typography.labelMedium)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (localApiKey.isNotBlank())
                                    "${localApiKey.take(8)}…"
                                else "Не задан (проверьте local.properties → LOCAL_API_KEY)",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (localApiKey.isNotBlank())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        }

                        // Base URL + Health check
                        OutlinedTextField(
                            value = localBaseUrl,
                            onValueChange = {
                                localBaseUrl = it
                                localHealthStatus = null
                                onLocalBaseUrlChange(it)
                            },
                            label = { Text("URL туннеля") },
                            supportingText = { Text("Пример: https://xxxx.trycloudflare.com") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { if (localBaseUrl.isNotBlank()) checkHealth() }) {
                                    if (isCheckingHealth) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = "Проверить /health")
                                    }
                                }
                            }
                        )

                        localHealthStatus?.let { ok ->
                            Text(
                                if (ok) "✓ Сервер доступен" else "✗ Сервер недоступен",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }

                        if (modelsError.isNotBlank()) {
                            Text(modelsError, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }

                        // Chat model dropdown
                        ExposedDropdownMenuBox(
                            expanded = showLocalModelDropdown,
                            onExpandedChange = {
                                if (availableModels.isNotEmpty()) showLocalModelDropdown = it
                            }
                        ) {
                            OutlinedTextField(
                                value = localChatModel,
                                onValueChange = { localChatModel = it },
                                label = { Text("Модель для чата") },
                                supportingText = { Text("Введите вручную или загрузите список") },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                                singleLine = true,
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { if (localBaseUrl.isNotBlank()) loadLocalModels() }) {
                                            if (isLoadingModels) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = "Загрузить модели")
                                            }
                                        }
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLocalModelDropdown)
                                    }
                                }
                            )
                            if (availableModels.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = showLocalModelDropdown,
                                    onDismissRequest = { showLocalModelDropdown = false }
                                ) {
                                    availableModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                localChatModel = model
                                                showLocalModelDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Embedding model dropdown
                        ExposedDropdownMenuBox(
                            expanded = showLocalEmbeddingDropdown,
                            onExpandedChange = { showLocalEmbeddingDropdown = it }
                        ) {
                            OutlinedTextField(
                                value = localEmbeddingModel,
                                onValueChange = { localEmbeddingModel = it },
                                label = { Text("Модель для эмбеддингов (RAG)") },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLocalEmbeddingDropdown) }
                            )
                            ExposedDropdownMenu(
                                expanded = showLocalEmbeddingDropdown,
                                onDismissRequest = { showLocalEmbeddingDropdown = false }
                            ) {
                                localEmbeddingModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            localEmbeddingModel = model
                                            showLocalEmbeddingDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Project
                Text("Проект (для /help и RAG)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = projectPath,
                    onValueChange = { projectPath = it },
                    label = { Text("Путь к проекту") },
                    supportingText = { Text("Пример: /Users/user/Projects/MyApp") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                HorizontalDivider()

                // Memory / prompts
                Text("Память и промпты", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("system_prompt.md", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = systemPromptEnabled, onCheckedChange = { systemPromptEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("invariants.md", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = invariantsEnabled, onCheckedChange = { invariantsEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Профиль пользователя", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = profileMemoryEnabled, onCheckedChange = { profileMemoryEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Обновление контекста (+1 запрос)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = contextStrategyEnabled, onCheckedChange = { contextStrategyEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Извлечение состояния задачи (+1 запрос)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = taskStateExtractionEnabled, onCheckedChange = { taskStateExtractionEnabled = it })
                }

                HorizontalDivider()

                // PR Review via Webhook
                Text("PR Review (Webhook)", style = MaterialTheme.typography.titleSmall)

                // GitHub token status
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (githubTokenSet) "GitHub Token: задан" else "GitHub Token: не задан (добавьте GITHUB_TOKEN в local.properties)",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (githubTokenSet)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Следить за PR", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Автоматически ревьюить новые PR в проекте из «Путь к проекту»",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = prReviewEnabled, onCheckedChange = { prReviewEnabled = it })
                }

                if (prReviewEnabled) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "StudyAI установит git hook в проект и будет автоматически ревьюить PR при каждом git push",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (prReviewStatus.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = prReviewStatus,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(
                            AppSettings(
                                provider = provider,
                                projectPath = projectPath,
                                ollamaBaseUrl = ollamaBaseUrl,
                                ollamaChatModel = ollamaChatModel,
                                ollamaEmbeddingModel = ollamaEmbeddingModel,
                                localBaseUrl = localBaseUrl,
                                localChatModel = localChatModel,
                                localEmbeddingModel = localEmbeddingModel,
                                systemPromptEnabled = systemPromptEnabled,
                                invariantsEnabled = invariantsEnabled,
                                profileMemoryEnabled = profileMemoryEnabled,
                                contextStrategyEnabled = contextStrategyEnabled,
                                taskStateExtractionEnabled = taskStateExtractionEnabled,
                                prReviewEnabled = prReviewEnabled
                            )
                        )
                        onDismiss()
                    }) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}
