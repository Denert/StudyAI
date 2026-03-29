package ru.mike.study.studyai.rag.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.mike.study.studyai.rag.RagChunk
import ru.mike.study.studyai.rag.RagService

@Composable
fun RagScreen(
    viewModel: RagViewModel,
    ragMinScore: Float = 0.3f,
    ragTopK: Int = 5,
    ragCandidateK: Int = 20,
    ragFilterEnabled: Boolean = true,
    ragRewriteEnabled: Boolean = true,
    onMinScoreChange: (Float) -> Unit = {},
    onTopKChange: (Int) -> Unit = {},
    onCandidateKChange: (Int) -> Unit = {},
    onFilterEnabledChange: (Boolean) -> Unit = {},
    onRewriteEnabledChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val isIndexing by viewModel.isIndexing.collectAsState()
    val indexingLog by viewModel.indexingLog.collectAsState()
    val fixedChunks by viewModel.fixedChunks.collectAsState()
    val structureChunks by viewModel.structureChunks.collectAsState()
    val fixedStats by viewModel.fixedStats.collectAsState()
    val structureStats by viewModel.structureStats.collectAsState()
    val docFiles by viewModel.docFiles.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var searchInput by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RAG — Локальный индекс", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Документы", modifier = Modifier.padding(vertical = 12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Сравнение", modifier = Modifier.padding(vertical = 12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("Поиск", modifier = Modifier.padding(vertical = 12.dp))
                }
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                    Text("Настройки", modifier = Modifier.padding(vertical = 12.dp))
                }
            }

            when (selectedTab) {
                0 -> DocumentsTab(
                    docsPath = viewModel.docsPath,
                    docFiles = docFiles,
                    isIndexing = isIndexing,
                    indexingLog = indexingLog,
                    onRefresh = { viewModel.refreshDocFiles() },
                    onIndexAll = { viewModel.indexAll() },
                    onIndexFixed = { viewModel.indexStrategy("fixed") },
                    onIndexStructure = { viewModel.indexStrategy("structure") }
                )
                1 -> ComparisonTab(
                    fixedStats = fixedStats,
                    structureStats = structureStats,
                    fixedChunks = fixedChunks,
                    structureChunks = structureChunks
                )
                2 -> SearchTab(
                    searchInput = searchInput,
                    onSearchInputChange = { searchInput = it },
                    onSearch = { viewModel.search(searchInput) },
                    isSearching = isSearching,
                    searchResults = searchResults
                )
                3 -> SearchSettingsTab(
                    ragMinScore = ragMinScore,
                    ragTopK = ragTopK,
                    ragCandidateK = ragCandidateK,
                    ragFilterEnabled = ragFilterEnabled,
                    ragRewriteEnabled = ragRewriteEnabled,
                    onMinScoreChange = onMinScoreChange,
                    onTopKChange = onTopKChange,
                    onCandidateKChange = onCandidateKChange,
                    onFilterEnabledChange = onFilterEnabledChange,
                    onRewriteEnabledChange = onRewriteEnabledChange
                )
            }
        }
    }
}

