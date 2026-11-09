package org.stypox.dicio.eval

import android.os.Handler
import android.os.Looper
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.di.NotificationSpeechContext
import org.stypox.dicio.di.SkillContextInternal
import java.util.concurrent.FutureTask

/** Dispatch synchronously so even property reads and callbacks are fenced on the main thread. */
internal interface SpeechMainThread {
    fun <T> run(action: () -> T): T
}

internal object AndroidSpeechMainThread : SpeechMainThread {
    private val handler = Handler(Looper.getMainLooper())
    override fun <T> run(action: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return action()
        val task = FutureTask { action() }
        check(handler.post(task)) { "Main thread is unavailable" }
        return task.get()
    }
}

internal class RequestSpeechDevice(
    private val delegate: SpeechOutputDevice,
    private val isCurrent: () -> Boolean,
    private val main: SpeechMainThread = AndroidSpeechMainThread,
) : SpeechOutputDevice {
    override fun speak(speechOutput: String) = main.run {
        if (isCurrent()) delegate.speak(speechOutput)
    }
    override fun stopSpeaking() = main.run {
        if (isCurrent()) delegate.stopSpeaking()
    }
    override fun cleanup() = main.run {
        if (isCurrent()) delegate.cleanup()
    }
    override val isSpeaking: Boolean
        get() = main.run { isCurrent() && delegate.isSpeaking }

    override fun runWhenFinishedSpeaking(runnable: Runnable) = main.run {
        if (isCurrent()) delegate.runWhenFinishedSpeaking(Runnable {
            main.run { if (isCurrent()) runnable.run() }
        })
    }
}

internal class RequestSkillContext(
    delegate: SkillContextInternal,
    isCurrent: () -> Boolean,
    main: SpeechMainThread = AndroidSpeechMainThread,
) : SkillContextInternal by delegate, NotificationSpeechContext {
    override val speechOutputDevice: SpeechOutputDevice =
        RequestSpeechDevice(delegate.speechOutputDevice, isCurrent, main)
    override val notificationSpeechOutputDevice: SpeechOutputDevice =
        (delegate as? NotificationSpeechContext)?.notificationSpeechOutputDevice ?: delegate.speechOutputDevice
}
