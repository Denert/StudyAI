package ru.mike.study.studyai.github

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.mike.study.studyai.config.AppSettings
import ru.mike.study.studyai.config.LlmProvider
import ru.mike.study.studyai.rag.RagService

class PrReviewService(
    private val gitHubService: GitHubService,
    private val ragService: RagService,
    private val appSettings: AppSettings,
    private val apiKey: String,
    private val reviewedPrStore: ReviewedPrStore,
    private val onStatusUpdate: (String) -> Unit = {},
    private val onChatMessage: (String) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
        }
    }

    @Serializable
    private data class ReviewMessage(val role: String, val content: String)

    @Serializable
    private data class ReviewRequest(
        val model: String,
        val messages: List<ReviewMessage>,
        @SerialName("max_tokens") val maxTokens: Int = 4096,
        val temperature: Float = 0.3f
    )

    @Serializable
    private data class ReviewChoice(val message: ReviewMessage)

    @Serializable
    private data class ReviewResponse(
        val choices: List<ReviewChoice>? = null,
        val error: ReviewError? = null
    )

    @Serializable
    private data class ReviewError(val message: String? = null)

    suspend fun reviewByBranch(ownerRepo: String, branch: String, sha: String) {
        val parts = ownerRepo.split("/")
        if (parts.size != 2) return
        val (owner, repo) = parts
        val pr = gitHubService.findPrByBranch(owner, repo, branch)
        if (pr == null) {
            val nopr = "ℹ️ Нет открытого PR для ветки **$branch** в $ownerRepo"
            println("PrReviewService: no open PR found for branch $branch")
            onStatusUpdate(nopr)
            onChatMessage(nopr)
            return
        }
        reviewPr(pr.number, "synchronize", ownerRepo, pr.head.sha)
    }

    suspend fun reviewPr(prNumber: Int, action: String, ownerRepo: String, headSha: String) {
        val parts = ownerRepo.split("/")
        if (parts.size != 2) {
            println("PrReviewService: Invalid repo format: $ownerRepo")
            return
        }
        val owner = parts[0]
        val repo = parts[1]

        val commentExists = gitHubService.hasAiReviewComment(owner, repo, prNumber)
        if (commentExists) {
            println("PrReviewService: PR #$prNumber comment already exists, skipping silently")
            reviewedPrStore.markReviewed(ownerRepo, prNumber, headSha)
            return
        }
        reviewedPrStore.clearReview(ownerRepo, prNumber)

        println("PrReviewService: Reviewing PR #$prNumber ($action) in $ownerRepo @ $headSha")
        val startMsg = "🤖 Начинаю ревью PR #$prNumber в $ownerRepo..."
        onStatusUpdate(startMsg)
        onChatMessage(startMsg)

        val prInfo = gitHubService.getPrInfo(owner, repo, prNumber)
        val prFiles = gitHubService.getPrFiles(owner, repo, prNumber)

        if (prFiles.isEmpty()) {
            println("PrReviewService: No files in PR #$prNumber")
            onStatusUpdate("PR #$prNumber: нет изменённых файлов")
            onChatMessage("⚠️ PR #$prNumber: нет изменённых файлов")
            return
        }

        onChatMessage("📂 PR #$prNumber «${prInfo?.title ?: ""}» — изменено файлов: ${prFiles.size}")

        // Build diff text, truncate at 20000 chars
        val diffText = buildString {
            for (file in prFiles) {
                append("### ${file.filename} (${file.status}, +${file.additions}/-${file.deletions})\n")
                if (file.patch != null) {
                    append("```diff\n${file.patch}\n```\n\n")
                }
                if (length > 20_000) {
                    append("\n...(diff truncated, showing first files)...")
                    break
                }
            }
        }

        // RAG: build a rich query from PR title + description + file paths + extracted identifiers from diff
        onStatusUpdate("Поиск документации по изменениям...")
        val identifiersFromDiff = Regex("[A-Z][a-zA-Z0-9]{3,}").findAll(diffText)
            .map { it.value }.distinct().take(20).joinToString(" ")
        val ragQuery = buildString {
            prInfo?.title?.let { append("$it ") }
            prInfo?.body?.take(200)?.let { append("$it ") }
            append(prFiles.map { it.filename }.joinToString(" "))
            if (identifiersFromDiff.isNotBlank()) append(" $identifiersFromDiff")
        }.take(500)
        val ragContext = try {
            ragService.getContext(ragQuery, "fixed", topK = 6).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("PrReviewService: RAG search failed: ${e.message}")
            null
        }

        // Build prompt
        val systemPrompt = buildString {
            append("Ты опытный code reviewer для Kotlin Multiplatform / Android проекта. ")
            append("Твоя задача — дать развёрнутое, конкретное ревью на русском языке. ")
            append("По каждой проблеме указывай файл, строку или имя функции. ")
            append("Не ограничивай себя — пиши столько замечаний, сколько найдёшь. ")
            if (ragContext != null) {
                append("\n\nДОКУМЕНТАЦИЯ ПРОЕКТА (используй для проверки соответствия архитектуре и соглашениям):\n$ragContext")
            }
        }

        val userPrompt = buildString {
            append("## PR #$prNumber: ${prInfo?.title ?: "без названия"}\n")
            prInfo?.body?.takeIf { it.isNotBlank() }?.let { append("**Описание:** $it\n\n") }
            append("**Изменённые файлы:** ${prFiles.joinToString(", ") { it.filename }}\n\n")
            append("## DIFF\n$diffText\n\n")
            append("---\n")
            append("Напиши подробное ревью по следующим разделам. ")
            append("Для каждого замечания указывай файл и, если возможно, функцию или строку. ")
            append("Если в разделе замечаний нет — напиши \"Замечаний нет\".\n\n")
            append("### 🐛 Потенциальные баги и ошибки\n")
            append("### 🏗 Архитектура и соответствие соглашениям проекта\n")
            append("### 🔒 Безопасность и граничные случаи\n")
            append("### 💡 Улучшения и рекомендации\n")
        }

        val effectiveModel = when (appSettings.provider) {
            LlmProvider.OLLAMA -> appSettings.ollamaChatModel
            LlmProvider.LOCAL -> appSettings.localChatModel
            LlmProvider.OPENAI -> "gpt-4o-mini"
        }

        onStatusUpdate("Анализ моделью $effectiveModel...")
        onChatMessage("⚙️ Отправляю на анализ модели **$effectiveModel**...")

        val reviewText = try {
            val response = client.post("${appSettings.effectiveBaseUrl}/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ReviewRequest(
                    model = effectiveModel,
                    messages = listOf(
                        ReviewMessage("system", systemPrompt),
                        ReviewMessage("user", userPrompt)
                    )
                ))
            }
            val body = json.decodeFromString<ReviewResponse>(response.bodyAsText())
            body.error?.message?.let { "Ошибка модели: $it" }
                ?: body.choices?.firstOrNull()?.message?.content
                ?: "Пустой ответ от модели"
        } catch (e: Exception) {
            println("PrReviewService: LLM call failed: ${e.message}")
            "Ошибка при вызове модели: ${e.message}"
        }

        val comment = buildString {
            append("## 🤖 AI Code Review\n\n")
            append(reviewText)
            append("\n\n---\n")
            append("_Модель: $effectiveModel | Файлов: ${prFiles.size}_")
        }

        onStatusUpdate("Публикация ревью для PR #$prNumber...")
        val posted = gitHubService.postComment(owner, repo, prNumber, comment)

        if (posted) {
            reviewedPrStore.markReviewed(ownerRepo, prNumber, headSha)
            onStatusUpdate("✅ Ревью опубликовано — PR #$prNumber")
            println("PrReviewService: Review posted for PR #$prNumber")
            onChatMessage("✅ **Ревью опубликовано на GitHub для PR #$prNumber**\n\n$reviewText")
        } else {
            onStatusUpdate("❌ Ошибка публикации ревью — PR #$prNumber")
            onChatMessage("❌ Не удалось опубликовать ревью для PR #$prNumber (проверь GITHUB_TOKEN)")
        }
    }
}
