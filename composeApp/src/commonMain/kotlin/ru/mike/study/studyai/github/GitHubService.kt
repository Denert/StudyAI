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
import java.io.File

class GitHubService(private val githubToken: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

    @Serializable
    data class PrFile(
        val filename: String,
        val status: String,
        val additions: Int = 0,
        val deletions: Int = 0,
        val patch: String? = null
    )

    @Serializable
    data class PrInfo(
        val number: Int,
        val title: String,
        val body: String? = null,
        val head: PrHead
    )

    @Serializable
    data class PrHead(val sha: String, val ref: String = "")

    @Serializable
    data class PrSummary(val number: Int, val head: PrHead)

    @Serializable
    private data class CommentRequest(val body: String)

    @Serializable
    private data class IssueComment(val body: String? = null)

    private fun authHeaders(): HttpRequestBuilder.() -> Unit = {
        header(HttpHeaders.Authorization, "Bearer $githubToken")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    suspend fun getPrInfo(owner: String, repo: String, prNumber: Int): PrInfo? = try {
        val response = client.get("https://api.github.com/repos/$owner/$repo/pulls/$prNumber", authHeaders())
        json.decodeFromString<PrInfo>(response.bodyAsText())
    } catch (e: Exception) {
        println("GitHubService: getPrInfo failed: ${e.message}")
        null
    }

    suspend fun getPrFiles(owner: String, repo: String, prNumber: Int): List<PrFile> = try {
        val response = client.get(
            "https://api.github.com/repos/$owner/$repo/pulls/$prNumber/files?per_page=100",
            authHeaders()
        )
        json.decodeFromString<List<PrFile>>(response.bodyAsText())
    } catch (e: Exception) {
        println("GitHubService: getPrFiles failed: ${e.message}")
        emptyList()
    }

    /** Returns (etag, prs). prs is null when server returns 304 Not Modified. */
    suspend fun listOpenPrs(owner: String, repo: String, etag: String? = null): Pair<String?, List<PrSummary>?> = try {
        val response = client.get("https://api.github.com/repos/$owner/$repo/pulls?state=open&per_page=50") {
            authHeaders()()
            if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
        }
        val newEtag = response.headers[HttpHeaders.ETag]
        if (response.status.value == 304) Pair(etag, null)
        else Pair(newEtag, json.decodeFromString<List<PrSummary>>(response.bodyAsText()))
    } catch (e: Exception) {
        println("GitHubService: listOpenPrs failed: ${e.message}")
        Pair(null, null)
    }

    suspend fun findPrByBranch(owner: String, repo: String, branch: String): PrSummary? = try {
        val response = client.get(
            "https://api.github.com/repos/$owner/$repo/pulls?state=open&head=$owner:$branch&per_page=1",
            authHeaders()
        )
        json.decodeFromString<List<PrSummary>>(response.bodyAsText()).firstOrNull()
    } catch (e: Exception) {
        println("GitHubService: findPrByBranch failed: ${e.message}")
        null
    }

    suspend fun hasAiReviewComment(owner: String, repo: String, prNumber: Int): Boolean = try {
        val response = client.get(
            "https://api.github.com/repos/$owner/$repo/issues/$prNumber/comments?per_page=100",
            authHeaders()
        )
        json.decodeFromString<List<IssueComment>>(response.bodyAsText())
            .any { it.body?.startsWith("## 🤖 AI Code Review") == true }
    } catch (e: Exception) {
        println("GitHubService: hasAiReviewComment failed: ${e.message}")
        true // fail-safe: assume exists to avoid spam
    }

    suspend fun postComment(owner: String, repo: String, prNumber: Int, body: String): Boolean = try {
        val response = client.post("https://api.github.com/repos/$owner/$repo/issues/$prNumber/comments") {
            authHeaders()()
            contentType(ContentType.Application.Json)
            setBody(CommentRequest(body))
        }
        response.status.isSuccess().also {
            if (!it) println("GitHubService: postComment HTTP ${response.status}: ${response.bodyAsText().take(200)}")
        }
    } catch (e: Exception) {
        println("GitHubService: postComment failed: ${e.message}")
        false
    }

    companion object {
        fun parseOwnerRepo(projectPath: String): Pair<String, String>? = try {
            val gitConfig = File("$projectPath/.git/config")
            if (!gitConfig.exists()) return null
            val content = gitConfig.readText()
            val regex = Regex("""url\s*=\s*https://github\.com/([^/\s]+)/([^\s.]+)""")
            val match = regex.find(content) ?: return null
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            Pair(owner, repo)
        } catch (e: Exception) {
            null
        }
    }
}
