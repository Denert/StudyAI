package ru.mike.study.studyai.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Connection test result
 */
data class McpTestResult(
    val serverId: String,
    val success: Boolean,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val tools: List<McpTool> = emptyList(),
    val error: String? = null
)

/**
 * ViewModel for MCP server management
 */
class McpViewModel : ViewModel() {

    private val configService = McpConfigService()

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    private val _testResults = MutableStateFlow<Map<String, McpTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, McpTestResult>> = _testResults.asStateFlow()

    private val _testingServerId = MutableStateFlow<String?>(null)
    val testingServerId: StateFlow<String?> = _testingServerId.asStateFlow()

    init {
        loadServers()
    }

    private fun loadServers() {
        _servers.value = configService.getServers()
    }

    /**
     * Add a new MCP server
     */
    fun addServer(name: String, command: String, args: String) {
        val argsList = args.split(" ").filter { it.isNotBlank() }
        val server = McpServerConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            command = command,
            args = argsList
        )
        configService.addServer(server)
        loadServers()
    }

    /**
     * Remove a server
     */
    fun removeServer(serverId: String) {
        configService.removeServer(serverId)
        _testResults.value = _testResults.value - serverId
        loadServers()
    }

    /**
     * Update server configuration
     */
    fun updateServer(serverId: String, name: String, command: String, args: String) {
        val server = _servers.value.find { it.id == serverId } ?: return
        val argsList = args.split(" ").filter { it.isNotBlank() }
        configService.updateServer(server.copy(
            name = name,
            command = command,
            args = argsList
        ))
        _testResults.value = _testResults.value - serverId
        loadServers()
    }

    /**
     * Toggle server enabled state
     */
    fun toggleServer(serverId: String) {
        val server = _servers.value.find { it.id == serverId } ?: return
        configService.updateServer(server.copy(enabled = !server.enabled))
        loadServers()
    }

    /**
     * Test connection to a server
     */
    fun testServer(serverId: String) {
        val server = _servers.value.find { it.id == serverId } ?: return

        viewModelScope.launch {
            _testingServerId.value = serverId

            val client = configService.createClient(server)

            try {
                val connectResult = client.connect()

                connectResult.fold(
                    onSuccess = { initResult ->
                        // Get tools list
                        val toolsResult = client.listTools()

                        val tools = toolsResult.getOrDefault(emptyList())

                        _testResults.value = _testResults.value + (serverId to McpTestResult(
                            serverId = serverId,
                            success = true,
                            serverName = initResult.serverInfo?.name,
                            serverVersion = initResult.serverInfo?.version,
                            tools = tools
                        ))
                    },
                    onFailure = { error ->
                        _testResults.value = _testResults.value + (serverId to McpTestResult(
                            serverId = serverId,
                            success = false,
                            error = error.message ?: "Unknown error"
                        ))
                    }
                )
            } catch (e: Exception) {
                _testResults.value = _testResults.value + (serverId to McpTestResult(
                    serverId = serverId,
                    success = false,
                    error = e.message ?: "Unknown error"
                ))
            } finally {
                client.disconnect()
                _testingServerId.value = null
            }
        }
    }

    /**
     * Clear test result for a server
     */
    fun clearTestResult(serverId: String) {
        _testResults.value = _testResults.value - serverId
    }
}
