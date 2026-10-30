package org.stypox.dicio.llm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

internal fun showRemoveKey(ready: Boolean, keyStatus: PolzaSettings.KeyStatus, removeKey: Boolean) =
    ready && (keyStatus != PolzaSettings.KeyStatus.ABSENT || removeKey)

@Composable
fun PolzaSettingsCard() {
    val vm: PolzaSettingsViewModel = hiltViewModel()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var confirmRemoveKey by remember { mutableStateOf(false) }
    if (confirmRemoveKey) AlertDialog(
        onDismissRequest = { confirmRemoveKey = false },
        title = { Text("Удалить сохранённый ключ?") },
        text = { Text("При сохранении ключ будет удалён вместе с другими применяемыми изменениями.") },
        confirmButton = { TextButton(onClick = { confirmRemoveKey = false; vm.save() }) { Text("Удалить и сохранить") } },
        dismissButton = { TextButton(onClick = { confirmRemoveKey = false }) { Text("Отмена") } },
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Сбросить настройки?") },
        text = { Text("Сохранённые настройки и ключ будут удалены, несохранённый черновик потерян.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; vm.reset() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
    )
    if (confirmReload) AlertDialog(
        onDismissRequest = { confirmReload = false },
        title = { Text("Отбросить черновик?") },
        text = { Text("Несохранённые изменения, включая введённый ключ, будут потеряны после успешной загрузки.") },
        confirmButton = { TextButton(onClick = { confirmReload = false; vm.reload(discardDraft = true) }) { Text("Загрузить") } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("Отмена") } },
    )
    Column(Modifier.padding(16.dp)) {
        Text("RouterAI")
        Text("При включении запросы, приложения и данные медиасессий отправляются RouterAI; запросы могут быть платными.")
        if (vm.ready && vm.dirty) Text("Не сохранено")
        Text("Режим RouterAI (черновик)")
        Switch(checked = vm.enabled, onCheckedChange = vm::updateEnabled,
            enabled = vm.ready && !vm.saving,
            modifier = Modifier.semantics { contentDescription = "Режим RouterAI, черновик" })
        OutlinedTextField(value = vm.model, onValueChange = vm::updateModel,
            label = { Text("ID модели из каталога RouterAI") }, singleLine = true,
            enabled = vm.ready && !vm.saving)
        if (vm.ready && vm.keyStatus != PolzaSettings.KeyStatus.ABSENT) Text(when (vm.keyStatus) {
            PolzaSettings.KeyStatus.AVAILABLE -> "Ключ сохранён."
            PolzaSettings.KeyStatus.UNREADABLE -> "Ключ недоступен: замените или удалите его."
            PolzaSettings.KeyStatus.ABSENT -> ""
        })
        OutlinedTextField(value = vm.key, onValueChange = vm::updateKey,
            label = { Text("Новый API-ключ (пусто — сохранить текущий)") }, singleLine = true,
            enabled = vm.ready && !vm.saving && !vm.removeKey,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation())
        if (showRemoveKey(vm.ready, vm.keyStatus, vm.removeKey)) {
            Text("Удалить ключ при сохранении")
            Switch(checked = vm.removeKey, onCheckedChange = vm::updateRemoveKey,
                enabled = !vm.saving,
                modifier = Modifier.semantics { contentDescription = "Удалить ключ при сохранении" })
            if (vm.removeKey && vm.enabled) Text("Для удаления выключите RouterAI в черновике.")
        }
        Button(enabled = vm.ready && !vm.saving, onClick = {
            if (vm.removeKey && !vm.enabled) confirmRemoveKey = true else vm.save()
        }) { Text("Сохранить") }
        if (vm.recoveryAvailable) {
            Button(enabled = !vm.saving, onClick = { confirmReset = true }) { Text("Сбросить настройки…") }
        }
        if (!vm.ready && !vm.saving) {
            Button(onClick = { if (vm.dirty) confirmReload = true else vm.reload() }) { Text("Загрузить настройки заново") }
        }
        if (vm.status.isNotEmpty()) Text(vm.status)
    }
}
