package ru.mike.study.studyai.config

import java.net.HttpURLConnection
import java.net.URL

object OllamaManager {

    private var process: Process? = null
    private var weStartedIt = false

    /**
     * Проверяет, запущен ли Ollama, и запускает его если нет.
     * Вызывать из IO-потока.
     */
    fun ensureRunning(baseUrl: String) {
        if (isRunning(baseUrl)) {
            println("★ Ollama уже запущен по адресу $baseUrl")
            return
        }
        println("★ Ollama не обнаружен — запускаю 'ollama serve'...")
        try {
            process = ProcessBuilder("ollama", "serve")
                .redirectErrorStream(true)
                .start()
            weStartedIt = true
            println("★ Ollama запущен (pid=${process?.pid()})")
            // Небольшая пауза чтобы сервер успел подняться
            Thread.sleep(1500)
        } catch (e: Exception) {
            println("★ Не удалось запустить Ollama: ${e.message}")
        }
    }

    fun isRunning(baseUrl: String): Boolean {
        return try {
            val url = URL("$baseUrl/api/tags")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.connect()
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Проверяет, доступна ли модель, и скачивает её если нет.
     * Вызывать из IO-потока.
     */
    fun ensureModelAvailable(baseUrl: String, modelName: String) {
        if (isModelAvailable(baseUrl, modelName)) {
            println("★ Модель $modelName уже доступна")
            return
        }
        println("★ Модель $modelName не найдена — запускаю 'ollama pull $modelName'...")
        try {
            val pullProcess = ProcessBuilder("ollama", "pull", modelName)
                .redirectErrorStream(true)
                .start()
            val output = pullProcess.inputStream.bufferedReader().readText()
            val exitCode = pullProcess.waitFor()
            if (exitCode == 0) {
                println("★ Модель $modelName успешно загружена")
            } else {
                println("★ Ошибка при загрузке модели $modelName (код $exitCode): $output")
            }
        } catch (e: Exception) {
            println("★ Не удалось загрузить модель $modelName: ${e.message}")
        }
    }

    private fun isModelAvailable(baseUrl: String, modelName: String): Boolean {
        return try {
            val url = URL("$baseUrl/api/tags")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.connect()
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().readText()
            // Простая проверка вхождения имени модели в ответ
            body.contains("\"$modelName\"") || body.contains("\"$modelName:")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Останавливает Ollama только если мы его сами запустили.
     */
    fun stopIfManaged() {
        if (weStartedIt) {
            process?.destroy()
            process = null
            weStartedIt = false
            println("★ Ollama остановлен")
        }
    }
}
