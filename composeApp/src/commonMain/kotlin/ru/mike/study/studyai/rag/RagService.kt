package ru.mike.study.studyai.rag

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.mike.study.studyai.config.AppSettings
import ru.mike.study.studyai.config.LlmProvider
import ru.mike.study.studyai.config.OllamaManager
import ru.mike.study.studyai.rag.chunking.FixedSizeChunker
import ru.mike.study.studyai.rag.chunking.StructureChunker
import ru.mike.study.studyai.rag.index.EmbeddingService
import ru.mike.study.studyai.rag.index.VectorStore
import java.io.File

class RagService(private val settings: AppSettings, openAiApiKey: String) {

    private val effectiveApiKey = when (settings.provider) {
        LlmProvider.OLLAMA -> ""
        else -> openAiApiKey // works for both OPENAI and LOCAL (caller passes correct key)
    }

    val docsDir = File(System.getProperty("user.home") + "/.studyai/rag-docs").also { it.mkdirs() }
    private val embeddingService = EmbeddingService(
        apiKey = effectiveApiKey,
        baseUrl = settings.effectiveBaseUrl,
        embeddingModel = settings.effectiveEmbeddingModel
    )
    private val vectorStore = VectorStore(providerPrefix = settings.providerPrefix)
    private val fixedChunker = FixedSizeChunker()
    private val structureChunker = StructureChunker()

    private val chatEndpoint = "${settings.effectiveBaseUrl}/v1/chat/completions"
    private val rewriteApiKey = effectiveApiKey
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

    @Serializable
    private data class RewriteMessage(val role: String, val content: String)
    @Serializable
    private data class RewriteRequest(val model: String, val messages: List<RewriteMessage>, val max_tokens: Int = 120)
    @Serializable
    private data class RewriteChoice(val message: RewriteMessage)
    @Serializable
    private data class RewriteResponse(val choices: List<RewriteChoice>)

    data class IndexStats(
        val fileCount: Int,
        val chunkCount: Int,
        val strategy: String,
        val avgChunkSize: Int = 0,
        val minChunkSize: Int = 0,
        val maxChunkSize: Int = 0
    )

    suspend fun indexDocuments(
        strategy: String,
        onProgress: (String) -> Unit = {}
    ): IndexStats {
        val files = docsDir.listFiles { f -> f.extension == "md" || f.extension == "pdf" }
            ?: emptyArray()
        if (files.isEmpty()) return IndexStats(0, 0, strategy)

        val chunker = if (strategy == "fixed") fixedChunker else structureChunker
        val allChunks = mutableListOf<RagChunk>()

        for (file in files) {
            onProgress("Читаю: ${file.name}")
            RagLogger.log("Читаю файл: ${file.name} (${file.length()} байт)")
            val text = when (file.extension.lowercase()) {
                "pdf" -> {
                    onProgress("Извлекаю текст из PDF: ${file.name}")
                    extractPdfText(file)
                }
                else -> file.readText()
            }
            if (text.isBlank()) {
                RagLogger.log("Пропускаю ${file.name}: пустой текст")
                onProgress("⚠ Пропускаю ${file.name}: не удалось извлечь текст")
                continue
            }
            RagLogger.log("Длина текста: ${text.length} символов")
            val chunks = chunker.chunk(text, file.name)
            RagLogger.log("Чанков из ${file.name}: ${chunks.size}")
            allChunks.addAll(chunks)
        }

        RagLogger.log("Всего чанков: ${allChunks.size}")
        onProgress("Генерирую эмбеддинги (${allChunks.size} чанков)...")

        if (settings.provider == LlmProvider.OLLAMA) {
            onProgress("Проверяю модель ${settings.effectiveEmbeddingModel}...")
            withContext(Dispatchers.IO) {
                OllamaManager.ensureModelAvailable(settings.ollamaBaseUrl, settings.effectiveEmbeddingModel)
            }
        }

        val embeddings = try {
            embeddingService.embedBatch(allChunks.map { it.content })
        } catch (e: Exception) {
            RagLogger.logError("Ошибка при генерации эмбеддингов", e)
            onProgress("❌ Ошибка эмбеддингов: ${e.message}")
            throw e
        }

        RagLogger.log("Получено эмбеддингов: ${embeddings.size}")
        val chunksWithEmbeddings = allChunks.mapIndexed { i, chunk ->
            chunk.copy(embedding = embeddings[i])
        }

        RagLogger.log("Сохраняю индекс...")
        vectorStore.save(chunksWithEmbeddings, strategy)
        onProgress("✓ Индекс сохранён")

        return IndexStats(
            fileCount = files.size,
            chunkCount = chunksWithEmbeddings.size,
            strategy = strategy,
            avgChunkSize = if (chunksWithEmbeddings.isEmpty()) 0
            else chunksWithEmbeddings.map { it.charCount }.average().toInt(),
            minChunkSize = chunksWithEmbeddings.minOfOrNull { it.charCount } ?: 0,
            maxChunkSize = chunksWithEmbeddings.maxOfOrNull { it.charCount } ?: 0
        )
    }

