package ru.mike.study.studyai.rag

enum class RagMode(val displayName: String) {
    NO_RAG("Без RAG"),
    RAG_ONLY("Только RAG"),
    RAG_PLUS_MODEL("RAG + Модель"),
    MCP_TOOL("RAG как инструмент")
}
