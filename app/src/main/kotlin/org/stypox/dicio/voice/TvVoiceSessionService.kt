package org.stypox.dicio.voice

import android.os.Bundle
import android.os.Build
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class TvVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        if (Build.VERSION.SDK_INT >= 23) {
            return TvVoiceSession(this)
        }
        // Android 5 retains the existing Activity assistant; no API 23 session is constructed.
        return VoiceInteractionSession(this)
    }
}
