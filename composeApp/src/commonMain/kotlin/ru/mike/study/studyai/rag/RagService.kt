package ru.mike.study.studyai.rag

import ru.mike.study.studyai.rag.chunking.FixedSizeChunker
import ru.mike.study.studyai.rag.chunking.StructureChunker
import ru.mike.study.studyai.rag.index.EmbeddingService
import ru.mike.study.studyai.rag.index.VectorStore
import java.io.File

class RagService(apiKey: String) {

    val docsDir = File(System.getProperty("user.home") + "/.studyai/rag-docs").also { it.mkdirs() }
    private val embeddingService = EmbeddingService(apiKey)
    private val vectorStore = VectorStore()
    private val fixedChunker = FixedSizeChunker()
    private val structureChunker = StructureChunker()

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
        val files = docsDir.listFiles { f -> f.extension == "md" } ?: emptyArray()
        if (files.isEmpty()) return IndexStats(0, 0, strategy)

        val chunker = if (strategy == "fixed") fixedChunker else structureChunker
        val allChunks = mutableListOf<RagChunk>()

        for (file in files) {
            onProgress("Читаю: ${file.name}")
            RagLogger.log("Читаю файл: ${file.name} (${file.length()} байт)")
            val text = file.readText()
            RagLogger.log("Длина текста: ${text.length} символов")
            val chunks = chunker.chunk(text, file.name)
            RagLogger.log("Чанков из ${file.name}: ${chunks.size}")
            allChunks.addAll(chunks)
        }

        RagLogger.log("Всего чанков: ${allChunks.size}")
        onProgress("Генерирую эмбеддинги (${allChunks.size} чанков)...")

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

    fun getChunks(strategy: String): List<RagChunk> = vectorStore.load(strategy)

    fun hasIndex(strategy: String): Boolean = vectorStore.hasIndex(strategy)

    fun getDocFiles(): List<String> =
        docsDir.listFiles { f -> f.extension == "md" }?.map { it.name } ?: emptyList()
}
