package org.stypox.dicio.io.input.stt_popup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.voice.VoiceInputCoordinator
import javax.inject.Inject

@HiltViewModel
class SttPopupViewModel @Inject constructor(
    application: Application,
    val sttInputDevice: SttInputDeviceWrapper,
    private val coordinator: VoiceInputCoordinator,
) : AndroidViewModel(application) {

    private val _textFieldValue = MutableStateFlow("")
    val textFieldValue: StateFlow<String> = _textFieldValue
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var lastFinalUtterances: List<Pair<String, Float>> = listOf()
    private var lastTextFieldInput = ""


    fun setTextFieldValue(value: String) {
        lastTextFieldInput = value
        _textFieldValue.value = value
        if (lastFinalUtterances.isNotEmpty() &&
            lastFinalUtterances[0].first.trim() != value.trim()) {
            // the user manually set the text field value to something else,
            // so ditch all utterance alternatives and keep only what the user said
            lastFinalUtterances = listOf()
        }
    }

    fun getUtterancesWithConfidence(): List<Pair<String, Float>> {
        return lastFinalUtterances.takeIf {
            it.isNotEmpty() && it[0].first == _textFieldValue.value
        } ?: run {
            // The displayed value may be a partial or manual edit, not a final hypothesis.
            listOf(Pair(_textFieldValue.value, 1.0f))
        }
    }

    fun onSttClick() {
        _error.value = null
        if (!coordinator.startPopup(this, this::onReceiveInputEvent)) {
            _error.value = "Распознавание речи недоступно или занято другим запросом"
        }
    }

    fun onSttLoadAfterPermissionRequest() {
        sttInputDevice.tryLoad(null)
    }

    fun releaseInput() {
        coordinator.release(this)
    }

    override fun onCleared() {
        coordinator.release(this)
        super.onCleared()
    }

    private fun onReceiveInputEvent(inputEvent: InputEvent) {
        when (inputEvent) {
            InputEvent.Started -> Unit
            is InputEvent.Partial -> {
                // the text field will show the partial value, but the previous value will be kept
                _textFieldValue.value = inputEvent.utterance
            }
            is InputEvent.Error -> {
                _error.value = inputEvent.throwable.message ?: "Ошибка распознавания речи"
                _textFieldValue.value = lastTextFieldInput
            }
            InputEvent.None -> {
                // in case of error or in case the user said nothing, restore the previous value
                _textFieldValue.value = lastTextFieldInput
            }
            is InputEvent.Final -> {
                // receiving a final event from the STT behaves almost identically as receiving a
                // new value in setTextFieldValue when the user types manually
                lastTextFieldInput = inputEvent.utterances[0].first
                _textFieldValue.value = inputEvent.utterances[0].first
                lastFinalUtterances = inputEvent.utterances
            }
        }
    }
}
