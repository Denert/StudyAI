package ru.mike.study.studyai

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ru.mike.study.studyai.config.ApiConfig
import ru.mike.study.studyai.ui.ChatScreen
import ru.mike.study.studyai.viewmodel.ChatViewModel

@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            val viewModel = remember { ChatViewModel(ApiConfig.apiKey) }
            ChatScreen(viewModel)
        }
    }
}