package org.stypox.dicio.ui.home

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.stypox.dicio.ui.theme.AppTheme

class TvHomeActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialFocusAndActions() {
        var action = ""
        compose.setContent {
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) { requester.requestFocus() }
            AppTheme {
                TvHomeActions(
                    sttState = null,
                    onSttClick = {},
                    onTextClick = { action = "text" },
                    onSettingsClick = { action = "settings" },
                    onHistoryUp = { action = "up" },
                    onHistoryDown = { action = "down" },
                    initialFocus = requester,
                )
            }
        }
        compose.onNodeWithTag("tv_text").assertIsFocused().performClick()
        assertEquals("text", action)
        compose.onNodeWithTag("tv_settings").performClick()
        assertEquals("settings", action)
        compose.onNodeWithTag("tv_history_up").performClick()
        assertEquals("up", action)
        compose.onNodeWithTag("tv_history_down").performClick()
        assertEquals("down", action)
    }

    @Test fun dpadMovesFocusToSettings() {
        compose.setContent {
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) { requester.requestFocus() }
            AppTheme {
                TvHomeActions(null, {}, {}, {}, {}, {}, requester)
            }
        }
        compose.onNodeWithTag("tv_text").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("tv_settings").assertIsFocused()
    }
}
