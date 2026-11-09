package org.stypox.dicio.skills.timer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.dicio.skill.context.SkillContext
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.MockSkillContext
import org.stypox.dicio.di.NotificationSpeechContext
import org.stypox.dicio.sentences.Sentences
import java.time.Duration

class TimerContinuationTest : StringSpec({
    "duration continuation receives the context of the answer" {
        val initial = object : SkillContext by MockSkillContext {}
        val current = object : SkillContext by MockSkillContext {}
        var received: SkillContext? = null
        val output = TimerOutput.SetAskDuration { ctx, duration ->
            received = ctx
            duration shouldBe Duration.ofMinutes(2)
            TimerOutput.Cancel("done")
        }
        val plan = output.getInteractionPlan(initial) as InteractionPlan.StartSubInteraction
        @Suppress("UNCHECKED_CAST")
        val skill = plan.nextSkills.single() as Skill<Duration?>
        runBlocking { skill.generateOutput(current, Duration.ofMinutes(2)) }
        received shouldBe current
    }

    "confirm continuation receives the context of the answer" {
        val initial = object : SkillContext by MockSkillContext {}
        val current = object : SkillContext by MockSkillContext {}
        var received: SkillContext? = null
        val data = StandardRecognizerData<Sentences.UtilYesNo>(Specificity.HIGH, { _, _, _ ->
            error("recognition is not needed")
        }, emptyList())
        val output = TimerOutput.ConfirmCancel(data) { ctx ->
            received = ctx
            TimerOutput.Cancel("done")
        }
        val plan = output.getInteractionPlan(initial) as InteractionPlan.StartSubInteraction
        @Suppress("UNCHECKED_CAST")
        val skill = plan.nextSkills.single() as Skill<Boolean>
        runBlocking { skill.generateOutput(current, true) }
        received shouldBe current
    }

    "notifications retain the shared device instead of the per-input device" {
        val inputDevice = RecordingSpeech()
        val sharedDevice = RecordingSpeech()
        val ctx = object : SkillContext by MockSkillContext, NotificationSpeechContext {
            override val speechOutputDevice: SpeechOutputDevice = inputDevice
            override val notificationSpeechOutputDevice: SpeechOutputDevice = sharedDevice
        }
        val notification = notificationSpeechDevice(ctx)
        notification.speak("tick")
        sharedDevice.messages shouldBe listOf("tick")
        inputDevice.messages shouldBe emptyList()
    }

    "contexts without a notification channel fall back to the current device" {
        val device = RecordingSpeech()
        val ctx = object : SkillContext by MockSkillContext {
            override val speechOutputDevice: SpeechOutputDevice = device
        }
        notificationSpeechDevice(ctx).speak("expired")
        device.messages shouldBe listOf("expired")
    }
})

private class RecordingSpeech : SpeechOutputDevice {
    val messages = mutableListOf<String>()
    override fun speak(speechOutput: String) { messages.add(speechOutput) }
    override fun stopSpeaking() = Unit
    override val isSpeaking = false
    override fun runWhenFinishedSpeaking(runnable: Runnable) = runnable.run()
    override fun cleanup() = Unit
}
