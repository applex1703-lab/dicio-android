package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CurrentVoicePresentationTest : StringSpec({
    "repeated results retain only the current request and answer" {
        val current = CurrentVoicePresentation()
        repeat(1000) { index ->
            current.thinking("request $index")
            current.answer("answer $index")
        }
        current.state.value shouldBe VoicePresentation(request = "request 999", status = "Ответ", answer = "answer 999")
    }
    "partial, error, silent success and clear replace transient state" {
        val current = CurrentVoicePresentation()
        current.listening("partial")
        current.state.value.request shouldBe "partial"
        current.error("permission denied")
        current.state.value.answer shouldBe "permission denied"
        current.state.value.dismiss shouldBe false
        current.thinking("next")
        current.answer("not spoken or shown", silent = true)
        current.state.value.answer shouldBe ""
        current.state.value.dismiss shouldBe true
        current.noInput()
        current.state.value.request shouldBe ""
        current.state.value.dismiss shouldBe false
        current.clear()
        current.state.value shouldBe VoicePresentation()
    }
})
