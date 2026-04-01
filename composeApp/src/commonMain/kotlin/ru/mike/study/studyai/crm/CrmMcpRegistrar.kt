package ru.mike.study.studyai.crm

import ru.mike.study.studyai.mcp.McpConfigService
import ru.mike.study.studyai.mcp.McpServerConfig

object CrmMcpRegistrar {

    private const val SERVER_ID = "studyai-crm"

    fun register() {
        val classpath = System.getProperty("java.class.path") ?: run {
            println("CrmMcpRegistrar: java.class.path недоступен, пропускаем")
            return
        }
        val javaHome = System.getProperty("java.home") ?: run {
            println("CrmMcpRegistrar: java.home недоступен, пропускаем")
            return
        }
        val javaExe = "$javaHome/bin/java"

        val server = McpServerConfig(
            id = SERVER_ID,
            name = "StudyAI CRM",
            command = javaExe,
            args = listOf("-cp", classpath, "ru.mike.study.studyai.crm.CrmMcpServerKt"),
            enabled = true
        )

        val configService = McpConfigService()
        val existing = configService.getServers().find { it.id == SERVER_ID }

        if (existing == null) {
            configService.addServer(server)
            println("CrmMcpRegistrar: CRM MCP сервер зарегистрирован")
        } else {
            // Classpath меняется при пересборке — всегда обновляем
            configService.updateServer(server)
            println("CrmMcpRegistrar: CRM MCP сервер обновлён")
        }
    }
}