    suspend fun search(query: String, strategy: String, topK: Int = 5): List<Pair<RagChunk, Float>> {
        if (settings.provider == LlmProvider.OLLAMA) {
            withContext(Dispatchers.IO) {
                OllamaManager.ensureModelAvailable(settings.ollamaBaseUrl, settings.effectiveEmbeddingModel)
            }
        }
        val queryEmbedding = embeddingService.embed(query)
        return vectorStore.search(queryEmbedding, strategy, topK)
    }

    suspend fun getContext(query: String, strategy: String, topK: Int = 5): String {
        val results = search(query, strategy, topK)
        if (results.isEmpty()) return ""
        return results.joinToString("\n\n---\n\n") { (chunk, score) ->
            "[${chunk.source} / ${chunk.title}] (релевантность: ${"%.2f".format(score)})\n${chunk.content}"
        }
    }

    suspend fun rewriteQuery(query: String, model: String = "gpt-4o-mini"): String {
        return try {
            val response = httpClient.post(chatEndpoint) {
                header(HttpHeaders.Authorization, "Bearer $rewriteApiKey")
                contentType(ContentType.Application.Json)
                setBody(RewriteRequest(
                    model = model,
                    messages = listOf(
                        RewriteMessage("system", "Ты оптимизатор поисковых запросов. Перефразируй вопрос пользователя в краткий поисковый запрос, насыщенный ключевыми словами. Верни только запрос, без пояснений."),
                        RewriteMessage("user", query)
                    )
                ))
            }
            val body = response.bodyAsText()
            val parsed = json.decodeFromString<RewriteResponse>(body)
            parsed.choices.firstOrNull()?.message?.content?.trim() ?: query
        } catch (e: Exception) {
            RagLogger.logError("Ошибка переформулировки запроса", e)
            query
        }
    }

    suspend fun getContextFiltered(
        query: String,
        strategy: String,
        topK: Int = 5,
        candidateK: Int = 20,
        minScore: Float = 0.3f,
        model: String = "gpt-4o-mini",
        rewriteEnabled: Boolean = true
    ): Pair<String, String> {
        val rewritten = if (rewriteEnabled) rewriteQuery(query, model) else query
        RagLogger.log(if (rewriteEnabled) "Переформулировано: \"$rewritten\"" else "Переформулировка отключена, запрос: \"$rewritten\"")
        val queryEmbedding = embeddingService.embed(rewritten)
        val results = vectorStore.searchWithFilter(queryEmbedding, strategy, candidateK, topK, minScore)
        RagLogger.log("Результатов после фильтра (minScore=$minScore): ${results.size} из $candidateK кандидатов")
        if (results.isEmpty()) return rewritten to ""
        val context = results.joinToString("\n\n---\n\n") { (chunk, score) ->
            "[${chunk.source} / ${chunk.title}] (релевантность: ${"%.2f".format(score)})\n${chunk.content}"
        }
        return rewritten to context
    }

    fun getChunks(strategy: String): List<RagChunk> = vectorStore.load(strategy)

    fun hasIndex(strategy: String): Boolean = vectorStore.hasIndex(strategy)

    fun getDocFiles(): List<String> =
        docsDir.listFiles { f -> f.extension == "md" || f.extension == "pdf" }
            ?.sortedBy { it.name }
            ?.map { it.name } ?: emptyList()
}
