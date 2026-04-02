package ru.mike.study.studyai.filetools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private enum class DiffOp { KEEP, ADD, DEL }
private data class DiffLineOp(val op: DiffOp, val line: String)

class FileToolsService(private val workingDirectory: String) {

    fun isConfigured() = workingDirectory.isNotBlank()

    // ── Чтение / поиск ───────────────────────────────────────────────────────

    fun fileList(directory: String? = null, pattern: String? = null): String {
        if (!isConfigured()) return "Путь к проекту не задан в настройках."
        val dir = if (directory != null) File(workingDirectory, directory) else File(workingDirectory)
        if (!dir.exists()) return "Директория не найдена: ${dir.absolutePath}"

        val files = dir.walkTopDown()
            .filter { it.isFile }
            .filter { f -> !f.path.contains("/build/") && !f.path.contains("/.git/") && !f.path.contains("/.gradle/") }
            .filter { f -> pattern == null || f.name.endsWith(pattern.removePrefix("*")) }
            .take(300)
            .map { it.relativeTo(File(workingDirectory)).path }
            .sorted()
            .toList()

        return if (files.isEmpty()) "Файлы не найдены."
        else "Файлы (${files.size}):\n" + files.joinToString("\n")
    }

    fun fileRead(path: String): String {
        if (!isConfigured()) return "Путь к проекту не задан в настройках."
        val file = resolve(path) ?: return "Путь выходит за пределы рабочей директории."
        if (!file.exists()) return "Файл не найден: $path"
        if (!file.isFile) return "Это не файл: $path"
        val content = file.readText()
        return if (content.length > 60_000)
            content.take(60_000) + "\n\n[... обрезано — файл слишком большой]"
        else content
    }

    fun fileSearch(query: String, directory: String? = null, filePattern: String? = null): String {
        if (!isConfigured()) return "Путь к проекту не задан в настройках."
        val dir = if (directory != null) File(workingDirectory, directory) else File(workingDirectory)
        if (!dir.exists()) return "Директория не найдена."

        val results = mutableListOf<String>()
        dir.walkTopDown()
            .filter { it.isFile }
            .filter { f -> !f.path.contains("/build/") && !f.path.contains("/.git/") }
            .filter { f -> filePattern == null || f.name.endsWith(filePattern.removePrefix("*")) }
            .forEach { file ->
                if (results.size >= 200) return@forEach
                try {
                    file.readLines().forEachIndexed { i, line ->
                        if (line.contains(query, ignoreCase = true)) {
                            val rel = file.relativeTo(File(workingDirectory)).path
                            results.add("$rel:${i + 1}: ${line.trim()}")
                        }
                    }
                } catch (_: Exception) { }
            }

        return if (results.isEmpty()) "Совпадения не найдены по запросу «$query»."
        else "Найдено совпадений: ${results.size}\n\n" + results.joinToString("\n")
    }

    // ── Запись / редактирование / удаление ───────────────────────────────────

    /** Полная перезапись / создание файла. */
    fun prepareWrite(path: String, content: String): Pair<PendingFileChange, String> {
        val file = resolve(path) ?: return errorChange(path) to "Путь выходит за пределы рабочей директории."
        val oldContent = if (file.exists() && file.isFile) file.readText() else null
        val diff = computeDiff(path, oldContent, content)
        val pending = PendingFileChange(path = path, oldContent = oldContent, newContent = content, diff = diff)
        val summary = if (oldContent == null)
            "Новый файл: $path (${content.lines().size} строк)"
        else {
            val added = diff.lines().count { it.startsWith("+") && !it.startsWith("+++") }
            val removed = diff.lines().count { it.startsWith("-") && !it.startsWith("---") }
            "Изменение: $path (+$added / -$removed строк)"
        }
        return pending to summary
    }

