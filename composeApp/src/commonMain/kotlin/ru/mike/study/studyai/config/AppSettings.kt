package ru.mike.study.studyai.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class LlmProvider { OPENAI, OLLAMA }

@Serializable
data class AppSettings(
    val provider: LlmProvider = LlmProvider.OPENAI,
    val ollamaBaseUrl: String = "http://localhost:11434",
    val ollamaChatModel: String = "llama3.1:8b",
    val ollamaEmbeddingModel: String = "nomic-embed-text",
    val systemPromptEnabled: Boolean = true,
    val invariantsEnabled: Boolean = true,
    val profileMemoryEnabled: Boolean = true
) {
    val effectiveBaseUrl: String get() = when (provider) {
        LlmProvider.OPENAI -> "https://api.openai.com"
        LlmProvider.OLLAMA -> ollamaBaseUrl
    }

    val effectiveEmbeddingModel: String get() = when (provider) {
        LlmProvider.OPENAI -> "text-embedding-3-small"
        LlmProvider.OLLAMA -> ollamaEmbeddingModel
    }

    val providerPrefix: String get() = when (provider) {
        LlmProvider.OPENAI -> "openai"
        LlmProvider.OLLAMA -> "ollama"
    }
}

object AppSettingsStore {
    private val settingsFile = File(System.getProperty("user.home") + "/.studyai/settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppSettings = try {
        if (settingsFile.exists()) json.decodeFromString(settingsFile.readText())
        else AppSettings()
    } catch (e: Exception) {
        AppSettings()
    }

    fun save(settings: AppSettings) {
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), settings))
    }
}