@Composable
private fun SearchSettingsTab(
    ragMinScore: Float,
    ragTopK: Int,
    ragCandidateK: Int,
    ragFilterEnabled: Boolean,
    ragRewriteEnabled: Boolean,
    onMinScoreChange: (Float) -> Unit,
    onTopKChange: (Int) -> Unit,
    onCandidateKChange: (Int) -> Unit,
    onFilterEnabledChange: (Boolean) -> Unit,
    onRewriteEnabledChange: (Boolean) -> Unit
) {
    var minScoreText by remember(ragMinScore) { mutableStateOf(ragMinScore.toString()) }
    var topKText by remember(ragTopK) { mutableStateOf(ragTopK.toString()) }
    var candidateKText by remember(ragCandidateK) { mutableStateOf(ragCandidateK.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Параметры поиска (RAG_ONLY и RAG+Модель)", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Фильтрация результатов", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (ragFilterEnabled) "Включено: candidateK → minScore → topK"
                    else "Выключено: простой поиск top-5",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = ragFilterEnabled, onCheckedChange = onFilterEnabledChange)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Переформулировка запроса (rewrite)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (ragRewriteEnabled) "Включено: запрос оптимизируется через LLM перед поиском"
                    else "Выключено: поиск по исходному запросу",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = ragRewriteEnabled,
                onCheckedChange = onRewriteEnabledChange,
                enabled = ragFilterEnabled
            )
        }

        HorizontalDivider()

        Text(
            "Применяются только в режимах «Только RAG» и «RAG + Модель».",
            style = MaterialTheme.typography.bodySmall,
            color = if (ragFilterEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )

        OutlinedTextField(
            enabled = ragFilterEnabled,
            value = candidateKText,
            onValueChange = { v ->
                candidateKText = v
                v.toIntOrNull()?.let { onCandidateKChange(it) }
            },
            label = { Text("Кандидаты (candidateK)") },
            supportingText = { Text("Сколько чанков достать до фильтрации. По умолчанию: 20") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            enabled = ragFilterEnabled,
            value = minScoreText,
            onValueChange = { v ->
                minScoreText = v
                v.toFloatOrNull()?.let { onMinScoreChange(it) }
            },
            label = { Text("Порог релевантности (minScore)") },
            supportingText = { Text("Отсекать чанки ниже этого cosine similarity. Диапазон: 0.0–1.0. По умолчанию: 0.3") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            enabled = ragFilterEnabled,
            value = topKText,
            onValueChange = { v ->
                topKText = v
                v.toIntOrNull()?.let { onTopKChange(it) }
            },
            label = { Text("Итоговых чанков (topK)") },
            supportingText = { Text("Сколько чанков подать в контекст после фильтрации. По умолчанию: 5") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Текущие значения", style = MaterialTheme.typography.labelMedium)
                Text(
                    when {
                        !ragFilterEnabled -> "фильтр выключен — простой поиск top-5"
                        ragRewriteEnabled -> "rewrite → candidateK = $ragCandidateK → minScore ≥ $ragMinScore → topK = $ragTopK"
                        else -> "candidateK = $ragCandidateK → minScore ≥ $ragMinScore → topK = $ragTopK (без rewrite)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun DocumentsTab(
    docsPath: String,
    docFiles: List<String>,
    isIndexing: Boolean,
    indexingLog: String,
    onRefresh: () -> Unit,
    onIndexAll: () -> Unit,
    onIndexFixed: () -> Unit,
    onIndexStructure: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Docs folder path
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Папка с документами", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(docsPath, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(
                    "Скидывайте .md файлы в эту папку",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Files list
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Файлы (${docFiles.size})", style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }

        if (docFiles.isEmpty()) {
            Text(
                "Нет .md файлов. Добавьте файлы в папку выше.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            docFiles.forEach { file ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        file,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            }
        }

        HorizontalDivider()

        // Index buttons
        Text("Индексация", style = MaterialTheme.typography.titleSmall)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onIndexAll,
                enabled = !isIndexing && docFiles.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Индексировать оба")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onIndexFixed,
                enabled = !isIndexing && docFiles.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Fixed Size")
            }
            OutlinedButton(
                onClick = onIndexStructure,
                enabled = !isIndexing && docFiles.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Structure")
            }
        }

        if (isIndexing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (indexingLog.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    indexingLog,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ComparisonTab(
    fixedStats: RagService.IndexStats?,
    structureStats: RagService.IndexStats?,
    fixedChunks: List<RagChunk>,
    structureChunks: List<RagChunk>
) {
    if (fixedStats == null && structureStats == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Индексы не созданы. Перейдите на вкладку «Документы».")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Stats comparison table
        item {
            Text("Сравнение стратегий", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    StatsRow("", "Fixed Size", "Structure", isHeader = true)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    StatsRow("Чанков", fixedStats?.chunkCount?.toString() ?: "—", structureStats?.chunkCount?.toString() ?: "—")
                    StatsRow("Файлов", fixedStats?.fileCount?.toString() ?: "—", structureStats?.fileCount?.toString() ?: "—")
                    StatsRow("Ср. размер", fixedStats?.avgChunkSize?.let { "$it симв." } ?: "—", structureStats?.avgChunkSize?.let { "$it симв." } ?: "—")
                    StatsRow("Мин. размер", fixedStats?.minChunkSize?.let { "$it симв." } ?: "—", structureStats?.minChunkSize?.let { "$it симв." } ?: "—")
                    StatsRow("Макс. размер", fixedStats?.maxChunkSize?.let { "$it симв." } ?: "—", structureStats?.maxChunkSize?.let { "$it симв." } ?: "—")
                }
            }
        }

        // Fixed chunks preview
        if (fixedChunks.isNotEmpty()) {
            item {
                Text("Fixed Size — первые 3 чанка", style = MaterialTheme.typography.titleSmall)
            }
            items(fixedChunks.take(3)) { chunk ->
                ChunkCard(chunk)
            }
        }

        // Structure chunks preview
        if (structureChunks.isNotEmpty()) {
            item {
                Text("Structure — первые 3 чанка", style = MaterialTheme.typography.titleSmall)
            }
            items(structureChunks.take(3)) { chunk ->
                ChunkCard(chunk)
            }
        }
    }
}

@Composable
private fun SearchTab(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    searchResults: Map<String, List<Pair<RagChunk, Float>>>
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Search input
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = onSearchInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите поисковый запрос...") },
                singleLine = true
            )
            Button(onClick = onSearch, enabled = !isSearching && searchInput.isNotBlank()) {
                Icon(Icons.Default.Search, contentDescription = null)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (searchResults.isEmpty() && !isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Введите запрос для поиска по индексам", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            searchResults.forEach { (strategy, results) ->
                item {
                    val label = if (strategy == "fixed") "Fixed Size" else "Structure"
                    Text("$label (${results.size} результатов)", style = MaterialTheme.typography.titleSmall)
                }
                items(results) { (chunk, score) ->
                    SearchResultCard(chunk = chunk, score = score)
                }
            }
        }
    }
}

@Composable
private fun StatsRow(label: String, fixed: String, structure: String, isHeader: Boolean = false) {
    val style = if (isHeader) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1.5f), style = style)
        Text(fixed, modifier = Modifier.weight(1f), style = style, fontWeight = if (isHeader) FontWeight.Bold else null)
        Text(structure, modifier = Modifier.weight(1f), style = style, fontWeight = if (isHeader) FontWeight.Bold else null)
    }
}

@Composable
private fun ChunkCard(chunk: RagChunk) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(chunk.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("${chunk.charCount} симв.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(chunk.source, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chunk.section.isNotBlank() && chunk.section != chunk.title) {
                Text(chunk.section, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                chunk.content.take(200) + if (chunk.content.length > 200) "..." else "",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SearchResultCard(chunk: RagChunk, score: Float) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(chunk.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("${chunk.source} · ${chunk.charCount} симв.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "${"%.0f".format(score * 100)}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                chunk.content.take(300) + if (chunk.content.length > 300) "..." else "",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
