package ru.mike.study.studyai.memory

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Memory Layer Types
 */
enum class MemoryLayer {
    SHORT_TERM,  // Full current dialog (all messages)
    WORKING,     // Sub-tasks/topics within the dialog
    LONG_TERM    // User profile, decisions, knowledge (global)
}

/**
 * Task status in working memory
 */
enum class TaskStatus {
    ACTIVE,      // Currently being worked on
    COMPLETED,   // Task finished
    PAUSED       // Temporarily paused, may resume
}

/**
 * A sub-task/topic within the dialog
 */
data class WorkingTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val context: MutableList<String> = mutableListOf(),  // Key decisions, artifacts
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

/**
 * Working memory - tracks sub-tasks within dialog
 */
data class WorkingMemory(
    val tasks: MutableList<WorkingTask> = mutableListOf(),
    var currentTaskId: String? = null
) {
    val currentTask: WorkingTask?
        get() = tasks.find { it.id == currentTaskId }

    val activeTasks: List<WorkingTask>
        get() = tasks.filter { it.status == TaskStatus.ACTIVE }

    val completedTasks: List<WorkingTask>
        get() = tasks.filter { it.status == TaskStatus.COMPLETED }
}

/**
 * Long-term memory structure
 */
data class LongTermMemory(
    val profile: MutableMap<String, String> = mutableMapOf(),
    val preferences: MutableMap<String, String> = mutableMapOf(),
    val knowledge: MutableList<String> = mutableListOf(),
    val decisions: MutableList<String> = mutableListOf()
)

/**
 * Service for managing 3-layer memory system
 *
 * Layers:
 * 1. SHORT_TERM - Full dialog (all messages, managed by OpenAiService)
 * 2. WORKING - Sub-tasks/topics within dialog (per-chat .md file)
 * 3. LONG_TERM - User profile and knowledge (global .md file)
 */
class MemoryService {

