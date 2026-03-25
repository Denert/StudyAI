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