    /** Точечная замена строки внутри файла (old_string → new_string). */
    fun prepareEdit(path: String, oldString: String, newString: String): Pair<PendingFileChange, String> {
        val file = resolve(path) ?: return errorChange(path) to "Путь выходит за пределы рабочей директории."
        if (!file.exists()) return errorChange(path) to "Файл не найден: $path"
        val oldContent = file.readText()
        if (!oldContent.contains(oldString))
            return errorChange(path) to "Строка не найдена в файле $path. Используй file_read и проверь точное содержимое."
        val newContent = oldContent.replaceFirst(oldString, newString)
        val diff = computeEditDiff(path, oldContent, oldString, newString)
        val pending = PendingFileChange(path = path, oldContent = oldContent, newContent = newContent, diff = diff)
        return pending to "Правка: $path"
    }

    /** Удаление файла. */
    fun prepareDelete(path: String): Pair<PendingFileChange, String> {
        val file = resolve(path) ?: return errorChange(path) to "Путь выходит за пределы рабочей директории."
        if (!file.exists()) return errorChange(path) to "Файл не найден: $path"
        val oldContent = file.readText()
        val diff = oldContent.lines().joinToString("\n") { "-$it" }
        val pending = PendingFileChange(
            path = path, oldContent = oldContent, newContent = null,
            diff = "--- a/$path\n+++ /dev/null\n$diff",
            isDelete = true
        )
        return pending to "Удаление: $path"
    }

