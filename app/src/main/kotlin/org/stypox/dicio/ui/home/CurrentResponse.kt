package org.stypox.dicio.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.stypox.dicio.eval.VoicePresentation
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput

/** Only the current request is shown, never the interaction history. */
@Composable
internal fun CurrentResponse(presentation: VoicePresentation, currentOutput: SkillOutput?,
    skillContext: SkillContext, starting: Boolean, error: String?, modifier: Modifier = Modifier) {
    if (presentation == VoicePresentation() && !starting && error == null && currentOutput == null) return
    Column(modifier.verticalScroll(rememberScrollState()).testTag("current_response")) {
        if (error != null) {
            Text(error)
        } else {
            if (presentation.status.isNotBlank()) Text(presentation.status)
            if (presentation.request.isNotBlank()) Text(presentation.request)
            if (currentOutput != null) currentOutput.GraphicalOutput(skillContext)
            else if (presentation.answer.isNotBlank()) Text(presentation.answer)
        }
    }
}
