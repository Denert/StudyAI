package ru.mike.study.studyai.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * MCP Server configuration
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)

/**
 * MCP configuration storage
 */
@Serializable
data class McpConfiguration(
    val servers: List<McpServerConfig> = emptyList()
)

/**
 * Service for managing MCP server configurations
 */
class McpConfigService {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".studyai")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val configFile: File
        get() = File(configDir, "mcp_servers.json")

    /**
     * Load MCP configuration
     */
    fun loadConfig(): McpConfiguration {
        return try {
            if (configFile.exists()) {
                json.decodeFromString(configFile.readText())
            } else {
                McpConfiguration()
            }
        } catch (e: Exception) {
            println("Error loading MCP config: ${e.message}")
            McpConfiguration()
        }
    }

    /**
     * Save MCP configuration
     */
    fun saveConfig(config: McpConfiguration) {
        try {
            configFile.writeText(json.encodeToString(config))
        } catch (e: Exception) {
            println("Error saving MCP config: ${e.message}")
        }
    }

    /**
     * Get all servers
     */
    fun getServers(): List<McpServerConfig> {
        return loadConfig().servers
    }

    /**
     * Add a new server
     */
    fun addServer(server: McpServerConfig) {
        val config = loadConfig()
        val updated = config.copy(servers = config.servers + server)
        saveConfig(updated)
    }

    /**
     * Remove a server by ID
     */
    fun removeServer(serverId: String) {
        val config = loadConfig()
        val updated = config.copy(servers = config.servers.filter { it.id != serverId })
        saveConfig(updated)
    }

    /**
     * Update a server
     */
    fun updateServer(server: McpServerConfig) {
        val config = loadConfig()
        val updated = config.copy(
            servers = config.servers.map {
                if (it.id == server.id) server else it
            }
        )
        saveConfig(updated)
    }

    /**
     * Create MCP client for a server config
     */
    fun createClient(server: McpServerConfig): McpClient {
        return McpClient(server.command, server.args, server.env)
    }
}
