package ru.mike.study.studyai.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * MCP Client - connects to MCP server via stdio
 */
class McpClient(
    private val command: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true   // Encode fields with defaults
        explicitNulls = false   // Don't include null fields in JSON
    }

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var requestId = 0
    private var initialized = false

    var serverInfo: ServerInfo? = null
        private set
    var serverCapabilities: ServerCapabilities? = null
        private set

    private fun log(message: String) {
        McpLogger.log(message)
    }

    private fun logRequest(method: String, params: Any?) {
        McpLogger.logRequest(method, params)
    }

    private fun logResponse(method: String, response: JsonRpcResponse) {
        McpLogger.logResponse(
            method = method,
            success = response.error == null,
            result = response.result?.toString(),
            error = response.error?.message
        )
    }

    /**
     * Start MCP server process and establish connection
     */
    suspend fun connect(): Result<InitializeResult> = withContext(Dispatchers.IO) {
        try {
            log("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            log("┃ 🔌 CONNECTING TO MCP SERVER")
            log("┃ Command: $command")
            log("┃ Args: ${args.joinToString(" ")}")
            log("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            // Parse inline env vars from args (format: KEY=value)
            val parsedEnv = mutableMapOf<String, String>()
            val parsedArgs = mutableListOf<String>()
            var foundCommand = false

            for (arg in args) {
                if (!foundCommand && arg.contains("=") && !arg.startsWith("-")) {
                    // This looks like an env var (KEY=value)
                    val parts = arg.split("=", limit = 2)
                    if (parts.size == 2 && parts[0].matches(Regex("^[A-Z_][A-Z0-9_]*$"))) {
                        parsedEnv[parts[0]] = parts[1]
                        log("Parsed env: ${parts[0]}=***")
                        continue
                    }
                }
                foundCommand = true
                parsedArgs.add(arg)
            }

            // Build process
            log("Executing: $command ${parsedArgs.joinToString(" ")}")
            if (parsedEnv.isNotEmpty()) {
                log("With env vars: ${parsedEnv.keys.joinToString(", ")}")
            }

            val processBuilder = ProcessBuilder(listOf(command) + parsedArgs)
            processBuilder.environment().putAll(env)
            processBuilder.environment().putAll(parsedEnv)
            processBuilder.redirectErrorStream(false)

            log("Starting process...")
            process = processBuilder.start()
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            log("Process started (PID: ${process?.pid()})")

            // Start error stream reader in background
            Thread {
                try {
                    val errorReader = BufferedReader(InputStreamReader(process!!.errorStream))
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        McpLogger.log("[STDERR] $line")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }.start()

            // Initialize MCP connection
            log("Sending initialize request...")
            val initResult = initialize()

            if (initResult.isSuccess) {
                initialized = true
                serverInfo = initResult.getOrNull()?.serverInfo
                serverCapabilities = initResult.getOrNull()?.capabilities

                log("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                log("┃ ✓ CONNECTED SUCCESSFULLY")
                log("┃ Server: ${serverInfo?.name ?: "unknown"} ${serverInfo?.version ?: ""}")
                log("┃ Protocol: ${initResult.getOrNull()?.protocolVersion}")
                log("┃ Capabilities:")
                log("┃   - tools: ${serverCapabilities?.tools != null}")
                log("┃   - prompts: ${serverCapabilities?.prompts != null}")
                log("┃   - resources: ${serverCapabilities?.resources != null}")
                log("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Send initialized notification
                sendNotification("notifications/initialized")
            } else {
                log("❌ Initialize failed: ${initResult.exceptionOrNull()?.message}")
            }

            initResult
        } catch (e: Exception) {
            log("❌ Connection failed: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Send initialize request
     */
    private suspend fun initialize(): Result<InitializeResult> {
        val params = InitializeParams()
        val paramsJson = json.encodeToJsonElement(InitializeParams.serializer(), params)

        val response = sendRequest("initialize", paramsJson as JsonObject)

        return response.mapCatching { jsonResponse ->
            val result = jsonResponse.result
                ?: throw Exception("No result in initialize response")
            json.decodeFromJsonElement(InitializeResult.serializer(), result)
        }
    }

    /**
     * Get list of available tools
     */
    suspend fun listTools(): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        if (!initialized) {
            log("❌ Cannot list tools: client not initialized")
            return@withContext Result.failure(Exception("Client not initialized"))
        }

        log("Requesting tools list...")
        val response = sendRequest("tools/list", null)

        response.mapCatching { jsonResponse ->
            val result = jsonResponse.result
                ?: throw Exception("No result in tools/list response")
            val toolsResult = json.decodeFromJsonElement(ToolsListResult.serializer(), result)

            log("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            log("┃ 🔧 TOOLS (${toolsResult.tools.size})")
            toolsResult.tools.forEach { tool ->
                log("┃   📦 ${tool.name}")
                if (tool.description != null) {
                    log("┃      ${tool.description}")
                }
            }
            log("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            toolsResult.tools
        }
    }

    /**
     * Call a tool
     */
    suspend fun callTool(name: String, arguments: JsonObject? = null): Result<ToolCallResult> =
        withContext(Dispatchers.IO) {
            if (!initialized) {
                log("❌ Cannot call tool: client not initialized")
                return@withContext Result.failure(Exception("Client not initialized"))
            }

            log("Calling tool: $name")
            if (arguments != null) {
                log("Arguments: $arguments")
            }

            val params = buildJsonObject {
                put("name", json.encodeToJsonElement(kotlinx.serialization.serializer<String>(), name))
                if (arguments != null) {
                    put("arguments", arguments)
                }
            }

            val response = sendRequest("tools/call", params)

            response.mapCatching { jsonResponse ->
                val result = jsonResponse.result
                    ?: throw Exception("No result in tools/call response")
                val toolResult = json.decodeFromJsonElement(ToolCallResult.serializer(), result)

                log("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                log("┃ 🔧 TOOL RESULT: $name")
                toolResult.content.forEach { content ->
                    log("┃   [${content.type}] ${content.text?.take(100) ?: ""}")
                }
                if (toolResult.isError == true) {
                    log("┃   ❌ Tool returned error")
                }
                log("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                toolResult
            }
        }

    /**
     * Send JSON-RPC request and wait for response
     */
    private suspend fun sendRequest(method: String, params: JsonObject?): Result<JsonRpcResponse> =
        withContext(Dispatchers.IO) {
            try {
                val id = ++requestId
                val request = JsonRpcRequest(
                    id = id,
                    method = method,
                    params = params
                )

                val requestJson = json.encodeToString(request)
                logRequest(method, params)

                log(">>> Sending: ${requestJson.take(200)}${if (requestJson.length > 200) "..." else ""}")

                writer?.write(requestJson)
                writer?.newLine()
                writer?.flush()

                // Read response
                log("<<< Waiting for response...")
                val responseLine = reader?.readLine()
                    ?: return@withContext Result.failure(Exception("No response from server"))

                log("<<< Received: ${responseLine.take(200)}${if (responseLine.length > 200) "..." else ""}")

                val response = json.decodeFromString<JsonRpcResponse>(responseLine)
                logResponse(method, response)

                if (response.error != null) {
                    Result.failure(Exception("MCP Error: ${response.error.message}"))
                } else {
                    Result.success(response)
                }
            } catch (e: Exception) {
                log("❌ Request failed: ${e.message}")
                e.printStackTrace()
                Result.failure(e)
            }
        }

    /**
     * Send notification (no response expected)
     */
    private suspend fun sendNotification(method: String, params: JsonObject? = null) =
        withContext(Dispatchers.IO) {
            try {
                val notification = buildJsonObject {
                    put("jsonrpc", json.encodeToJsonElement(kotlinx.serialization.serializer<String>(), "2.0"))
                    put("method", json.encodeToJsonElement(kotlinx.serialization.serializer<String>(), method))
                    if (params != null) {
                        put("params", params)
                    }
                }

                val notificationJson = json.encodeToString(notification)
                log(">>> Notification: $method")

                writer?.write(notificationJson)
                writer?.newLine()
                writer?.flush()
            } catch (e: Exception) {
                log("❌ Failed to send notification: ${e.message}")
            }
        }

    /**
     * Disconnect from MCP server
     */
    fun disconnect() {
        log("Disconnecting...")
        try {
            writer?.close()
            reader?.close()
            process?.destroy()
            log("✓ Disconnected")
        } catch (e: Exception) {
            log("❌ Disconnect error: ${e.message}")
        } finally {
            process = null
            reader = null
            writer = null
            initialized = false
        }
    }

    val isConnected: Boolean
        get() = initialized && process?.isAlive == true
}
