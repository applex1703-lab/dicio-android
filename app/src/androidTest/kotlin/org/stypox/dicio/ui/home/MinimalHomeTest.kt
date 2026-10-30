package org.stypox.dicio.ui.home

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.stypox.dicio.eval.VoicePresentation
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.skills.calculator.CalculatorOutput
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.nav.SearchTopAppBar

class MinimalHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeShowsCurrentErrorWithoutHistoryControls() {
        compose.setContent {
            AppTheme {
                HomeScreen(
                    interactionLog = InteractionLog(listOf(Interaction(null, listOf(
                        QuestionAnswer("Старый запрос", CalculatorOutput("5", "5", "2+3"))
                    ))), null),
                    presentation = VoicePresentation(status = "Ошибка", answer = "Ошибка соединения"),
                    currentOutput = null,
                    skillContext = SkillContextImpl.newForPreviews(LocalContext.current),
                    ownsWork = false, startError = null,
                    sttState = null,
                    onSttClick = {}, onManualUserInput = {}, onCancel = {},
                    navigationIcon = { Text("Меню") }, snackbarHost = {},
                )
            }
        }
        compose.onNodeWithText("Ошибка соединения").assertIsDisplayed()
        compose.onNodeWithText("Старый запрос").assertDoesNotExist()
        compose.onNodeWithTag("home_text_input").assertExists()
        listOf("tv_text", "tv_settings", "tv_history_up", "tv_history_down", "interaction_list")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test fun currentLegacyOutputReplacesSpeechSummary() {
        compose.setContent {
            AppTheme {
                CurrentResponse(
                    VoicePresentation(status = "Ответ", answer = "речевое резюме"),
                    CalculatorOutput("5", "пять", "2+3"),
                    SkillContextImpl.newForPreviews(LocalContext.current), false, null,
                )
            }
        }
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithText("речевое резюме").assertDoesNotExist()
    }

    @Test fun startFailureIsVisible() {
        compose.setContent {
            AppTheme {
                CurrentResponse(VoicePresentation(), null,
                    SkillContextImpl.newForPreviews(LocalContext.current), false, "Микрофон недоступен")
            }
        }
        compose.onNodeWithText("Микрофон недоступен").assertIsDisplayed()
    }

    @Test fun tvTextEntryReceivesInitialFocus() {
        compose.setContent {
            AppTheme {
                SearchTopAppBar(null, {}, {}, "Текст", { Text("Dicio") },
                    { Text("Текст") }, { Text("Меню") }, initialFocus = true)
            }
        }
        compose.onNodeWithTag("home_text_input").assertIsFocused()
    }
}
