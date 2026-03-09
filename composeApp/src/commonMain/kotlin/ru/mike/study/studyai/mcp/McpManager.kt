package ru.mike.study.studyai.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Manages all MCP server connections and provides unified access to tools
 */
class McpManager {

    private val configService = McpConfigService()
    private val clients = mutableMapOf<String, McpClient>()
    private val serverTools = mutableMapOf<String, List<McpTool>>()
    private val mutex = Mutex()

    /**
     * Connect to all enabled MCP servers
     */
    suspend fun connectAll(): Map<String, Result<List<McpTool>>> {
        val results = mutableMapOf<String, Result<List<McpTool>>>()

        val servers = configService.getServers().filter { it.enabled }

        for (server in servers) {
            McpLogger.log("Connecting to server: ${server.name}")
            val result = connectServer(server)
            results[server.id] = result
        }

        return results
    }

    /**
     * Connect to a specific server
     */
    suspend fun connectServer(server: McpServerConfig): Result<List<McpTool>> {
        return mutex.withLock {
            try {
                // Disconnect existing client if any
                clients[server.id]?.disconnect()

                val client = configService.createClient(server)
                val connectResult = client.connect()

                if (connectResult.isFailure) {
                    return@withLock Result.failure(connectResult.exceptionOrNull()!!)
                }

                val toolsResult = client.listTools()

                if (toolsResult.isSuccess) {
                    clients[server.id] = client
                    serverTools[server.id] = toolsResult.getOrDefault(emptyList())
                    McpLogger.log("Server ${server.name}: ${serverTools[server.id]?.size ?: 0} tools")
                    Result.success(serverTools[server.id]!!)
                } else {
                    client.disconnect()
                    Result.failure(toolsResult.exceptionOrNull()!!)
                }
            } catch (e: Exception) {
                McpLogger.logError("Failed to connect to ${server.name}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get all available tools from all connected servers
     */
    fun getAllTools(): List<McpToolWithServer> {
        val allTools = mutableListOf<McpToolWithServer>()

        for ((serverId, tools) in serverTools) {
            val server = configService.getServers().find { it.id == serverId }
            for (tool in tools) {
                allTools.add(McpToolWithServer(
                    tool = tool,
                    serverId = serverId,
                    serverName = server?.name ?: "unknown"
                ))
            }
        }

        return allTools
    }

    /**
     * Call a tool by name
     */
    suspend fun callTool(toolName: String, arguments: JsonObject?): Result<ToolCallResult> {
        // Find which server has this tool
        for ((serverId, tools) in serverTools) {
            if (tools.any { it.name == toolName }) {
                val client = clients[serverId]
                    ?: return Result.failure(Exception("Server not connected"))

                McpLogger.log("Calling tool '$toolName' on server $serverId")
                return client.callTool(toolName, arguments)
            }
        }

        return Result.failure(Exception("Tool '$toolName' not found"))
    }

    /**
     * Disconnect from all servers
     */
    fun disconnectAll() {
        for ((_, client) in clients) {
            client.disconnect()
        }
        clients.clear()
        serverTools.clear()
    }

    /**
     * Check if any servers are connected
     */
    fun hasConnectedServers(): Boolean = clients.isNotEmpty()

    /**
     * Get connected server count
     */
    fun connectedServerCount(): Int = clients.size
}

/**
 * Tool with server info
 */
data class McpToolWithServer(
    val tool: McpTool,
    val serverId: String,
    val serverName: String
)
