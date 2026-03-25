package ru.mike.study.studyai

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ru.mike.study.studyai.config.OllamaManager
import ru.mike.study.studyai.mcp.McpLogger

fun main() {
    // Initialize MCP logger (clears old logs)
    McpLogger.init()

    application {
        Window(
            onCloseRequest = {
                OllamaManager.stopIfManaged()
                exitApplication()
            },
            title = "StudyAI",
        ) {
            App()
        }
    }
}
