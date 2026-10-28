package org.stypox.dicio.ui.home

import android.content.ComponentName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.settings.TvSystemStatus
import org.stypox.dicio.settings.componentSelected
import org.stypox.dicio.settings.tvStatusText
import org.stypox.dicio.settings.datastore.SpeechOutputDevice

class TvStatusTest {
    @Test fun listenerCheckMatchesComponentNotJustPackage() {
        val component = ComponentName("example.app", "example.app.Media")
        assertTrue(componentSelected("other/.Listener:example.app/.Media", component))
        assertFalse(componentSelected("example.app/.Other", component))
        assertFalse(componentSelected(null, component))
    }

    @Test fun statusSeparatesPermissionFromWorking() {
        val text = tvStatusText(TvSystemStatus(true, null, false), SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_TOAST)
        assertTrue(text.contains("разрешение предоставлено"))
        assertTrue(text.contains("распознавание не проверены"))
        assertTrue(text.contains("уведомлений не предоставлено"))
        assertTrue(text.contains("всплывающее сообщение"))
        assertTrue(text.contains("RouterAI: конфигурация не проверена"))
    }
}
