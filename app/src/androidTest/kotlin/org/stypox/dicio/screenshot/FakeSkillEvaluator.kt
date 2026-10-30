package org.stypox.dicio.screenshot

import kotlinx.coroutines.flow.MutableStateFlow
import org.dicio.skill.skill.Permission
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.ui.home.InteractionLog

class FakeSkillEvaluator : SkillEvaluator {
    override val currentOutput = MutableStateFlow<org.dicio.skill.skill.SkillOutput?>(null)
    override val voicePresentation = MutableStateFlow(org.stypox.dicio.eval.VoicePresentation())
    override fun resetVoicePresentation() { voicePresentation.value = org.stypox.dicio.eval.VoicePresentation() }
    override fun cancelPendingInput() {}
    override val state: MutableStateFlow<InteractionLog> = MutableStateFlow(
        InteractionLog(
            interactions = listOf(),
            pendingQuestion = null,
        )
    )

    override var permissionRequester: suspend (List<Permission>) -> Boolean = { true }

    override fun processInputEvent(event: InputEvent) {
    }
}