    /** Применяет изменение на диск. */
    fun applyChange(change: PendingFileChange): Boolean {
        return try {
            val file = resolve(change.path) ?: return false
            if (change.isDelete) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(change.newContent ?: return false)
            }
            true
        } catch (e: Exception) {
            System.err.println("FileToolsService: ошибка: ${e.message}")
            false
        }
    }

    // ── Безопасность ─────────────────────────────────────────────────────────

    /** Возвращает файл только если он находится внутри workingDirectory. */
    private fun resolve(path: String): File? {
        if (workingDirectory.isBlank()) return null
        val root = File(workingDirectory).canonicalFile
        val target = File(workingDirectory, path).canonicalFile
        return if (target.path.startsWith(root.path)) target else null
    }

    private fun errorChange(path: String) = PendingFileChange(
        path = path, oldContent = null, newContent = null, diff = "", isApplied = false
    )

    // ── Diff ─────────────────────────────────────────────────────────────────

    /** Unified diff с контекстом (3 строки вокруг изменений). */
    fun computeDiff(path: String, oldContent: String?, newContent: String): String {
        if (oldContent == null) {
            val lines = newContent.lines()
            val sb = StringBuilder()
            sb.appendLine("--- /dev/null")
            sb.appendLine("+++ b/$path")
            lines.take(120).forEach { sb.appendLine("+$it") }
            if (lines.size > 120) sb.appendLine("... (ещё ${lines.size - 120} строк)")
            return sb.toString()
        }

        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        // Trim to avoid O(n²) blowup on huge files; show warning if truncated
        val maxLines = 600
        val ol = oldLines.take(maxLines)
        val nl = newLines.take(maxLines)
        val truncated = oldLines.size > maxLines || newLines.size > maxLines

        // True LCS via DP table
        val m = ol.size
        val n = nl.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (ol[i - 1] == nl[j - 1]) dp[i - 1][j - 1] + 1
                           else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }

        // Backtrack to build edit script
        val ops = ArrayDeque<DiffLineOp>()
        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && ol[i - 1] == nl[j - 1] -> {
                    ops.addFirst(DiffLineOp(DiffOp.KEEP, ol[i - 1])); i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    ops.addFirst(DiffLineOp(DiffOp.ADD, nl[j - 1])); j--
                }
                else -> {
                    ops.addFirst(DiffLineOp(DiffOp.DEL, ol[i - 1])); i--
                }
            }
        }

        val changedIdx = ops.indices.filter { ops[it].op != DiffOp.KEEP }.toSet()
        if (changedIdx.isEmpty() && !truncated)
            return "--- a/$path\n+++ b/$path\n(файл не изменился)"

        // Build hunks with 3-line context
        val context = 3
        val inHunk = BooleanArray(ops.size)
        changedIdx.forEach { idx ->
            for (c in maxOf(0, idx - context)..minOf(ops.size - 1, idx + context)) inHunk[c] = true
        }

        val sb = StringBuilder()
        sb.appendLine("--- a/$path")
        sb.appendLine("+++ b/$path")
        if (truncated) sb.appendLine("... (файл обрезан до $maxLines строк для показа diff)")

        var k = 0
        while (k < ops.size) {
            if (!inHunk[k]) { k++; continue }
            while (k < ops.size && inHunk[k]) {
                val op = ops[k]
                when (op.op) {
                    DiffOp.KEEP -> sb.appendLine(" ${op.line}")
                    DiffOp.ADD  -> sb.appendLine("+${op.line}")
                    DiffOp.DEL  -> sb.appendLine("-${op.line}")
                }
                k++
            }
            if (k < ops.size) sb.appendLine("@@ ─────────────────────────────────── @@")
        }
        return sb.toString()
    }

    /** Diff для точечной правки — показывает только изменённый регион с контекстом. */
    private fun computeEditDiff(path: String, oldContent: String, oldString: String, newString: String): String {
        val newContent = oldContent.replaceFirst(oldString, newString)
        return computeDiff(path, oldContent, newContent)
    }

    companion object {
        val toolSchemas: List<Pair<String, String>> = listOf(
            "file_list" to """{"type":"object","properties":{"directory":{"type":"string","description":"Поддиректория (необязательно)"},"pattern":{"type":"string","description":"Расширение: *.kt, *.md (необязательно)"}}}""",
            "file_read" to """{"type":"object","properties":{"path":{"type":"string","description":"Путь к файлу относительно корня проекта"}},"required":["path"]}""",
            "file_search" to """{"type":"object","properties":{"query":{"type":"string","description":"Строка для поиска"},"directory":{"type":"string","description":"Директория (необязательно)"},"file_pattern":{"type":"string","description":"Расширение файлов (необязательно)"}},"required":["query"]}""",
            "file_write" to """{"type":"object","properties":{"path":{"type":"string","description":"Путь к файлу"},"content":{"type":"string","description":"Полное содержимое файла"}},"required":["path","content"]}""",
            "file_edit" to """{"type":"object","properties":{"path":{"type":"string","description":"Путь к файлу"},"old_string":{"type":"string","description":"Точная строка/блок для замены (должна присутствовать в файле)"},"new_string":{"type":"string","description":"Новая строка/блок на замену"}},"required":["path","old_string","new_string"]}""",
            "file_delete" to """{"type":"object","properties":{"path":{"type":"string","description":"Путь к файлу для удаления"}},"required":["path"]}""",
            "agent_todo" to """{"type":"object","properties":{"todos":{"type":"array","items":{"type":"string"},"description":"Актуальный список задач агента. Обновляй при каждом изменении плана."}},"required":["todos"]}"""
        )

        val toolDescriptions: Map<String, String> = mapOf(
            "file_list"   to "Список файлов проекта. Используй для изучения структуры.",
            "file_read"   to "Читает файл. Вызывай перед file_edit или file_write.",
            "file_search" to "Ищет текст по всем файлам проекта. Возвращает файл:строка:текст.",
            "file_write"  to "Создаёт новый файл или полностью заменяет существующий. Показывает diff, требует подтверждения.",
            "file_edit"   to "Точечная замена строки/блока внутри файла без перезаписи всего содержимого. Показывает diff, требует подтверждения. Предпочитай вместо file_write при изменении части файла.",
            "file_delete" to "Удаляет файл. Показывает diff (все строки красные), требует подтверждения.",
            "agent_todo"  to "Обновляет список задач агента. Вызывай в начале работы и при каждом изменении плана чтобы отслеживать прогресс."
        )

        fun extractString(args: JsonObject?, key: String): String? =
            args?.get(key)?.jsonPrimitive?.content

        fun extractStringList(args: JsonObject?, key: String): List<String>? =
            args?.get(key)?.let { el ->
                try {
                    el.toString().trim('[', ']')
                        .split(",")
                        .map { it.trim().trim('"') }
                        .filter { it.isNotBlank() }
                } catch (_: Exception) { null }
            }
    }
}
