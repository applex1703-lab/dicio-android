package org.stypox.dicio.settings.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.stypox.dicio.ui.theme.AppTheme

class SettingAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun booleanSettingIsOneAccessibleSwitch() {
        val checked = mutableStateOf(false)
        compose.setContent {
            AppTheme { BooleanSetting("Enable feature").Render(checked.value) { checked.value = it } }
        }
        val switches = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onAllNodes(switches).assertCountEquals(1)
        compose.onNodeWithText("Enable feature").assertIsOff().performClick().assertIsOn()
    }

    @Test fun chooserExposesSelectionAndUpdatesValue() {
        val value = mutableStateOf(1)
        compose.setContent {
            AppTheme {
                ListSetting("Choice", possibleValues = listOf(
                    ListSetting.Value(1, "First"), ListSetting.Value(2, "Second")
                )).Render(value.value) { value.value = it }
            }
        }
        compose.onNodeWithText("Choice").performClick()
        compose.onNodeWithText("First").assertIsSelected()
        compose.onNodeWithText("Second").assertIsNotSelected().performClick()
        compose.onNodeWithText("Choice").performClick()
        compose.onNodeWithText("Second").assertIsSelected()
    }
}
