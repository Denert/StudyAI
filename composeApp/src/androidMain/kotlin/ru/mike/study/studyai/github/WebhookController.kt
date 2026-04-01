package ru.mike.study.studyai.github

import kotlinx.coroutines.CoroutineScope

actual class WebhookController actual constructor(
    projectPath: String,
    ownerRepo: String,
    scope: CoroutineScope,
    githubService: GitHubService,
    reviewedPrStore: ReviewedPrStore,
    onEvent: suspend (branch: String, sha: String) -> Unit
) {
    actual val isRunning: Boolean = false
    actual fun start() {}
    actual fun stop() {}
    actual fun checkNow() {}
}
