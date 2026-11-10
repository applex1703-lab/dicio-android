package org.stypox.dicio.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.eval.VoicePresentation
import org.stypox.dicio.io.input.SttState

class VoiceStatusTest : StringSpec({
    "preparation and readiness are distinct" {
        voiceStatus(VoicePresentation(), SttState.WaitingForResult) shouldBe "Подготовка микрофона…"
        voiceStatus(VoicePresentation(), SttState.Listening) shouldBe "Распознаватель готов…"
    }
    "after speech ends a partial transcript is not shown as listening" {
        val partial = VoicePresentation(request = "текст", status = "Слушаю…")
        voiceStatus(partial, SttState.Listening) shouldBe "Слушаю…"
        voiceStatus(partial, SttState.WaitingForResult) shouldBe "Распознаю…"
    }
    "terminal and thinking states take precedence" {
        voiceStatus(VoicePresentation(request = "текст", status = "Думаю…"), SttState.WaitingForResult) shouldBe "Думаю…"
        voiceStatus(VoicePresentation(request = "текст", status = "Ответ", answer = "да"), SttState.Listening) shouldBe "Ответ"
    }
    "status remains visible alongside a transcript until an answer appears" {
        showVoiceStatus("Слушаю…", "") shouldBe true
        showVoiceStatus("Распознаю…", "") shouldBe true
        showVoiceStatus("Думаю…", "") shouldBe true
        showVoiceStatus("Выполняю…", "") shouldBe true
        showVoiceStatus("Ответ", "да") shouldBe false
        showVoiceStatus("Не удалось выполнить запрос", "Ошибка") shouldBe false
        showVoiceStatus("", "") shouldBe false
    }
})