    private val memoryDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".studyai/memory")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val longTermFile: File
        get() = File(memoryDir, "long_term_memory.md")

    private fun workingMemoryFile(chatId: String): File {
        return File(memoryDir, "working_memory_$chatId.md")
    }

    // ==================== LONG-TERM MEMORY ====================

    fun readLongTermMemory(): LongTermMemory {
        if (!longTermFile.exists()) {
            return LongTermMemory()
        }

        return try {
            parseLongTermMemory(longTermFile.readText())
        } catch (e: Exception) {
            println("Error reading long-term memory: ${e.message}")
            LongTermMemory()
        }
    }

    fun saveLongTermMemory(memory: LongTermMemory) {
        try {
            longTermFile.writeText(formatLongTermMemory(memory))
            println("┃  💾 Long-term memory saved")
        } catch (e: Exception) {
            println("Error saving long-term memory: ${e.message}")
        }
    }

    fun updateLongTermMemory(
        profileUpdates: Map<String, String> = emptyMap(),
        preferenceUpdates: Map<String, String> = emptyMap(),
        newKnowledge: List<String> = emptyList(),
        newDecisions: List<String> = emptyList()
    ) {
        val memory = readLongTermMemory()

        memory.profile.putAll(profileUpdates)
        memory.preferences.putAll(preferenceUpdates)
        memory.knowledge.addAll(newKnowledge.filter { it !in memory.knowledge })
        memory.decisions.addAll(newDecisions.filter { it !in memory.decisions })

        saveLongTermMemory(memory)
    }

    private fun formatLongTermMemory(memory: LongTermMemory): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return buildString {
            appendLine("# Long-Term Memory")
            appendLine("> Last updated: $timestamp")
            appendLine()

            appendLine("## Profile")
            if (memory.profile.isEmpty()) {
                appendLine("_No profile data yet_")
            } else {
                memory.profile.forEach { (key, value) ->
                    appendLine("- **$key**: $value")
                }
            }
            appendLine()

            appendLine("## Preferences")
            if (memory.preferences.isEmpty()) {
                appendLine("_No preferences yet_")
            } else {
                memory.preferences.forEach { (key, value) ->
                    appendLine("- **$key**: $value")
                }
            }
            appendLine()

            appendLine("## Knowledge")
            if (memory.knowledge.isEmpty()) {
                appendLine("_No knowledge stored yet_")
            } else {
                memory.knowledge.forEach { item ->
                    appendLine("- $item")
                }
            }
            appendLine()

            appendLine("## Important Decisions")
            if (memory.decisions.isEmpty()) {
                appendLine("_No decisions recorded yet_")
            } else {
                memory.decisions.forEach { item ->
                    appendLine("- $item")
                }
            }
        }
    }

    private fun parseLongTermMemory(content: String): LongTermMemory {
        val memory = LongTermMemory()
        var currentSection = ""

        content.lines().forEach { line ->
            when {
                line.startsWith("## Profile") -> currentSection = "profile"
                line.startsWith("## Preferences") -> currentSection = "preferences"
                line.startsWith("## Knowledge") -> currentSection = "knowledge"
                line.startsWith("## Important Decisions") -> currentSection = "decisions"
                line.startsWith("- **") && line.contains("**:") -> {
                    val keyEnd = line.indexOf("**:", 4)
                    if (keyEnd > 4) {
                        val key = line.substring(4, keyEnd)
                        val value = line.substring(keyEnd + 3).trim()
                        when (currentSection) {
                            "profile" -> memory.profile[key] = value
                            "preferences" -> memory.preferences[key] = value
                        }
                    }
                }
                line.startsWith("- ") && currentSection in listOf("knowledge", "decisions") -> {
                    val item = line.substring(2).trim()
                    if (item.isNotEmpty() && !item.startsWith("_")) {
                        when (currentSection) {
                            "knowledge" -> memory.knowledge.add(item)
                            "decisions" -> memory.decisions.add(item)
                        }
                    }
                }
            }
        }

        return memory
    }

    // ==================== WORKING MEMORY ====================

    fun readWorkingMemory(chatId: String): WorkingMemory {
        val file = workingMemoryFile(chatId)
        if (!file.exists()) {
            return WorkingMemory()
        }

        return try {
            parseWorkingMemory(file.readText())
        } catch (e: Exception) {
            println("Error reading working memory for chat $chatId: ${e.message}")
            WorkingMemory()
        }
    }

    fun saveWorkingMemory(chatId: String, memory: WorkingMemory) {
        try {
            val file = workingMemoryFile(chatId)
            file.writeText(formatWorkingMemory(memory))
            println("┃  💾 Working memory saved (${memory.tasks.size} tasks)")
        } catch (e: Exception) {
            println("Error saving working memory: ${e.message}")
        }
    }

    /**
     * Add a new sub-task to working memory
     */
    fun addTask(chatId: String, taskName: String, initialContext: List<String> = emptyList()): WorkingTask {
        val memory = readWorkingMemory(chatId)

        val newTask = WorkingTask(
            name = taskName,
            context = initialContext.toMutableList()
        )

        memory.tasks.add(newTask)
        memory.currentTaskId = newTask.id

        saveWorkingMemory(chatId, memory)
        return newTask
    }

    /**
     * Update current task with new context
     */
    fun updateCurrentTask(chatId: String, newContext: List<String>) {
        val memory = readWorkingMemory(chatId)
        val currentTask = memory.currentTask ?: return

        newContext.forEach { ctx ->
            if (ctx !in currentTask.context) {
                currentTask.context.add(ctx)
            }
        }

        saveWorkingMemory(chatId, memory)
    }

    /**
     * Complete a task
     */
    fun completeTask(chatId: String, taskId: String? = null) {
        val memory = readWorkingMemory(chatId)
        val task = if (taskId != null) {
            memory.tasks.find { it.id == taskId }
        } else {
            memory.currentTask
        } ?: return

        val index = memory.tasks.indexOfFirst { it.id == task.id }
        if (index >= 0) {
            memory.tasks[index] = task.copy(
                status = TaskStatus.COMPLETED,
                completedAt = System.currentTimeMillis()
            )
        }

        // Set current to next active task if current was completed
        if (memory.currentTaskId == task.id) {
            memory.currentTaskId = memory.activeTasks.firstOrNull()?.id
        }

        saveWorkingMemory(chatId, memory)
    }

    /**
     * Switch to a different task
     */
    fun switchTask(chatId: String, taskId: String) {
        val memory = readWorkingMemory(chatId)
        if (memory.tasks.any { it.id == taskId }) {
            memory.currentTaskId = taskId
            saveWorkingMemory(chatId, memory)
        }
    }

    fun clearWorkingMemory(chatId: String) {
        val file = workingMemoryFile(chatId)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun formatWorkingMemory(memory: WorkingMemory): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return buildString {
            appendLine("# Working Memory (Sub-Tasks)")
            appendLine("> Last updated: $timestamp")
            appendLine("> Current task: ${memory.currentTask?.name ?: "none"}")
            appendLine()

            appendLine("## Active Tasks")
            if (memory.activeTasks.isEmpty()) {
                appendLine("_No active tasks_")
            } else {
                memory.activeTasks.forEach { task ->
                    val isCurrent = task.id == memory.currentTaskId
                    appendLine("### ${if (isCurrent) "→ " else ""}${task.name}")
                    appendLine("- ID: ${task.id}")
                    appendLine("- Status: ${task.status}")
                    if (task.context.isNotEmpty()) {
                        appendLine("- Context:")
                        task.context.forEach { ctx ->
                            appendLine("  - $ctx")
                        }
                    }
                    appendLine()
                }
            }

            appendLine("## Completed Tasks")
            if (memory.completedTasks.isEmpty()) {
                appendLine("_No completed tasks_")
            } else {
                memory.completedTasks.forEach { task ->
                    appendLine("### ✓ ${task.name}")
                    appendLine("- ID: ${task.id}")
                    if (task.context.isNotEmpty()) {
                        appendLine("- Context:")
                        task.context.forEach { ctx ->
                            appendLine("  - $ctx")
                        }
                    }
                    appendLine()
                }
            }
        }
    }

    private fun parseWorkingMemory(content: String): WorkingMemory {
        val memory = WorkingMemory()
        var currentTask: WorkingTask? = null
        var inContext = false
        var currentTaskId: String? = null

        content.lines().forEach { line ->
            when {
                line.startsWith("> Current task:") -> {
                    // Will set currentTaskId later
                }
                line.startsWith("### → ") || line.startsWith("### ✓ ") || line.startsWith("### ") -> {
                    // Save previous task
                    currentTask?.let { memory.tasks.add(it) }

                    val name = line.removePrefix("### → ").removePrefix("### ✓ ").removePrefix("### ").trim()
                    val status = when {
                        line.startsWith("### ✓ ") -> TaskStatus.COMPLETED
                        line.startsWith("### → ") -> TaskStatus.ACTIVE
                        else -> TaskStatus.ACTIVE
                    }
                    currentTask = WorkingTask(name = name, status = status)
                    inContext = false
                }
                line.startsWith("- ID: ") -> {
                    val id = line.removePrefix("- ID: ").trim()
                    currentTask = currentTask?.copy(id = id)
                    if (line.contains("→")) {
                        currentTaskId = id
                    }
                }
                line.startsWith("- Status: ") -> {
                    val statusStr = line.removePrefix("- Status: ").trim()
                    val status = TaskStatus.entries.find { it.name == statusStr } ?: TaskStatus.ACTIVE
                    currentTask = currentTask?.copy(status = status)
                }
                line.startsWith("- Context:") -> {
                    inContext = true
                }
                line.startsWith("  - ") && inContext -> {
                    val ctx = line.removePrefix("  - ").trim()
                    currentTask?.context?.add(ctx)
                }
                line.isBlank() -> {
                    inContext = false
                }
            }
        }

        // Add last task
        currentTask?.let { memory.tasks.add(it) }

        // Set current task
        memory.currentTaskId = currentTaskId ?: memory.activeTasks.firstOrNull()?.id

        return memory
    }

    // ==================== COMBINED CONTEXT ====================

    /**
     * Build context string from all memory layers for LLM
     * Note: Short-term memory (full dialog) is handled separately by OpenAiService
     */
    fun buildMemoryContext(chatId: String): String {
        val longTerm = readLongTermMemory()
        val working = readWorkingMemory(chatId)

        return buildString {
            // Long-term memory
            if (longTerm.profile.isNotEmpty() || longTerm.preferences.isNotEmpty() ||
                longTerm.knowledge.isNotEmpty() || longTerm.decisions.isNotEmpty()) {
                appendLine("=== LONG-TERM MEMORY (User Profile & Knowledge) ===")

                if (longTerm.profile.isNotEmpty()) {
                    appendLine("Profile:")
                    longTerm.profile.forEach { (k, v) -> appendLine("  - $k: $v") }
                }
                if (longTerm.preferences.isNotEmpty()) {
                    appendLine("Preferences:")
                    longTerm.preferences.forEach { (k, v) -> appendLine("  - $k: $v") }
                }
                if (longTerm.knowledge.isNotEmpty()) {
                    appendLine("Knowledge:")
                    longTerm.knowledge.forEach { appendLine("  - $it") }
                }
                if (longTerm.decisions.isNotEmpty()) {
                    appendLine("Important Decisions:")
                    longTerm.decisions.forEach { appendLine("  - $it") }
                }
                appendLine()
            }

            // Working memory - sub-tasks
            if (working.tasks.isNotEmpty()) {
                appendLine("=== WORKING MEMORY (Sub-Tasks in this Dialog) ===")

                val current = working.currentTask
                if (current != null) {
                    appendLine("CURRENT TASK: ${current.name}")
                    if (current.context.isNotEmpty()) {
                        appendLine("Context:")
                        current.context.forEach { appendLine("  - $it") }
                    }
                    appendLine()
                }

                val otherActive = working.activeTasks.filter { it.id != current?.id }
                if (otherActive.isNotEmpty()) {
                    appendLine("Other active tasks:")
                    otherActive.forEach { task ->
                        appendLine("  - ${task.name}")
                    }
                    appendLine()
                }

                if (working.completedTasks.isNotEmpty()) {
                    appendLine("Completed tasks:")
                    working.completedTasks.forEach { task ->
                        appendLine("  - ✓ ${task.name}")
                    }
                }
            }
        }
    }

    /**
     * Log current memory state
     */
    fun logMemoryState(chatId: String) {
        val longTerm = readLongTermMemory()
        val working = readWorkingMemory(chatId)

        println("")
        println("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
        println("┃  🧠 MEMORY STATE                                          ┃")
        println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
        println("┃  📦 LONG-TERM (global):")
        println("┃     Profile: ${longTerm.profile.size} items")
        println("┃     Preferences: ${longTerm.preferences.size} items")
        println("┃     Knowledge: ${longTerm.knowledge.size} items")
        println("┃     Decisions: ${longTerm.decisions.size} items")
        println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
        println("┃  📋 WORKING (chat: ${chatId.take(8)}...):")
        println("┃     Active tasks: ${working.activeTasks.size}")
        println("┃     Completed tasks: ${working.completedTasks.size}")
        println("┃     Current: ${working.currentTask?.name ?: "none"}")
        println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
        println("┃  💬 SHORT-TERM: full dialog (all messages)")
        println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
        println("")
    }
}
