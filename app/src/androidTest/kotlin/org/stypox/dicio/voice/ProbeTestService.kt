package org.stypox.dicio.voice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Inert test-APK service; it neither starts recognition nor accesses the microphone. */
class ProbeTestService : Service() {
    override fun onBind(intent: Intent?): IBinder = Binder()
}
