package ru.mike.study.studyai.config

import java.io.File
import java.util.Properties

object ApiConfig {
    val apiKey: String by lazy {
        val props = Properties()
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "../../local.properties"
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                println("Found local.properties at: ${file.absolutePath}")
                file.inputStream().use { props.load(it) }
                break
            }
        }

        val key = props.getProperty("OPENAI_API_KEY") ?: ""
        println("API Key loaded: ${if (key.isNotEmpty()) "${key.take(10)}..." else "EMPTY"}")
        key
    }
}