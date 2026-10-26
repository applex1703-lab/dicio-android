package org.stypox.dicio.voice

import android.os.Bundle
import android.os.Build
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class TvVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        check(Build.VERSION.SDK_INT >= 23) { "Voice session requires Android 6 or later." }
        return TvVoiceSession(this)
    }
}
