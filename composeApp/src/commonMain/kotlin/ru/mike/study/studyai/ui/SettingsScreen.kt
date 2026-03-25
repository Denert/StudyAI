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
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var provider by remember(settings) { mutableStateOf(settings.provider) }
    var ollamaBaseUrl by remember(settings) { mutableStateOf(settings.ollamaBaseUrl) }
    var ollamaChatModel by remember(settings) { mutableStateOf(settings.ollamaChatModel) }
    var ollamaEmbeddingModel by remember(settings) { mutableStateOf(settings.ollamaEmbeddingModel) }
    var systemPromptEnabled by remember(settings) { mutableStateOf(settings.systemPromptEnabled) }
    var invariantsEnabled by remember(settings) { mutableStateOf(settings.invariantsEnabled) }
    var profileMemoryEnabled by remember(settings) { mutableStateOf(settings.profileMemoryEnabled) }

    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }

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
                                supportingText = { Text("Пример: qwen2.5-coder:1.5b") },
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

                        OutlinedTextField(
                            value = ollamaEmbeddingModel,
                            onValueChange = { ollamaEmbeddingModel = it },
                            label = { Text("Модель для эмбеддингов (RAG)") },
                            supportingText = { Text("Установите: ollama pull nomic-embed-text") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

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
                }

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
                                ollamaBaseUrl = ollamaBaseUrl,
                                ollamaChatModel = ollamaChatModel,
                                ollamaEmbeddingModel = ollamaEmbeddingModel,
                                systemPromptEnabled = systemPromptEnabled,
                                invariantsEnabled = invariantsEnabled,
                                profileMemoryEnabled = profileMemoryEnabled
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
