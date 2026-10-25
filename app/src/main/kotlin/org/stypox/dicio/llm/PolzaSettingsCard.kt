package org.stypox.dicio.llm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PolzaSettingsCard() {
    val context = LocalContext.current.applicationContext
    val settings = remember { PolzaSettings(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val config = withContext(Dispatchers.IO) { settings.load() }
            enabled = config.enabled
            model = config.model
            key = config.apiKey
            ready = true
        } catch (_: Exception) {
            status = "Не удалось прочитать настройки polza.ai."
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text("polza.ai — текстовые ответы LLM (экспериментально)")
        Text("Включённый режим отправляет запросы провайдеру вместо обычных навыков. " +
            "Управление приложениями через LLM пока не подключено. Запросы могут быть платными.")
        Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = ready && !saving)
        OutlinedTextField(value = model, onValueChange = { model = it },
            label = { Text("ID модели из каталога polza.ai") }, singleLine = true)
        OutlinedTextField(value = key, onValueChange = { key = it },
            label = { Text("API-ключ (пустое поле удаляет ключ)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Button(enabled = ready && !saving, onClick = {
            saving = true
            scope.launch {
                try {
                    require(!enabled || (key.isNotBlank() && model.isNotBlank())) {
                        "Для включения укажите ключ и модель."
                    }
                    withContext(Dispatchers.IO) { settings.save(PolzaSettings.Config(enabled, model, key)) }
                    status = "Сохранено. Проверка подключения — текстовым запросом на главном экране."
                } catch (_: Exception) {
                    status = "Не удалось сохранить: проверьте ключ, модель и доступность хранилища."
                } finally { saving = false }
            }
        }) { Text("Сохранить") }
        Text(status)
    }
}
