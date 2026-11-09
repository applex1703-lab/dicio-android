package org.stypox.dicio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.dicio.skill.context.SpeechOutputDevice

class RequestSpeechDeviceTest : StringSpec({
    class Main : SpeechMainThread {
        var before: (() -> Unit)? = null
        override fun <T> run(action: () -> T): T {
            before?.also { before = null; it() }
            return action()
        }
    }
    class Device : SpeechOutputDevice {
        val calls = mutableListOf<String>()
        var callback: Runnable? = null
        override fun speak(speechOutput: String) { calls += speechOutput }
        override fun stopSpeaking() { calls += "stop" }
        override fun cleanup() { calls += "cleanup" }
        override val isSpeaking: Boolean get() { calls += "isSpeaking"; return true }
        override fun runWhenFinishedSpeaking(runnable: Runnable) { calls += "register"; callback = runnable }
    }

    "check at dispatch time fences every stale operation" {
        val main = Main()
        val device = Device()
        var current = true
        val scoped = RequestSpeechDevice(device, { current }, main)
        main.before = { current = false }
        scoped.speak("old")
        scoped.stopSpeaking()
        scoped.cleanup()
        scoped.runWhenFinishedSpeaking(Runnable { device.calls += "callback" })
        scoped.isSpeaking shouldBe false
        device.calls shouldBe emptyList()
    }

    "callback is checked again after reentry and on dispatch" {
        val main = Main()
        val device = Device()
        var current = true
        val scoped = RequestSpeechDevice(device, { current }, main)
        scoped.runWhenFinishedSpeaking(Runnable { device.calls += "callback" })
        main.before = { current = false }
        device.callback!!.run()
        device.calls shouldBe listOf("register")
    }

    "current facade delegates without changing queue policy" {
        val device = Device()
        val scoped = RequestSpeechDevice(device, { true }, Main())
        scoped.speak("hello")
        scoped.isSpeaking shouldBe true
        scoped.runWhenFinishedSpeaking(Runnable { device.calls += "callback" })
        device.callback!!.run()
        scoped.stopSpeaking()
        scoped.cleanup()
        device.calls shouldBe listOf("hello", "isSpeaking", "register", "callback", "stop", "cleanup")
    }
})
