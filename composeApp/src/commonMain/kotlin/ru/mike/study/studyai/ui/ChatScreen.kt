package ru.mike.study.studyai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ru.mike.study.studyai.data.Chat
import ru.mike.study.studyai.data.ChatMessage
import ru.mike.study.studyai.data.ContextStrategy
import ru.mike.study.studyai.data.FactData
import ru.mike.study.studyai.data.TaskPhase
import ru.mike.study.studyai.mcp.McpServersScreen
import ru.mike.study.studyai.mcp.McpViewModel
import ru.mike.study.studyai.viewmodel.ChatViewModel

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val chats by viewModel.chats.collectAsState()
    val currentChat by viewModel.currentChat.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val model by viewModel.model.collectAsState()
    val strategy by viewModel.strategy.collectAsState()
    val slidingWindowSize by viewModel.slidingWindowSize.collectAsState()
    val facts by viewModel.facts.collectAsState()
    val checkpointIndex by viewModel.checkpointIndex.collectAsState()
    val showBranchSelector by viewModel.showBranchSelector.collectAsState()
    val pendingBranchChat by viewModel.pendingBranchChat.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val currentPhase by viewModel.currentPhase.collectAsState()
    val awaitingPhaseConfirmation by viewModel.awaitingPhaseConfirmation.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Dialog states
    var showFactsDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showMcpDialog by remember { mutableStateOf(false) }

    // MCP ViewModel
    val mcpViewModel = remember { McpViewModel() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // Branch selector dialog
    if (showBranchSelector && pendingBranchChat != null) {
        BranchSelectorDialog(
            chat = pendingBranchChat!!,
            branchesWithIndent = viewModel.getAllBranchesRecursive(pendingBranchChat!!.id),
            currentChatId = currentChat?.id,
            onSelectMain = { viewModel.selectMainChat(pendingBranchChat!!.id) },
            onSelectBranch = { viewModel.selectBranch(it) },
            onDeleteBranch = { viewModel.deleteBranch(it) },
            onDismiss = { viewModel.dismissBranchSelector() }
        )
    }

    // Facts editor dialog
    if (showFactsDialog) {
        FactsEditorDialog(
            facts = facts,
            onUpdateFact = { key, value -> viewModel.updateFact(key, value) },
            onDeleteFact = { viewModel.deleteFact(it) },
            onDismiss = { showFactsDialog = false }
        )
    }

    // Profile manager dialog
    if (showProfileDialog) {
        ProfileManagerDialog(
            profiles = profiles,
            activeProfile = activeProfile,
            onSelectProfile = { viewModel.setActiveProfile(it) },
            onCreateProfile = { viewModel.createProfile(it) },
            onDeleteProfile = { viewModel.deleteProfile(it) },
            onDismiss = { showProfileDialog = false }
        )
    }

    // MCP servers dialog
    if (showMcpDialog) {
        Dialog(onDismissRequest = { showMcpDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.8f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                McpServersScreen(
                    viewModel = mcpViewModel,
                    onClose = { showMcpDialog = false }
                )
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar with chat list
        ChatSidebar(
            chats = chats.filter { it.parentChatId == null }, // Only root chats
            allChats = chats, // For counting actual branches
            currentChatId = currentChat?.id,
            onChatSelect = { viewModel.selectChat(it) },
            onNewChat = { viewModel.createNewChat() },
            onDeleteChat = { viewModel.deleteChat(it) },
            modifier = Modifier.width(250.dp)
        )

        // Main chat area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Strategy info bar
            if (strategy != ContextStrategy.NONE) {
                val currentBranchCount = currentChat?.let { chat ->
                    chats.count { it.parentChatId == chat.id }
                } ?: 0
                StrategyInfoBar(
                    strategy = strategy,
                    facts = facts,
                    checkpointIndex = checkpointIndex,
                    currentChat = currentChat,
                    branchCount = currentBranchCount,
                    activeProfile = activeProfile,
                    onShowFacts = { showFactsDialog = true },
                    onShowProfiles = { showProfileDialog = true }
                )
            }

            SelectionContainer(
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { index, _ -> index }
                    ) { _, message ->
                        ChatMessageItem(
                            message = message,
                            onConfirmPhase = { viewModel.confirmPhaseTransition() },
                            onRejectPhase = { viewModel.rejectPhaseTransition() }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Row 1: Model, Temperature, Strategy
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Model input
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(32.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (model.isEmpty()) {
                                Text(
                                    "gpt-4o-mini",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BasicTextField(
                                value = model,
                                onValueChange = { viewModel.setModel(it) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Temperature
                        Text("T:${"%.1f".format(temperature)}", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = temperature,
                            onValueChange = { viewModel.setTemperature(it) },
                            valueRange = 0f..2f,
                            steps = 19,
                            modifier = Modifier.weight(1f).height(20.dp)
                        )

                        // Strategy dropdown
                        StrategyDropdown(
                            selectedStrategy = strategy,
                            onStrategySelected = { viewModel.setStrategy(it) }
                        )

                        // Sliding window size (only for relevant strategies)
                        if (strategy == ContextStrategy.SLIDING_WINDOW ||
                            strategy == ContextStrategy.SUMMARY ||
                            strategy == ContextStrategy.STICKY_FACTS) {
                            Text("N:", style = MaterialTheme.typography.labelSmall)
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(32.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicTextField(
                                    value = slidingWindowSize.toString(),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { size -> viewModel.setSlidingWindowSize(size) }
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // MCP button
                        IconButton(
                            onClick = { showMcpDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Extension,
                                contentDescription = "MCP Серверы",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Row 2: Input field and buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                // Clear branch checkbox when user types
                                if (checkpointIndex != null) {
                                    viewModel.clearCheckpoint()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onKeyEvent { event ->
                                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed && !event.isMetaPressed && !event.isCtrlPressed) {
                                        if (inputText.isNotBlank() && !isLoading) {
                                            viewModel.sendMessage(inputText)
                                            inputText = ""
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            placeholder = { Text("Type a message...") },
                            enabled = !isLoading,
                            maxLines = 3,
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Branching checkbox
                        if (strategy == ContextStrategy.BRANCHING && currentChat?.canCreateBranch == true) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checkpointIndex != null,
                                    onCheckedChange = { checked ->
                                        if (checked) viewModel.setCheckpoint() else viewModel.clearCheckpoint()
                                    }
                                )
                                Text("Branch", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Facts button
                        if (strategy == ContextStrategy.STICKY_FACTS) {
                            IconButton(onClick = { showFactsDialog = true }) {
                                BadgedBox(
                                    badge = {
                                        if (facts.isNotEmpty()) {
                                            Badge { Text(facts.size.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = "Edit Facts"
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank() && !isLoading,
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Send")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyDropdown(
    selectedStrategy: ContextStrategy,
    onStrategySelected: (ContextStrategy) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        Box(
            modifier = Modifier
                .menuAnchor()
                .width(140.dp)
                .height(32.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strategyDisplayName(selectedStrategy),
                    style = MaterialTheme.typography.labelSmall
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 220.dp)
        ) {
            ContextStrategy.entries.filter { it != ContextStrategy.NONE }.forEach { strategy ->
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strategyDisplayName(strategy))
                            Text(
                                strategyDescription(strategy),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onStrategySelected(strategy)
                        expanded = false
                    }
                )
            }
        }
    }
}

fun strategyDisplayName(strategy: ContextStrategy): String {
    return when (strategy) {
        ContextStrategy.NONE -> "None"
        ContextStrategy.SLIDING_WINDOW -> "Sliding"
        ContextStrategy.SUMMARY -> "Summary"
        ContextStrategy.STICKY_FACTS -> "Facts"
        ContextStrategy.BRANCHING -> "Branch"
        ContextStrategy.MEMORY_LAYERS -> "Memory"
    }
}

fun strategyDescription(strategy: ContextStrategy): String {
    return when (strategy) {
        ContextStrategy.NONE -> "Send all messages"
        ContextStrategy.SLIDING_WINDOW -> "Keep last N messages"
        ContextStrategy.SUMMARY -> "Summarize old messages"
        ContextStrategy.STICKY_FACTS -> "Extract key facts"
        ContextStrategy.BRANCHING -> "Create dialog branches"
        ContextStrategy.MEMORY_LAYERS -> "3-layer memory"
    }
}

@Composable
fun StrategyInfoBar(
    strategy: ContextStrategy,
    facts: List<FactData>,
    checkpointIndex: Int?,
    currentChat: Chat?,
    branchCount: Int,
    activeProfile: String,
    onShowFacts: () -> Unit,
    onShowProfiles: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Strategy: ${strategyDisplayName(strategy)}",
                style = MaterialTheme.typography.labelSmall
            )

            when (strategy) {
                ContextStrategy.STICKY_FACTS -> {
                    Text(
                        text = "| ${facts.size} facts [Edit]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onShowFacts() }
                    )
                }
                ContextStrategy.BRANCHING -> {
                    currentChat?.let {
                        if (it.branchName != null) {
                            Text("| Branch ${it.branchName}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (it.branchDepth > 0) {
                            Text("| depth ${it.branchDepth}/3", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (branchCount > 0) {
                        Text("| $branchCount sub-branches", style = MaterialTheme.typography.labelSmall)
                    }
                    if (checkpointIndex != null) {
                        Text(
                            "| Will create 2 branches on Send",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                ContextStrategy.MEMORY_LAYERS -> {
                    Text(
                        text = "| Profile: $activeProfile [Manage]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onShowProfiles() }
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
fun BranchSelectorDialog(
    chat: Chat,
    branchesWithIndent: List<Pair<Chat, Int>>,
    currentChatId: String?,
    onSelectMain: () -> Unit,
    onSelectBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var branchToDelete by remember { mutableStateOf<Chat?>(null) }
    val isMainSelected = currentChatId == chat.id

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Select Branch",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Chat \"${chat.name}\" has ${branchesWithIndent.size} branches",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                // Main chat option
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectMain() },
                    color = if (isMainSelected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Chat,
                            contentDescription = null,
                            tint = if (isMainSelected) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "Main Chat",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMainSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "${chat.messages.size} messages",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isMainSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Branch options with indentation
                branchesWithIndent.forEach { (branch, indentLevel) ->
                    val isBranchSelected = currentChatId == branch.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (indentLevel * 16).dp),
                        color = if (isBranchSelected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val contentColor = if (isBranchSelected) MaterialTheme.colorScheme.onPrimary
                                          else MaterialTheme.colorScheme.onSecondaryContainer
                        Row(
                            modifier = Modifier
                                .clickable { onSelectBranch(branch.id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CallSplit,
                                contentDescription = null,
                                tint = contentColor
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Branch ${branch.branchName ?: ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor
                                )
                                Text(
                                    "${branch.messages.size} messages",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(
                                onClick = { branchToDelete = branch },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete branch",
                                    tint = if (isBranchSelected) MaterialTheme.colorScheme.onPrimary
                                           else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    // Delete confirmation dialog
    branchToDelete?.let { branch ->
        AlertDialog(
            onDismissRequest = { branchToDelete = null },
            title = { Text("Delete Branch?") },
            text = {
                Text("Are you sure you want to delete branch \"${branch.branchName}\"? This will also delete all sub-branches.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteBranch(branch.id)
                        branchToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { branchToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FactsEditorDialog(
    facts: List<FactData>,
    onUpdateFact: (String, String) -> Unit,
    onDeleteFact: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(min = 300.dp, max = 500.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Edit Facts", style = MaterialTheme.typography.titleMedium)

                if (facts.isEmpty()) {
                    Text(
                        "No facts yet. Facts are extracted automatically or you can add them manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(facts) { fact ->
                        FactItem(
                            fact = fact,
                            onUpdate = { newValue -> onUpdateFact(fact.key, newValue) },
                            onDelete = { onDeleteFact(fact.key) }
                        )
                    }
                }

                HorizontalDivider()

                Text("Add New Fact", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text("Key") },
                        modifier = Modifier.weight(0.4f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("Value") },
                        modifier = Modifier.weight(0.6f),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (newKey.isNotBlank() && newValue.isNotBlank()) {
                                onUpdateFact(newKey, newValue)
                                newKey = ""
                                newValue = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun ProfileManagerDialog(
    profiles: List<String>,
    activeProfile: String,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newProfileName by remember { mutableStateOf("") }
    var profileToDelete by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(min = 300.dp, max = 400.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Manage Profiles", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Profiles store long-term memory (user data, preferences, knowledge, decisions) that applies to all chats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Profile list
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(profiles) { profile ->
                        val isActive = profile == activeProfile
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProfile(profile) },
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                   else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                           else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = profile,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isActive) {
                                    Text(
                                        text = "Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (profile != "Default") {
                                    IconButton(
                                        onClick = { profileToDelete = profile },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete profile",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Create new profile
                Text("Create New Profile", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Profile Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (newProfileName.isNotBlank()) {
                                onCreateProfile(newProfileName)
                                newProfileName = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create")
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }

    // Delete confirmation dialog
    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete Profile?") },
            text = {
                Text("Are you sure you want to delete profile \"$profile\"? All memory data for this profile will be lost.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(profile)
                        profileToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FactItem(
    fact: FactData,
    onUpdate: (String) -> Unit,
    onDelete: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(fact.value) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${fact.key}:",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(100.dp)
            )

            if (isEditing) {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = {
                    onUpdate(editValue)
                    isEditing = false
                }) {
                    Icon(Icons.Default.Check, contentDescription = "Save")
                }
            } else {
                Text(
                    text = fact.value,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { isEditing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ChatSidebar(
    chats: List<Chat>,
    allChats: List<Chat>,
    currentChatId: String?,
    onChatSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column {
            // New chat button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNewChat() }
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Chat",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "New Chat",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chat list
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                items(chats) { chat ->
                    val branchCount = allChats.count { it.parentChatId == chat.id }
                    ChatListItem(
                        chat = chat,
                        branchCount = branchCount,
                        isSelected = chat.id == currentChatId,
                        onClick = { onChatSelect(chat.id) },
                        onDelete = { onDeleteChat(chat.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ChatListItem(
    chat: Chat,
    branchCount: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onClick() },
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (branchCount > 0) {
                        Icon(
                            Icons.Default.CallSplit,
                            contentDescription = "Has branches",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "${chat.messages.size} msg | ${strategyDisplayName(chat.strategy)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (chat.totalCostRub > 0) {
                    Text(
                        text = "${"%.4f".format(chat.totalCostRub)} ₽",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onConfirmPhase: () -> Unit = {},
    onRejectPhase: () -> Unit = {}
) {
    // System notification (weather, etc.) - centered with special styling
    if (message.isSystemNotification) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 350.dp)
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Text(
                text = "Auto notification",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val backgroundColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isFromUser) Alignment.End else Alignment.Start
    ) {

        Box(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isFromUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isFromUser) 4.dp else 16.dp
                    )
                )
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            if (message.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Thinking...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isFromUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
        }


        if (message.isFromUser) {
            val estimatedTokens = (message.content.length / 2.5).toInt().coerceAtLeast(1)
            Text(
                text = "~$estimatedTokens tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp, top = 4.dp)
            )
        } else if (message.metadata != null) {
            val meta = message.metadata
            Text(
                text = "${meta.totalTokens} tokens | ${String.format("%.4f", meta.costRub)} ₽",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun PhaseStatusBadge(phase: TaskPhase, isCompleted: Boolean) {
    val (icon, color, text) = when (phase) {
        TaskPhase.PLANNING -> Triple(
            Icons.Default.Description,
            MaterialTheme.colorScheme.primary,
            "PLANNING"
        )
        TaskPhase.EXECUTION -> Triple(
            Icons.Default.Build,
            MaterialTheme.colorScheme.tertiary,
            "EXECUTION"
        )
        TaskPhase.VALIDATION -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.secondary,
            "VALIDATION"
        )
        TaskPhase.DONE -> Triple(
            Icons.Default.Done,
            MaterialTheme.colorScheme.primary,
            "DONE"
        )
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (isCompleted) "$text ✓" else text,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun PhaseConfirmationButtons(
    phase: TaskPhase?,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onConfirm,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Confirm",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onReject,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Reject",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
