package org.stypox.dicio.llm

import androidx.compose.runtime.Composable
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.io.graphical.Headline

/** Result shown in history. Device actions deliberately have no spoken confirmation. */
internal class AssistantOutput(private val text: String, private val speak: Boolean) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext) = if (speak) text else ""
    @Composable override fun GraphicalOutput(ctx: SkillContext) { Headline(text) }
}
