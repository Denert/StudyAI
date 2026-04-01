package ru.mike.study.studyai.crm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

fun main() {
    val reader = BufferedReader(InputStreamReader(System.`in`))
    val writer = PrintWriter(System.out, true)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    CrmDatabase.initialize()
    System.err.println("[CRM MCP] Сервер запущен")

    var line: String?
    while (reader.readLine().also { line = it } != null) {
        val raw = line?.trim() ?: continue
        if (raw.isBlank()) continue

        try {
            val request = json.decodeFromString<JsonObject>(raw)
            val method = request["method"]?.jsonPrimitive?.content ?: continue
            val id = request["id"]?.jsonPrimitive?.intOrNull

            // Уведомления (без id) — ответ не нужен
            if (id == null) {
                System.err.println("[CRM MCP] Notification: $method")
                continue
            }

            System.err.println("[CRM MCP] Request: $method (id=$id)")

            val response = when (method) {
                "initialize" -> handleInitialize(id)
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(id, request["params"]?.jsonObject)
                else -> errorResponse(id, -32601, "Метод не найден: $method")
            }

            writer.println(json.encodeToString(response))
            writer.flush()

        } catch (e: Exception) {
            System.err.println("[CRM MCP] Ошибка обработки запроса: ${e.message}")
        }
    }

    System.err.println("[CRM MCP] stdin закрыт, завершение")
}

private fun handleInitialize(id: Int): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", buildJsonObject {
        put("protocolVersion", "2024-11-05")
        put("serverInfo", buildJsonObject {
            put("name", "StudyAI CRM")
            put("version", "1.0.0")
        })
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject {})
        })
    })
}

private fun handleToolsList(id: Int): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", buildJsonObject {
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("name", "crm_list_tickets")
                put("description", "Возвращает список всех тикетов поддержки: номер, название, приоритет, статус, пользователь. Используй чтобы показать доступные тикеты.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {})
                })
            })
            add(buildJsonObject {
                put("name", "crm_get_ticket")
                put("description", "Возвращает полные детали тикета по номеру: описание, шаги воспроизведения, версию, теги. Вызывай когда пользователь хочет работать с конкретным тикетом.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("number", buildJsonObject {
                            put("type", "integer")
                            put("description", "Номер тикета")
                        })
                    })
                    put("required", buildJsonArray { add("number") })
                })
            })
            add(buildJsonObject {
                put("name", "crm_search_tickets")
                put("description", "Поиск тикетов по ключевым словам в названии, описании или тегах.")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Поисковый запрос (например, «авторизация», «расчёт», «PDF»)")
                        })
                    })
                    put("required", buildJsonArray { add("query") })
                })
            })
        })
    })
}

private fun handleToolsCall(id: Int, params: JsonObject?): JsonObject {
    val toolName = params?.get("name")?.jsonPrimitive?.content
        ?: return errorResponse(id, -32602, "Не указано имя инструмента")
    val arguments = params["arguments"]?.jsonObject

    return try {
        val result = when (toolName) {
            "crm_list_tickets" -> {
                val tickets = CrmDatabase.loadAll()
                buildString {
                    appendLine("Тикеты поддержки (${tickets.size} шт.):\n")
                    tickets.forEach { t ->
                        appendLine("#${t.number} [${t.priority}] [${t.status}]  ${t.title}")
                        appendLine("  👤 ${t.user.name} (${t.user.email})")
                        appendLine("  🏷 ${t.tags.joinToString(", ")}")
                        appendLine()
                    }
                    appendLine("Чтобы открыть тикет, скажите: «открой тикет #<номер>»")
                }.trim()
            }

            "crm_get_ticket" -> {
                val number = arguments?.get("number")?.jsonPrimitive?.intOrNull
                    ?: return errorResponse(id, -32602, "Не указан номер тикета")
                val ticket = CrmDatabase.findByNumber(number)
                    ?: return toolResult(id, "Тикет #$number не найден.")
                buildString {
                    appendLine("=== Тикет #${ticket.number}: ${ticket.title} ===")
                    appendLine("Приоритет:  ${ticket.priority}")
                    appendLine("Статус:     ${ticket.status}")
                    appendLine("Версия:     ${ticket.affectedVersion ?: "не указана"}")
                    appendLine("Создан:     ${ticket.createdAt}")
                    appendLine("Теги:       ${ticket.tags.joinToString(", ")}")
                    appendLine()
                    appendLine("Пользователь: ${ticket.user.name} (${ticket.user.email})")
                    appendLine()
                    appendLine("Описание:")
                    appendLine(ticket.description)
                    ticket.stepsToReproduce?.let {
                        appendLine()
                        appendLine("Шаги воспроизведения:")
                        appendLine(it)
                    }
                }.trim()
            }

            "crm_search_tickets" -> {
                val query = arguments?.get("query")?.jsonPrimitive?.content
                    ?: return errorResponse(id, -32602, "Не указан поисковый запрос")
                val results = CrmDatabase.search(query)
                if (results.isEmpty()) {
                    "По запросу «$query» тикеты не найдены."
                } else {
                    buildString {
                        appendLine("Найдено тикетов: ${results.size}\n")
                        results.forEach { t ->
                            appendLine("#${t.number} [${t.priority}] [${t.status}]  ${t.title}")
                            appendLine("  ${t.description.take(120)}...")
                            appendLine()
                        }
                    }.trim()
                }
            }

            else -> return errorResponse(id, -32601, "Неизвестный инструмент: $toolName")
        }
        toolResult(id, result)
    } catch (e: Exception) {
        toolResult(id, "Ошибка выполнения: ${e.message}", isError = true)
    }
}

private fun toolResult(id: Int, text: String, isError: Boolean = false): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        if (isError) put("isError", true)
    })
}

private fun errorResponse(id: Int, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
    })
}
