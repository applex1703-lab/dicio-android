package org.stypox.dicio.settings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.settings.datastore.InputDevice

class MainSettingsScreenTest : StringSpec({
    "legacy Vosk and unset input devices display the Google option" {
        listOf(
            InputDevice.INPUT_DEVICE_VOSK,
            InputDevice.INPUT_DEVICE_UNSET,
            InputDevice.UNRECOGNIZED,
        ).forEach {
            displayedInputDevice(it) shouldBe InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
        }
    }

    "selectable input devices retain their displayed values" {
        listOf(
            InputDevice.INPUT_DEVICE_EXTERNAL_POPUP,
            InputDevice.INPUT_DEVICE_NOTHING,
        ).forEach {
            displayedInputDevice(it) shouldBe it
        }
    }
})
