package ru.mike.study.studyai.github

import kotlinx.coroutines.CoroutineScope

expect class WebhookController(
    projectPath: String,
    ownerRepo: String,
    scope: CoroutineScope,
    githubService: GitHubService,
    reviewedPrStore: ReviewedPrStore,
    onEvent: suspend (branch: String, sha: String) -> Unit
) {
    val isRunning: Boolean
    fun start()
    fun stop()
    fun checkNow()
}
