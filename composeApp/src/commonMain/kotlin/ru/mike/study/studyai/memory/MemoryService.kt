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
    LONG_TERM    // User profile, decisions, knowledge (per profile)
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
    val context: MutableList<String> = mutableListOf(),
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
 * Profile data (Long-term memory)
 */
data class Profile(
    val name: String,
    val data: MutableMap<String, String> = mutableMapOf(),
    val preferences: MutableMap<String, String> = mutableMapOf(),
    val knowledge: MutableList<String> = mutableListOf(),
    val decisions: MutableList<String> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Service for managing 3-layer memory system with profiles
 *
 * Profiles:
 * - Multiple profiles supported
 * - Each profile is a separate .md file
 * - One active profile at a time
 *
 * Layers:
 * 1. SHORT_TERM - Full dialog (all messages, managed by OpenAiService)
 * 2. WORKING - Sub-tasks/topics within dialog (per-chat .md file)
 * 3. LONG_TERM - Profile data (per-profile .md file)
 */
class MemoryService {

    companion object {
        const val DEFAULT_PROFILE_NAME = "Default"
    }

    private val memoryDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".studyai/memory")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val profilesDir: File by lazy {
        val dir = File(memoryDir, "profiles")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val activeProfileFile: File
        get() = File(memoryDir, "active_profile.txt")

    private fun profileFile(profileName: String): File {
        val safeName = profileName.replace(Regex("[^a-zA-Zа-яА-Я0-9_-]"), "_")
        return File(profilesDir, "$safeName.md")
    }

    private fun workingMemoryFile(chatId: String): File {
        return File(memoryDir, "working_memory_$chatId.md")
    }

    // ==================== PROFILES ====================

    /**
     * Get list of all available profiles
     */
    fun getAllProfiles(): List<String> {
        val profiles = profilesDir.listFiles { file -> file.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

        // Ensure default profile exists
        if (profiles.isEmpty()) {
            createProfile(DEFAULT_PROFILE_NAME)
            return listOf(DEFAULT_PROFILE_NAME)
        }

        return profiles
    }

    /**
     * Get active profile name
     */
    fun getActiveProfileName(): String {
        return if (activeProfileFile.exists()) {
            val name = activeProfileFile.readText().trim()
            if (name.isNotEmpty() && profileFile(name).exists()) {
                name
            } else {
                DEFAULT_PROFILE_NAME
            }
        } else {
            DEFAULT_PROFILE_NAME
        }
    }

    /**
     * Set active profile
     */
    fun setActiveProfile(profileName: String) {
        if (!profileFile(profileName).exists()) {
            createProfile(profileName)
        }
        activeProfileFile.writeText(profileName)
        println("┃  👤 Active profile set to: $profileName")
    }

    /**
     * Create a new profile
     */
    fun createProfile(profileName: String): Profile {
        val profile = Profile(name = profileName)
        saveProfile(profile)
        println("┃  👤 Profile created: $profileName")
        return profile
    }

    /**
     * Delete a profile
     */
    fun deleteProfile(profileName: String): Boolean {
        if (profileName == DEFAULT_PROFILE_NAME) {
            println("⚠ Cannot delete default profile")
            return false
        }

        val file = profileFile(profileName)
        if (file.exists()) {
            file.delete()
            println("┃  👤 Profile deleted: $profileName")

            // Switch to default if active profile was deleted
            if (getActiveProfileName() == profileName) {
                setActiveProfile(DEFAULT_PROFILE_NAME)
            }
            return true
        }
        return false
    }

    /**
     * Read active profile data
     */
    fun readActiveProfile(): Profile {
        val profileName = getActiveProfileName()
        return readProfile(profileName)
    }

    /**
     * Read specific profile
     */
    fun readProfile(profileName: String): Profile {
        val file = profileFile(profileName)
        if (!file.exists()) {
            return Profile(name = profileName)
        }

        return try {
            parseProfile(profileName, file.readText())
        } catch (e: Exception) {
            println("Error reading profile $profileName: ${e.message}")
            Profile(name = profileName)
        }
    }

    /**
     * Save profile
     */
    fun saveProfile(profile: Profile) {
        try {
            val file = profileFile(profile.name)
            file.writeText(formatProfile(profile))
            println("┃  💾 Profile saved: ${profile.name}")
        } catch (e: Exception) {
            println("Error saving profile ${profile.name}: ${e.message}")
        }
    }

    /**
     * Update active profile
     */
    fun updateActiveProfile(
        dataUpdates: Map<String, String> = emptyMap(),
        preferenceUpdates: Map<String, String> = emptyMap(),
        newKnowledge: List<String> = emptyList(),
        newDecisions: List<String> = emptyList()
    ) {
        val profile = readActiveProfile()

        profile.data.putAll(dataUpdates)
        profile.preferences.putAll(preferenceUpdates)
        profile.knowledge.addAll(newKnowledge.filter { it !in profile.knowledge })
        profile.decisions.addAll(newDecisions.filter { it !in profile.decisions })

        saveProfile(profile)
    }

    private fun formatProfile(profile: Profile): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return buildString {
            appendLine("# Profile: ${profile.name}")
            appendLine("> Last updated: $timestamp")
            appendLine()

            appendLine("## Profile Data")
            if (profile.data.isEmpty()) {
                appendLine("_No profile data yet_")
            } else {
                profile.data.forEach { (key, value) ->
                    appendLine("- **$key**: $value")
                }
            }
            appendLine()

            appendLine("## Preferences")
            if (profile.preferences.isEmpty()) {
                appendLine("_No preferences yet_")
            } else {
                profile.preferences.forEach { (key, value) ->
                    appendLine("- **$key**: $value")
                }
            }
            appendLine()

            appendLine("## Knowledge")
            if (profile.knowledge.isEmpty()) {
                appendLine("_No knowledge stored yet_")
            } else {
                profile.knowledge.forEach { item ->
                    appendLine("- $item")
                }
            }
            appendLine()

            appendLine("## Important Decisions")
            if (profile.decisions.isEmpty()) {
                appendLine("_No decisions recorded yet_")
            } else {
                profile.decisions.forEach { item ->
                    appendLine("- $item")
                }
            }
        }
    }

    private fun parseProfile(profileName: String, content: String): Profile {
        val profile = Profile(name = profileName)
        var currentSection = ""

        content.lines().forEach { line ->
            when {
                line.startsWith("## Profile Data") -> currentSection = "data"
                line.startsWith("## Preferences") -> currentSection = "preferences"
                line.startsWith("## Knowledge") -> currentSection = "knowledge"
                line.startsWith("## Important Decisions") -> currentSection = "decisions"
                line.startsWith("- **") && line.contains("**:") -> {
                    val keyEnd = line.indexOf("**:", 4)
                    if (keyEnd > 4) {
                        val key = line.substring(4, keyEnd)
                        val value = line.substring(keyEnd + 3).trim()
                        when (currentSection) {
                            "data" -> profile.data[key] = value
                            "preferences" -> profile.preferences[key] = value
                        }
                    }
                }
                line.startsWith("- ") && currentSection in listOf("knowledge", "decisions") -> {
                    val item = line.substring(2).trim()
                    if (item.isNotEmpty() && !item.startsWith("_")) {
                        when (currentSection) {
                            "knowledge" -> profile.knowledge.add(item)
                            "decisions" -> profile.decisions.add(item)
                        }
                    }
                }
            }
        }

        return profile
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

        if (memory.currentTaskId == task.id) {
            memory.currentTaskId = memory.activeTasks.firstOrNull()?.id
        }

        saveWorkingMemory(chatId, memory)
    }

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
                line.startsWith("> Current task:") -> { }
                line.startsWith("### → ") || line.startsWith("### ✓ ") || line.startsWith("### ") -> {
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

        currentTask?.let { memory.tasks.add(it) }
        memory.currentTaskId = currentTaskId ?: memory.activeTasks.firstOrNull()?.id

        return memory
    }

    // ==================== COMBINED CONTEXT ====================

    /**
     * Build context string from all memory layers for LLM
     */
    fun buildMemoryContext(chatId: String): String {
        val profile = readActiveProfile()
        val working = readWorkingMemory(chatId)

        return buildString {
            // Profile (Long-term memory)
            if (profile.data.isNotEmpty() || profile.preferences.isNotEmpty() ||
                profile.knowledge.isNotEmpty() || profile.decisions.isNotEmpty()) {
                appendLine("=== PROFILE: ${profile.name} ===")

                if (profile.data.isNotEmpty()) {
                    appendLine("User Info:")
                    profile.data.forEach { (k, v) -> appendLine("  - $k: $v") }
                }
                if (profile.preferences.isNotEmpty()) {
                    appendLine("Preferences:")
                    profile.preferences.forEach { (k, v) -> appendLine("  - $k: $v") }
                }
                if (profile.knowledge.isNotEmpty()) {
                    appendLine("Knowledge:")
                    profile.knowledge.forEach { appendLine("  - $it") }
                }
                if (profile.decisions.isNotEmpty()) {
                    appendLine("Important Decisions:")
                    profile.decisions.forEach { appendLine("  - $it") }
                }
                appendLine()
            }

            // Working memory - sub-tasks
            if (working.tasks.isNotEmpty()) {
                appendLine("=== WORKING MEMORY (Sub-Tasks) ===")

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
        val profile = readActiveProfile()
        val working = readWorkingMemory(chatId)

        println("")
        println("┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
        println("┃  🧠 MEMORY STATE                                          ┃")
        println("┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫")
        println("┃  👤 PROFILE: ${profile.name}")
        println("┃     Data: ${profile.data.size} items")
        println("┃     Preferences: ${profile.preferences.size} items")
        println("┃     Knowledge: ${profile.knowledge.size} items")
        println("┃     Decisions: ${profile.decisions.size} items")
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
