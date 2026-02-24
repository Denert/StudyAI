package ru.mike.study.studyai.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.mike.study.studyai.data.Chat
import java.io.File
import java.util.UUID

class ChatStorage {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val storageDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".studyai/chats")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    fun getAllChats(): List<Chat> {
        return storageDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<Chat>(file.readText())
                } catch (e: Exception) {
                    println("Error reading chat file ${file.name}: ${e.message}")
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun getChat(id: String): Chat? {
        val file = File(storageDir, "$id.json")
        return if (file.exists()) {
            try {
                json.decodeFromString<Chat>(file.readText())
            } catch (e: Exception) {
                println("Error reading chat $id: ${e.message}")
                null
            }
        } else null
    }

    fun saveChat(chat: Chat) {
        val file = File(storageDir, "${chat.id}.json")
        try {
            file.writeText(json.encodeToString(chat))
        } catch (e: Exception) {
            println("Error saving chat ${chat.id}: ${e.message}")
        }
    }

    fun deleteChat(id: String) {
        val file = File(storageDir, "$id.json")
        if (file.exists()) {
            file.delete()
        }
    }

    fun createNewChat(name: String = "New Chat"): Chat {
        val chat = Chat(
            id = UUID.randomUUID().toString(),
            name = name
        )
        saveChat(chat)
        return chat
    }
}
