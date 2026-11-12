package org.stypox.dicio.ui.home

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.eval.VideoCard
import org.stypox.dicio.eval.VideoRow
import org.stypox.dicio.eval.VideoSource
import org.stypox.dicio.eval.VoicePresentation
import org.stypox.dicio.ui.theme.AppTheme

class HomeVideoRowsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lateYoutubeRowDoesNotStealRutubeFocus() {
        checkLateRowKeepsFocus(VideoSource.RUTUBE, VideoSource.YOUTUBE)
    }

    @Test fun lateRutubeRowDoesNotStealYoutubeFocus() {
        checkLateRowKeepsFocus(VideoSource.YOUTUBE, VideoSource.RUTUBE)
    }

    private fun checkLateRowKeepsFocus(firstSource: VideoSource, lateSource: VideoSource) {
        val first = VideoRow(1, listOf(VideoCard("first", "Первый результат", "Автор", null)), source = firstSource)
        val late = VideoRow(2, listOf(VideoCard("late", "Поздний результат", "Автор", null)), source = lateSource)
        val presentation = mutableStateOf(VoicePresentation(status = "", videoRows = listOf(first)))
        val selected = mutableListOf<Pair<Long, String>>()
        compose.setContent {
            AppTheme {
                HomeScreen(
                    interactionLog = InteractionLog(emptyList(), null), presentation = presentation.value,
                    currentOutput = null, skillContext = SkillContextImpl.newForPreviews(LocalContext.current),
                    ownsWork = true, startError = null, sttState = null,
                    onSttClick = {}, onManualUserInput = {}, onCancel = {},
                    onSelectVideo = { row, video -> selected.add(row to video) },
                    navigationIcon = { Text("Меню") }, snackbarHost = {},
                )
            }
        }
        compose.onNodeWithText(firstSource.label).assertExists()
        compose.onNodeWithText("Первый результат").assertIsFocused()
        compose.onNodeWithText("Поздний результат").assertDoesNotExist()

        compose.runOnIdle {
            presentation.value = presentation.value.copy(videoRows = listOf(first, late).sortedBy { it.source.ordinal })
        }

        compose.onNodeWithText(firstSource.label).assertExists()
        compose.onNodeWithText(lateSource.label).assertExists()
        compose.onNodeWithText("Поздний результат").assertExists()
        compose.onNodeWithText("Первый результат").assertIsFocused()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }
}
