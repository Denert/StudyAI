package ru.mike.study.studyai.crm

import kotlinx.serialization.Serializable

enum class TicketPriority { LOW, MEDIUM, HIGH, CRITICAL }
enum class TicketStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }

@Serializable
data class TicketUser(
    val id: String,
    val name: String,
    val email: String
)

@Serializable
data class CrmTicket(
    val id: String,
    val number: Int,
    val title: String,
    val description: String,
    val priority: TicketPriority,
    val status: TicketStatus,
    val user: TicketUser,
    val createdAt: String,
    val tags: List<String> = emptyList(),
    val stepsToReproduce: String? = null,
    val affectedVersion: String? = null
)
