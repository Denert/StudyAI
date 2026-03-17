package ru.mike.study.studyai.rag.index

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.mike.study.studyai.rag.RagLogger

class EmbeddingService(private val apiKey: String) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    @Serializable
    private data class EmbeddingRequest(
        val input: List<String>,
        val model: String = "text-embedding-3-small"
    )

    @Serializable
    private data class EmbeddingResponse(
        val data: List<EmbeddingData>
    )

    @Serializable
    private data class EmbeddingData(
        val embedding: List<Float>,
        val index: Int
    )

    suspend fun embedBatch(texts: List<String>): List<List<Float>> {
        val results = mutableListOf<Pair<Int, List<Float>>>()

        texts.chunked(100).forEachIndexed { batchIndex, batch ->
            RagLogger.log("Запрос эмбеддингов: батч $batchIndex, ${batch.size} текстов")

            val httpResponse = client.post("https://api.openai.com/v1/embeddings") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(EmbeddingRequest(input = batch))
            }

            val statusCode = httpResponse.status.value
            RagLogger.log("HTTP статус: $statusCode")

            if (statusCode != 200) {
                val body = httpResponse.bodyAsText()
                RagLogger.log("Ошибка ответа: $body")
                error("Embeddings API вернул $statusCode: $body")
            }

            val rawBody = httpResponse.bodyAsText()
            RagLogger.log("Длина ответа: ${rawBody.length} символов")
            RagLogger.log("Начало ответа: ${rawBody.take(200)}")

            val response = try {
                json.decodeFromString<EmbeddingResponse>(rawBody)
            } catch (e: Exception) {
                RagLogger.logError("Ошибка парсинга JSON", e)
                RagLogger.log("Полный ответ: $rawBody")
                throw e
            }

            RagLogger.log("Получено эмбеддингов: ${response.data.size}")
            response.data.forEach { data ->
                results.add((batchIndex * 100 + data.index) to data.embedding)
            }
        }

        return results.sortedBy { it.first }.map { it.second }
    }

    suspend fun embed(text: String): List<Float> = embedBatch(listOf(text)).first()
}
