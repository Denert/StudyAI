package ru.mike.study.studyai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ru.mike.study.studyai.config.LlmProvider

@Composable
fun ModelParamsScreen(
    temperature: Float,
    maxTokens: Int,
    numCtx: Int,
    provider: LlmProvider,
    onSave: (temperature: Float, maxTokens: Int, numCtx: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var tempValue by remember { mutableStateOf(temperature) }
    var maxTokensValue by remember { mutableStateOf(maxTokens.toFloat()) }
    var numCtxValue by remember { mutableStateOf(numCtx.toFloat()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Параметры модели", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                HorizontalDivider()

                // Temperature
                ParamSlider(
                    label = "Temperature",
                    value = tempValue,
                    valueText = "%.2f".format(tempValue),
                    range = 0f..2f,
                    steps = 39,
                    description = "Чем выше — тем креативнее и менее предсказуемые ответы. Для кода и Q&A: 0.1–0.4. Для генерации идей: 0.7–1.2.",
                    onValueChange = { tempValue = it }
                )

                // Max tokens
                ParamSlider(
                    label = "Max tokens",
                    value = maxTokensValue,
                    valueText = maxTokensValue.toInt().toString(),
                    range = 256f..4096f,
                    steps = 14,
                    description = "Максимальная длина ответа модели. Для коротких ответов: 512–1024. Для развёрнутых объяснений: 2048–4096.",
                    onValueChange = { maxTokensValue = it }
                )

                // Num ctx (Ollama only)
                if (provider == LlmProvider.OLLAMA) {
                    ParamSlider(
                        label = "Context window (num_ctx)",
                        value = numCtxValue,
                        valueText = numCtxValue.toInt().toString(),
                        range = 512f..16384f,
                        steps = 30,
                        description = "Размер контекстного окна в токенах. Больше — больше памяти о разговоре, но медленнее. llama3.1:8b поддерживает до 128k, но 4096–8192 оптимально.",
                        onValueChange = { numCtxValue = it }
                    )
                }

                HorizontalDivider()

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(tempValue, maxTokensValue.toInt(), numCtxValue.toInt())
                        onDismiss()
                    }) {
                        Text("Применить")
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    description: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    valueText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
