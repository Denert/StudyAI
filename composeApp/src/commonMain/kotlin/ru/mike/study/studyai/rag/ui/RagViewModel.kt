package ru.mike.study.studyai.rag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.mike.study.studyai.rag.RagChunk
import ru.mike.study.studyai.rag.RagLogger
import ru.mike.study.studyai.rag.RagService

class RagViewModel(private val ragService: RagService) : ViewModel() {

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val _indexingLog = MutableStateFlow("")
    val indexingLog: StateFlow<String> = _indexingLog.asStateFlow()

    private val _fixedChunks = MutableStateFlow<List<RagChunk>>(emptyList())
    val fixedChunks: StateFlow<List<RagChunk>> = _fixedChunks.asStateFlow()

    private val _structureChunks = MutableStateFlow<List<RagChunk>>(emptyList())
    val structureChunks: StateFlow<List<RagChunk>> = _structureChunks.asStateFlow()

    private val _fixedStats = MutableStateFlow<RagService.IndexStats?>(null)
    val fixedStats: StateFlow<RagService.IndexStats?> = _fixedStats.asStateFlow()

    private val _structureStats = MutableStateFlow<RagService.IndexStats?>(null)
    val structureStats: StateFlow<RagService.IndexStats?> = _structureStats.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<Map<String, List<Pair<RagChunk, Float>>>>(emptyMap())
    val searchResults: StateFlow<Map<String, List<Pair<RagChunk, Float>>>> = _searchResults.asStateFlow()

    private val _docFiles = MutableStateFlow<List<String>>(emptyList())
    val docFiles: StateFlow<List<String>> = _docFiles.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val docsPath: String get() = ragService.docsDir.absolutePath

    init {
        RagLogger.init()
        loadExisting()
        refreshDocFiles()
    }

    fun refreshDocFiles() {
        _docFiles.value = ragService.getDocFiles()
    }

    fun indexAll() {
        viewModelScope.launch {
            _isIndexing.value = true
            _indexingLog.value = ""
            try {
                val fixedStats = ragService.indexDocuments("fixed") { msg ->
                    _indexingLog.value += "[Fixed] $msg\n"
                }
                _fixedStats.value = fixedStats
                _fixedChunks.value = ragService.getChunks("fixed")

                val structureStats = ragService.indexDocuments("structure") { msg ->
                    _indexingLog.value += "[Structure] $msg\n"
                }
                _structureStats.value = structureStats
                _structureChunks.value = ragService.getChunks("structure")

                _indexingLog.value += "\n✓ Оба индекса созданы"
            } catch (e: Exception) {
                _indexingLog.value += "❌ Ошибка: ${e.message}"
            }
            _isIndexing.value = false
        }
    }

    fun indexStrategy(strategy: String) {
        viewModelScope.launch {
            _isIndexing.value = true
            _indexingLog.value = ""
            try {
                val stats = ragService.indexDocuments(strategy) { msg ->
                    _indexingLog.value += "$msg\n"
                }
                if (strategy == "fixed") {
                    _fixedStats.value = stats
                    _fixedChunks.value = ragService.getChunks("fixed")
                } else {
                    _structureStats.value = stats
                    _structureChunks.value = ragService.getChunks("structure")
                }
            } catch (e: Exception) {
                _indexingLog.value += "❌ Ошибка: ${e.message}"
            }
            _isIndexing.value = false
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        _searchQuery.value = query
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = mutableMapOf<String, List<Pair<RagChunk, Float>>>()
                if (ragService.hasIndex("fixed")) {
                    results["fixed"] = ragService.search(query, "fixed")
                }
                if (ragService.hasIndex("structure")) {
                    results["structure"] = ragService.search(query, "structure")
                }
                _searchResults.value = results
            } catch (e: Exception) {
                _searchResults.value = emptyMap()
            }
            _isSearching.value = false
        }
    }

    private fun loadExisting() {
        _fixedChunks.value = ragService.getChunks("fixed")
        _structureChunks.value = ragService.getChunks("structure")
        if (_fixedChunks.value.isNotEmpty()) {
            val chunks = _fixedChunks.value
            _fixedStats.value = RagService.IndexStats(
                fileCount = chunks.map { it.source }.distinct().size,
                chunkCount = chunks.size,
                strategy = "fixed",
                avgChunkSize = chunks.map { it.charCount }.average().toInt(),
                minChunkSize = chunks.minOf { it.charCount },
                maxChunkSize = chunks.maxOf { it.charCount }
            )
        }
        if (_structureChunks.value.isNotEmpty()) {
            val chunks = _structureChunks.value
            _structureStats.value = RagService.IndexStats(
                fileCount = chunks.map { it.source }.distinct().size,
                chunkCount = chunks.size,
                strategy = "structure",
                avgChunkSize = chunks.map { it.charCount }.average().toInt(),
                minChunkSize = chunks.minOf { it.charCount },
                maxChunkSize = chunks.maxOf { it.charCount }
            )
        }
    }
}
