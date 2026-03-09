package ru.mike.study.studyai.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun McpServersScreen(
    viewModel: McpViewModel,
    onClose: () -> Unit
) {
    val servers by viewModel.servers.collectAsState()
    val testResults by viewModel.testResults.collectAsState()
    val testingServerId by viewModel.testingServerId.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MCP Серверы",
                style = MaterialTheme.typography.headlineSmall
            )

            Row {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (servers.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Нет MCP серверов",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Добавить сервер")
                    }
                }
            }
        } else {
            // Server list
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    McpServerCard(
                        server = server,
                        testResult = testResults[server.id],
                        isTesting = testingServerId == server.id,
                        onTest = { viewModel.testServer(server.id) },
                        onEdit = { editingServer = server },
                        onDelete = { viewModel.removeServer(server.id) },
                        onToggle = { viewModel.toggleServer(server.id) }
                    )
                }
            }
        }
    }

    // Add server dialog
    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, command, args ->
                viewModel.addServer(name, command, args)
                showAddDialog = false
            }
        )
    }

    // Edit server dialog
    editingServer?.let { server ->
        EditServerDialog(
            server = server,
            onDismiss = { editingServer = null },
            onSave = { name, command, args ->
                viewModel.updateServer(server.id, name, command, args)
                editingServer = null
            }
        )
    }
}

@Composable
fun McpServerCard(
    server: McpServerConfig,
    testResult: McpTestResult?,
    isTesting: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (server.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = if (server.enabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Switch(
                    checked = server.enabled,
                    onCheckedChange = { onToggle() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Command
            Text(
                text = "${server.command} ${server.args.joinToString(" ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Test button
                Button(
                    onClick = onTest,
                    enabled = !isTesting && server.enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Тестирование...")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Тест")
                    }
                }

                Row {
                    // Edit button
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Редактировать",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Delete button
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Test result
            if (testResult != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                if (testResult.success) {
                    // Success
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Подключено: ${testResult.serverName ?: "MCP Server"} ${testResult.serverVersion ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (testResult.tools.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Инструменты (${testResult.tools.size}):",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        testResult.tools.forEach { tool ->
                            Row(
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = tool.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (tool.description != null) {
                                        Text(
                                            text = tool.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Error
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ошибка: ${testResult.error}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, command: String, args: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("npx") }
    var args by remember { mutableStateOf("-y @modelcontextprotocol/server-filesystem /tmp") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить MCP сервер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    placeholder = { Text("Например: filesystem") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Команда") },
                    placeholder = { Text("npx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("Аргументы") },
                    placeholder = { Text("-y @modelcontextprotocol/server-filesystem /tmp") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                // Presets
                Text(
                    text = "Шаблоны:",
                    style = MaterialTheme.typography.labelMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            name = "filesystem"
                            command = "npx"
                            args = "-y @modelcontextprotocol/server-filesystem /tmp"
                        },
                        label = { Text("Filesystem") }
                    )
                    AssistChip(
                        onClick = {
                            name = "memory"
                            command = "npx"
                            args = "-y @modelcontextprotocol/server-memory"
                        },
                        label = { Text("Memory") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, command, args) },
                enabled = name.isNotBlank() && command.isNotBlank()
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun EditServerDialog(
    server: McpServerConfig,
    onDismiss: () -> Unit,
    onSave: (name: String, command: String, args: String) -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    var command by remember { mutableStateOf(server.command) }
    var args by remember { mutableStateOf(server.args.joinToString(" ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать MCP сервер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Команда") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("Аргументы") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, command, args) },
                enabled = name.isNotBlank() && command.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
