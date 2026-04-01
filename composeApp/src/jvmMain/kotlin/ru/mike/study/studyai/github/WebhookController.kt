package ru.mike.study.studyai.github

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds

actual class WebhookController actual constructor(
    private val projectPath: String,
    private val ownerRepo: String,
    private val scope: CoroutineScope,
    private val githubService: GitHubService,
    private val reviewedPrStore: ReviewedPrStore,
    private val onEvent: suspend (branch: String, sha: String) -> Unit
) {
    private val triggerFile = File(System.getProperty("user.home") + "/.studyai/pr-trigger.json")
    private val hookFile = File("$projectPath/.git/hooks/pre-push")
    private val json = Json { ignoreUnknownKeys = true }

    private var watchJob: kotlinx.coroutines.Job? = null
    private var serverJob: kotlinx.coroutines.Job? = null
    private var pollJob: kotlinx.coroutines.Job? = null
    private var watchService: java.nio.file.WatchService? = null
    private var serverEngine: ApplicationEngine? = null
    private var ghProcess: Process? = null
    private var lastProcessedTimestamp = 0L
    private var pollEtag: String? = null
    private var _isRunning = false
    actual val isRunning: Boolean get() = _isRunning

    @Serializable
    private data class FileTrigger(val branch: String, val sha: String, val timestamp: Long = 0)

    @Serializable
    private data class PrPayload(
        val action: String,
        @SerialName("pull_request") val pullRequest: PrPayloadPr
    )

    @Serializable
    private data class PrPayloadPr(
        val number: Int,
        val head: PrPayloadHead
    )

    @Serializable
    private data class PrPayloadHead(val ref: String, val sha: String)

    actual fun start() {
        if (_isRunning) return
        installHook()
        startWatcher()
        startGhWebhook()
        startPoller()
        _isRunning = true
        println("PrWatcher: started (project=$projectPath, repo=$ownerRepo)")
        processExistingTrigger()
    }

    actual fun stop() {
        watchJob?.cancel()
        watchService?.close()
        watchJob = null
        watchService = null
        ghProcess?.destroy()
        ghProcess = null
        serverEngine?.stop(0, 0)
        serverJob?.cancel()
        pollJob?.cancel()
        serverEngine = null
        serverJob = null
        pollJob = null
        removeHook()
        _isRunning = false
        println("PrWatcher: stopped")
    }

    actual fun checkNow() {
        if (!_isRunning) return
        scope.launch(Dispatchers.IO) { fetchAndTrigger(useEtag = false) }
    }

    private suspend fun fetchAndTrigger(useEtag: Boolean = true) {
        val parts = ownerRepo.split("/")
        if (parts.size != 2) return
        val (owner, repo) = parts
        try {
            val etag = if (useEtag) pollEtag else null
            val (newEtag, prs) = githubService.listOpenPrs(owner, repo, etag)
            if (newEtag != null) pollEtag = newEtag
            if (prs == null) { println("PrPoller: no changes (304)"); return }
            println("PrPoller: ${prs.size} open PR(s)")
            for (pr in prs) {
                val branch = pr.head.ref
                val sha = pr.head.sha
                if (branch.isEmpty()) continue
                println("PrPoller: checking PR #${pr.number} branch=$branch sha=$sha")
                onEvent(branch, sha)
            }
        } catch (e: Exception) {
            println("PrPoller: error: ${e.message}")
        }
    }

    private fun startPoller() {
        val parts = ownerRepo.split("/")
        if (parts.size != 2) { println("PrPoller: invalid ownerRepo, disabled"); return }
        pollJob = scope.launch(Dispatchers.IO) {
            println("PrPoller: started (interval=10s)")
            while (true) {
                fetchAndTrigger()
                delay(10_000)
            }
        }
    }

    private fun processExistingTrigger() {
        if (!triggerFile.exists()) return
        scope.launch {
            try {
                val trigger = json.decodeFromString<FileTrigger>(triggerFile.readText())
                if (trigger.timestamp > lastProcessedTimestamp) {
                    println("PrWatcher: unprocessed trigger found — branch=${trigger.branch} sha=${trigger.sha}")
                    lastProcessedTimestamp = trigger.timestamp
                    onEvent(trigger.branch, trigger.sha)
                }
            } catch (e: Exception) {
                println("PrWatcher: failed to process existing trigger: ${e.message}")
            }
        }
    }

    private fun startWatcher() {
        triggerFile.parentFile?.mkdirs()
        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws
        val watchDir: Path = triggerFile.parentFile.toPath()
        watchDir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)

        watchJob = scope.launch(Dispatchers.IO) {
            println("PrWatcher: watching ${triggerFile.parent} for changes")
            try {
                while (true) {
                    val key = ws.take()
                    for (event in key.pollEvents()) {
                        val changed = event.context() as? Path ?: continue
                        if (changed.fileName.toString() == "pr-trigger.json") {
                            delay(200)
                            try {
                                val trigger = json.decodeFromString<FileTrigger>(triggerFile.readText())
                                if (trigger.timestamp > lastProcessedTimestamp) {
                                    lastProcessedTimestamp = trigger.timestamp
                                    println("PrWatcher: new trigger — branch=${trigger.branch} sha=${trigger.sha}")
                                    onEvent(trigger.branch, trigger.sha)
                                } else {
                                    println("PrWatcher: duplicate trigger ignored (timestamp=${trigger.timestamp})")
                                }
                            } catch (e: Exception) {
                                println("PrWatcher: failed to read trigger: ${e.message}")
                            }
                        }
                    }
                    if (!key.reset()) {
                        println("PrWatcher: watch key invalid, stopping")
                        break
                    }
                }
            } catch (_: ClosedWatchServiceException) {
                println("PrWatcher: watcher closed cleanly")
            } catch (e: Exception) {
                println("PrWatcher: watcher error: ${e.message}")
            }
        }
    }

    private fun startGhWebhook() {
        val parts = ownerRepo.split("/")
        if (parts.size != 2) {
            println("GhWebhook: invalid ownerRepo '$ownerRepo', skipping")
            return
        }
        val (owner, repo) = parts
        val port = findFreePort()

        // embeddedServer is a CoroutineScope extension in Ktor 3.x — call it inside launch
        serverJob = scope.launch(Dispatchers.IO) {
            val server = embeddedServer(CIO, port = port) {
                routing {
                    post("/") {
                        val event = call.request.headers["X-GitHub-Event"]
                        val body = call.receiveText()
                        println("GhWebhook: received event=$event")
                        if (event == "pull_request") {
                            try {
                                val payload = json.decodeFromString<PrPayload>(body)
                                if (payload.action in listOf("opened", "reopened", "synchronize")) {
                                    val branch = payload.pullRequest.head.ref
                                    val sha = payload.pullRequest.head.sha
                                    println("GhWebhook: PR #${payload.pullRequest.number} action=${payload.action} branch=$branch sha=$sha")
                                    scope.launch { onEvent(branch, sha) }
                                }
                            } catch (e: Exception) {
                                println("GhWebhook: parse error: ${e.message}")
                            }
                        }
                        call.respond(HttpStatusCode.OK, "")
                    }
                }
            }
            serverEngine = server.start(wait = false).engine
            println("GhWebhook: server started on port $port")
            startGhProcess(owner, repo, port)
        }
    }

    private fun startGhProcess(owner: String, repo: String, port: Int) {
        try {
            val process = ProcessBuilder(
                "gh", "webhook", "forward",
                "--repo=$owner/$repo",
                "--events=pull_request",
                "--url=http://localhost:$port"
            )
                .redirectErrorStream(true)
                .start()
            ghProcess = process
            println("GhWebhook: gh webhook forward started for $owner/$repo → localhost:$port")
            scope.launch(Dispatchers.IO) {
                process.inputStream.bufferedReader().forEachLine { line ->
                    println("GhWebhook [gh]: $line")
                }
                println("GhWebhook: gh process exited (code=${process.waitFor()})")
            }
        } catch (e: Exception) {
            println("GhWebhook: failed to start gh: ${e.message} (is gh CLI installed and authenticated?)")
        }
    }

    private fun findFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun installHook() {
        val hooksDir = hookFile.parentFile
        if (!hooksDir.exists()) {
            println("PrWatcher: .git/hooks not found at $hooksDir")
            return
        }
        val script = """
            #!/bin/bash
            # Installed by StudyAI — do not edit manually
            while read local_ref local_sha remote_ref remote_sha; do
                if [ "${'$'}local_sha" != "0000000000000000000000000000000000000000" ]; then
                    BRANCH="${'$'}{local_ref#refs/heads/}"
                    mkdir -p "${'$'}HOME/.studyai"
                    printf '{"branch":"%s","sha":"%s","timestamp":%s}' \
                        "${'$'}BRANCH" "${'$'}local_sha" "${'$'}(date +%s)" \
                        > "${'$'}HOME/.studyai/pr-trigger.json"
                fi
            done
        """.trimIndent()
        hookFile.writeText(script)
        hookFile.setExecutable(true)
        println("PrWatcher: hook installed at ${hookFile.path}")
    }

    private fun removeHook() {
        if (hookFile.exists() && hookFile.readText().contains("Installed by StudyAI")) {
            hookFile.delete()
            println("PrWatcher: hook removed")
        }
    }
}
