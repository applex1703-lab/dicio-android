package org.stypox.dicio.llm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

// Writes must not be cancelled when a lazy item or its screen leaves composition.
private val settingsOperations = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private val operationsMutex = Mutex()

@HiltViewModel
class PolzaSettingsViewModel @Inject constructor(private val settings: PolzaSettings) : ViewModel() {
    var enabled by mutableStateOf(false)
        private set
    var model by mutableStateOf("")
        private set
    var key by mutableStateOf("")
        private set
    var removeKey by mutableStateOf(false)
        private set
    var keyStatus by mutableStateOf(PolzaSettings.KeyStatus.ABSENT)
        private set
    var status by mutableStateOf("Загрузка настроек…")
        private set
    var ready by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var recoveryAvailable by mutableStateOf(false)
        private set
    private var savedEnabled = false
    private var savedModel = ""
    private var revision = 0L
    val dirty: Boolean get() = enabled != savedEnabled || model != savedModel || key.isNotEmpty() || removeKey

    init {
        reload()
    }

    fun reload(discardDraft: Boolean = false) {
        if (saving) return
        if (dirty && !discardDraft) { status = "Есть несохранённый черновик. Подтвердите его удаление перед загрузкой."; return }
        if (!operationsMutex.tryLock()) { status = "Другая операция выполняется. Повторите загрузку."; return }
        saving = true
        ready = false
        recoveryAvailable = false
        status = "Загрузка настроек…"
        settingsOperations.launch {
            try {
                applyLoaded(withContext(Dispatchers.IO) { settings.loadEditor() })
                status = ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: PolzaSettings.EditorReadException) {
                revision = error.revision
                recoveryAvailable = true
                status = "Не удалось прочитать настройки. Можно подтвердить сброс повреждённого файла или повторить загрузку."
            }
            catch (_: Exception) { status = "Не удалось прочитать настройки RouterAI." }
            finally { saving = false; operationsMutex.unlock() }
        }
    }

    private fun applyLoaded(state: PolzaSettings.EditorState) {
        enabled = state.enabled
        model = state.model
        savedEnabled = enabled
        savedModel = model
        keyStatus = state.keyStatus
        revision = state.revision
        key = ""
        removeKey = false
        ready = true
        recoveryAvailable = false
    }

    fun updateEnabled(value: Boolean) { enabled = value; status = "" }
    fun updateModel(value: String) { model = value; status = "" }
    fun updateKey(value: String) { key = value; status = "" }
    fun updateRemoveKey(value: Boolean) { removeKey = value; status = "" }

    override fun onCleared() {
        key = ""
        super.onCleared()
    }

    fun save() {
        if (!ready || saving) return
        if (removeKey && enabled) {
            status = "Чтобы удалить ключ, сначала выключите RouterAI в черновике и сохраните изменения."
            return
        }
        if (!operationsMutex.tryLock()) { status = "Другая операция выполняется. Повторите сохранение."; return }
        saving = true
        val newEnabled = enabled
        val newModel = model
        val newKey = if (removeKey) "" else key
        val deleteKey = removeKey
        val expectedRevision = revision
        settingsOperations.launch {
            try {
                withContext(Dispatchers.IO) { settings.saveEditor(newEnabled, newModel, newKey, deleteKey, expectedRevision) }
                key = ""
                removeKey = false
                ready = false
                status = "Сохранено, но не удалось повторно прочитать настройки."
                applyLoaded(withContext(Dispatchers.IO) { settings.loadEditor() })
                status = "Сохранено. Подключение не проверено."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PolzaSettings.StaleEditorException) {
                ready = false
                status = "Настройки изменены в другом редакторе. Черновик сохранён; загрузите настройки заново после подтверждения."
            }
            catch (_: PolzaSettings.UncertainWriteException) {
                ready = false
                status = "Не удалось подтвердить запись: настройки могли измениться. Черновик сохранён; загрузите настройки заново после подтверждения."
            }
            catch (error: PolzaSettings.EditorReadException) {
                revision = error.revision
                ready = false
                recoveryAvailable = true
                status = "Сохранено, но настройки не удалось прочитать. Можно подтвердить сброс или повторить загрузку."
            }
            catch (_: Exception) {
                if (ready) status = "Не удалось сохранить: проверьте ключ, модель и доступность хранилища."
            } finally { saving = false; operationsMutex.unlock() }
        }
    }

    fun reset() {
        if ((!ready && !recoveryAvailable) || saving) return
        if (!operationsMutex.tryLock()) { status = "Другая операция выполняется. Повторите сброс."; return }
        saving = true
        settingsOperations.launch {
            try {
                withContext(Dispatchers.IO) { settings.reset(revision) }
                key = ""
                ready = false
                status = "Сброс выполнен, но не удалось повторно прочитать настройки."
                applyLoaded(withContext(Dispatchers.IO) { settings.loadEditor() })
                status = "Настройки и сохранённый API-ключ удалены."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PolzaSettings.StaleEditorException) {
                ready = false
                recoveryAvailable = false
                status = "Настройки изменены в другом редакторе. Сброс не выполнен; загрузите настройки заново."
            }
            catch (error: PolzaSettings.EditorReadException) {
                revision = error.revision
                ready = false
                recoveryAvailable = true
                status = "Сброс выполнен, но настройки не удалось прочитать. Повторите загрузку или подтвердите новый сброс."
            }
            catch (_: Exception) { ready = false; recoveryAvailable = false; status = "Не удалось подтвердить сброс. Загрузите настройки заново." }
            finally { saving = false; operationsMutex.unlock() }
        }
    }
}
