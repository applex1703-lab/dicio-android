package org.stypox.dicio.voice

import android.os.Build
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** System assistant endpoint. No hotword detector, recording, or network work while idle. */
class TvVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        if (Build.VERSION.SDK_INT >= 23) {
            setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        }
    }
}
