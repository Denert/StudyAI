package ru.mike.study.studyai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * MCP Protocol models (JSON-RPC 2.0)
 */

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// MCP Initialize
@Serializable
data class InitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: ClientInfo = ClientInfo()
)

@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: SamplingCapability? = null
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean = false
)

@Serializable
data class SamplingCapability(
    val dummy: String? = null // placeholder
)

@Serializable
data class ClientInfo(
    val name: String = "StudyAI",
    val version: String = "1.0.0"
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val prompts: PromptsCapability? = null,
    val resources: ResourcesCapability? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String? = null
)

// Tools
@Serializable
data class ToolsListResult(
    val tools: List<McpTool>
)

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement? = null
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonElement? = null
)

@Serializable
data class ToolCallResult(
    val content: List<ToolContent>,
    val isError: Boolean? = null
)

@Serializable
data class ToolContent(
    val type: String,
    val text: String? = null
)
