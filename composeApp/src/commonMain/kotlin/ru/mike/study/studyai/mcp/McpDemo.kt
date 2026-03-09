package ru.mike.study.studyai.mcp

import kotlinx.coroutines.runBlocking

/**
 * Demo: Connect to MCP server and list available tools
 *
 * Usage:
 *   McpDemo.run("npx", listOf("-y", "@anthropic/mcp-server-memory"))
 *   McpDemo.run("npx", listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
 */
object McpDemo {

    fun run(command: String, args: List<String> = emptyList(), env: Map<String, String> = emptyMap()) {
        println("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
        println("┃  🔌 MCP CONNECTION DEMO                                   ┃")
        println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
        println()
        println("Command: $command ${args.joinToString(" ")}")
        println()

        val client = McpClient(command, args, env)

        runBlocking {
            // 1. Connect
            println("┌─ Connecting to MCP server...")
            val connectResult = client.connect()

            connectResult.fold(
                onSuccess = { initResult ->
                    println("│  ✓ Connected!")
                    println("│  Protocol: ${initResult.protocolVersion}")
                    println("│  Server: ${initResult.serverInfo?.name ?: "unknown"} ${initResult.serverInfo?.version ?: ""}")
                    println("│  Capabilities:")
                    println("│    - tools: ${initResult.capabilities.tools != null}")
                    println("│    - prompts: ${initResult.capabilities.prompts != null}")
                    println("│    - resources: ${initResult.capabilities.resources != null}")
                    println("│")

                    // 2. List tools
                    println("├─ Requesting tools list...")
                    val toolsResult = client.listTools()

                    toolsResult.fold(
                        onSuccess = { tools ->
                            println("│  ✓ Found ${tools.size} tools:")
                            println("│")
                            tools.forEach { tool ->
                                println("│  📦 ${tool.name}")
                                if (tool.description != null) {
                                    println("│     ${tool.description}")
                                }
                            }
                        },
                        onFailure = { error ->
                            println("│  ✗ Failed to list tools: ${error.message}")
                        }
                    )
                },
                onFailure = { error ->
                    println("│  ✗ Connection failed: ${error.message}")
                    error.printStackTrace()
                }
            )

            // 3. Disconnect
            println("│")
            println("└─ Disconnecting...")
            client.disconnect()
            println("   ✓ Disconnected")
        }
    }

    /**
     * Quick test with filesystem server
     */
    fun testFilesystem(path: String = "/tmp") {
        run("npx", listOf("-y", "@modelcontextprotocol/server-filesystem", path))
    }

    /**
     * Quick test with memory server
     */
    fun testMemory() {
        run("npx", listOf("-y", "@modelcontextprotocol/server-memory"))
    }
}

// For running from command line
fun main() {
    // Test with filesystem server pointing to /tmp
    McpDemo.testFilesystem("/tmp")
}
