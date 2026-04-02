package ru.mike.study.studyai.filetools

data class PendingFileChange(
    val path: String,
    val oldContent: String?,    // null — новый файл
    val newContent: String?,    // null — удаление файла
    val diff: String,
    val isDelete: Boolean = false,
    val isApplied: Boolean? = null  // null=ожидание, true=применено, false=отменено
)
