package org.stypox.dicio.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import org.stypox.dicio.R
import org.stypox.dicio.io.input.SttState

@Composable
internal fun isTelevision(): Boolean =
    LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TvHomeActions(
    sttState: SttState?,
    onSttClick: () -> Unit,
    onTextClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    initialFocus: FocusRequester,
) {

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvActionButton(
            text = stringResource(R.string.text_input_hint),
            onClick = onTextClick,
            modifier = Modifier.focusRequester(initialFocus).testTag("tv_text"),
        )
        TvActionButton(text = stringResource(R.string.settings), onClick = onSettingsClick, modifier = Modifier.testTag("tv_settings"))
        if (sttState != null) {
            SttFab(state = sttState, onClick = onSttClick, modifier = Modifier.tvFocusOutline())
        }
        TvActionButton(text = "История ↑", onClick = onHistoryUp, modifier = Modifier.testTag("tv_history_up"))
        TvActionButton(text = "История ↓", onClick = onHistoryDown, modifier = Modifier.testTag("tv_history_down"))
    }
}

@Composable
private fun TvActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.tvFocusOutline().defaultMinSize(minHeight = 56.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun Modifier.tvFocusOutline(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface
    return onFocusChanged { focused = it.hasFocus }.border(3.dp, color, MaterialTheme.shapes.large)
}
