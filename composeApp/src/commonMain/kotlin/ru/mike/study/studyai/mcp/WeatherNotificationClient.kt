package ru.mike.study.studyai.mcp

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * WebSocket client for receiving weather notifications from the scheduler server
 */
class WeatherNotificationClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient {
        install(WebSockets)
    }

    private var job: Job? = null
    private var session: WebSocketSession? = null

    private val _notifications = MutableSharedFlow<WeatherNotification>()
    val notifications: SharedFlow<WeatherNotification> = _notifications

    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState

    /**
     * Connect to the WebSocket server
     */
    fun connect(
        host: String = "localhost",
        port: Int = 8081,
        scope: CoroutineScope
    ) {
        job?.cancel()

        job = scope.launch {
            while (isActive) {
                try {
                    _connectionState.emit(ConnectionState.CONNECTING)
                    McpLogger.log("[WS Client] Connecting to ws://$host:$port")

                    client.webSocket(host = host, port = port, path = "/") {
                        session = this
                        _connectionState.emit(ConnectionState.CONNECTED)
                        McpLogger.log("[WS Client] Connected")

                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    McpLogger.log("[WS Client] Received: $text")

                                    try {
                                        val notification = json.decodeFromString<WeatherNotification>(text)
                                        _notifications.emit(notification)
                                    } catch (e: Exception) {
                                        McpLogger.logError("[WS Client] Parse error", e)
                                    }
                                }
                                is Frame.Close -> {
                                    McpLogger.log("[WS Client] Connection closed")
                                    break
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    McpLogger.logError("[WS Client] Connection error", e)
                    _connectionState.emit(ConnectionState.DISCONNECTED)
                }

                // Reconnect after delay
                if (isActive) {
                    McpLogger.log("[WS Client] Reconnecting in 5 seconds...")
                    delay(5000)
                }
            }
        }
    }

    /**
     * Disconnect from the WebSocket server
     */
    fun disconnect() {
        job?.cancel()
        job = null
        session = null
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
}

@Serializable
data class WeatherNotification(
    val type: String,
    val message: String,
    val data: WeatherData? = null
)

@Serializable
data class WeatherData(
    val location: String? = null,
    val temperature: String? = null,
    val feels_like: String? = null,
    val conditions: String? = null,
    val humidity: String? = null,
    val wind: String? = null,
    val timestamp: String? = null
)
