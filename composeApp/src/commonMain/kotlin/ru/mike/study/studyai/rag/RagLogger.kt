package ru.mike.study.studyai.rag

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RagLogger {

    private val logFile: File by lazy {
        val dir = File(System.getProperty("user.home"), ".studyai/logs")
        dir.mkdirs()
        File(dir, "rag.log")
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun init() {
        try {
            logFile.writeText("")
            log("=".repeat(60))
            log("RAG LOG STARTED")
            log("=".repeat(60))
        } catch (e: Exception) {
            println("Failed to init RagLogger: ${e.message}")
        }
    }

    fun log(message: String) {
        val line = "[${LocalDateTime.now().format(dateFormatter)}] $message"
        println("[RAG] $message")
        try { logFile.appendText(line + "\n") } catch (_: Exception) {}
    }

    fun logError(message: String, exception: Exception? = null) {
        log("❌ ERROR: $message")
        exception?.let {
            log("   ${it::class.simpleName}: ${it.message}")
            it.stackTrace.take(5).forEach { frame -> log("      at $frame") }
        }
    }
}
