package ru.mike.study.studyai

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform