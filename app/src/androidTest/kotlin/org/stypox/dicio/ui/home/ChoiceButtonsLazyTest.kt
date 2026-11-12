package org.stypox.dicio.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.stypox.dicio.eval.ChoiceOption
import org.stypox.dicio.eval.ChoicePresentation
import org.stypox.dicio.ui.theme.AppTheme

@OptIn(ExperimentalTestApi::class)
class ChoiceButtonsLazyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paginationPreservesFocusAndNewRowStartsAtFirstItem() {
        fun options(start: Int, end: Int) = (start until end).map {
            ChoiceOption("id$it", "Option $it")
        }
        val choice = mutableStateOf(ChoicePresentation(1, options(0, 100)))
        val selected = mutableListOf<Pair<Long, String>>()
        compose.setContent {
            AppTheme { ChoiceButtons(choice.value, { id, option -> selected.add(id to option) }) }
        }

        compose.onNodeWithText("Option 0").assertIsFocused()
        compose.onNodeWithText("Option 99").assertDoesNotExist()
        compose.onNodeWithText("Option 0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Option 1").assertIsFocused()
        compose.runOnIdle { choice.value = choice.value.copy(options = options(0, 150)) }
        compose.onNodeWithText("Option 1").assertIsFocused()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
        compose.runOnIdle { choice.value = ChoicePresentation(2, options(200, 300)) }
        compose.onNodeWithText("Option 200").assertIsFocused()
        compose.onNodeWithText("Option 200").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf(2L to "id200"), selected) }
    }
}
