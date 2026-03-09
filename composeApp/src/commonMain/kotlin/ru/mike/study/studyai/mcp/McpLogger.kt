package ru.mike.study.studyai.mcp

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MCP Logger - writes logs to file for debugging
 */
object McpLogger {

    private val logDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".studyai/logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val logFile: File
        get() = File(logDir, "mcp.log")

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var initialized = false

    /**
     * Initialize logger - clears previous logs
     */
    fun init() {
        if (!initialized) {
            try {
                // Clear log file on app start
                logFile.writeText("")
                log("=".repeat(60))
                log("MCP LOG STARTED")
                log("=".repeat(60))
                initialized = true
            } catch (e: Exception) {
                println("Failed to initialize MCP logger: ${e.message}")
            }
        }
    }

    /**
     * Log a message
     */
    fun log(message: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val logLine = "[$timestamp] $message"

        // Print to console
        println("[MCP] $message")

        // Write to file
        try {
            logFile.appendText(logLine + "\n")
        } catch (e: Exception) {
            // Ignore file write errors
        }
    }

    /**
     * Log with box formatting
     */
    fun logBox(title: String, lines: List<String>) {
        log("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("┃ $title")
        lines.forEach { log("┃ $it") }
        log("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Log request
     */
    fun logRequest(method: String, params: Any?) {
        log("┌─[REQUEST]──────────────────────────────────────────────")
        log("│ Method: $method")
        if (params != null) {
            log("│ Params: $params")
        }
        log("└────────────────────────────────────────────────────────")
    }

    /**
     * Log response
     */
    fun logResponse(method: String, success: Boolean, result: String?, error: String?) {
        log("┌─[RESPONSE]─────────────────────────────────────────────")
        log("│ Method: $method")
        if (success) {
            val truncated = if ((result?.length ?: 0) > 500) result?.take(500) + "..." else result
            log("│ ✓ Result: $truncated")
        } else {
            log("│ ❌ Error: $error")
        }
        log("└────────────────────────────────────────────────────────")
    }

    /**
     * Log error
     */
    fun logError(message: String, exception: Exception? = null) {
        log("❌ ERROR: $message")
        exception?.let {
            log("   Exception: ${it.javaClass.simpleName}: ${it.message}")
            it.stackTrace.take(5).forEach { frame ->
                log("      at $frame")
            }
        }
    }

    /**
     * Get log file path for reading
     */
    fun getLogFilePath(): String = logFile.absolutePath

    /**
     * Read all logs
     */
    fun readLogs(): String {
        return try {
            if (logFile.exists()) logFile.readText() else ""
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
}
