package org.stypox.dicio.settings

import android.app.Application
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.stypox.dicio.R
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.SpeechOutputDevice
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettingsModule.Companion.newDataStoreForPreviews
import org.stypox.dicio.settings.ui.SettingsCategoryTitle
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.home.isTelevision
import org.stypox.dicio.llm.MediaAccessService


@Composable
fun MainSettingsScreen(
    navigationIcon: @Composable () -> Unit,
    navigateToSkillSettings: () -> Unit,
    viewModel: MainSettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = navigationIcon
            )
        }
    ) {
        MainSettingsScreen(
            navigateToSkillSettings = navigateToSkillSettings,
            viewModel = viewModel,
            modifier = Modifier.padding(it),
        )
    }
}

@Composable
private fun MainSettingsScreen(
    navigateToSkillSettings: () -> Unit,
    viewModel: MainSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settingsState.collectAsState()
    val isTv = isTelevision()
    var skillsFocused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var systemStatus by remember(context) { mutableStateOf(readTvSystemStatus(context)) }
    DisposableEffect(context, lifecycleOwner, isTv) {
        val observer = LifecycleEventObserver { _, event ->
            if (isTv && event == Lifecycle.Event.ON_RESUME) {
                systemStatus = readTvSystemStatus(context)
            }
        }
        if (isTv) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = if (isTv) PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            else PaddingValues(0.dp),
    ) {
        if (isTv) {
            item {
                Text(
                    text = tvStatusText(systemStatus, settings.speechOutputDevice),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        item { org.stypox.dicio.llm.PolzaSettingsCard() }
        /* GENERAL SETTINGS */
        item { SettingsCategoryTitle(stringResource(R.string.pref_general), topPadding = 4.dp) }
        item {
            languageSetting().Render(
                when (val language = settings.language) {
                    Language.UNRECOGNIZED -> Language.LANGUAGE_SYSTEM
                    else -> language
                },
                viewModel::setLanguage,
            )
        }
        item {
            themeSetting().Render(
                when (val theme = settings.theme) {
                    Theme.UNRECOGNIZED -> Theme.THEME_SYSTEM
                    else -> theme
                },
                viewModel::setTheme,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                dynamicColors().Render(
                    settings.dynamicColors,
                    viewModel::setDynamicColors
                )
            }
        }
        item {
            SettingsItem(
                title = stringResource(R.string.pref_skills_title),
                icon = Icons.Default.Extension,
                description = stringResource(R.string.pref_skills_summary),
                modifier = Modifier
                    .then(if (isTv) Modifier
                        .onFocusChanged { skillsFocused = it.hasFocus }
                        .border(
                            3.dp,
                            if (skillsFocused) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.surface,
                            MaterialTheme.shapes.large,
                        ) else Modifier)
                    .clickable(onClick = navigateToSkillSettings)
                    .testTag("skill_settings_item")
            )
        }

        /* INPUT AND OUTPUT METHODS */
        item { SettingsCategoryTitle(stringResource(R.string.pref_io)) }
        item {
            inputDevice().Render(
                when (val inputDevice = settings.inputDevice) {
                    InputDevice.UNRECOGNIZED,
                    InputDevice.INPUT_DEVICE_UNSET -> InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
                    else -> inputDevice
                },
                viewModel::setInputDevice,
            )
        }
        item {
            speechOutputDevice().Render(
                when (val speechOutputDevice = settings.speechOutputDevice) {
                    SpeechOutputDevice.UNRECOGNIZED,
                    SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_UNSET ->
                        SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS
                    else -> speechOutputDevice
                },
                viewModel::setSpeechOutputDevice,
            )
        }
        item {
            sttPlaySound().Render(
                when (val sttPlaySound = settings.sttPlaySound) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET -> SttPlaySound.STT_PLAY_SOUND_NOTIFICATION
                    else -> sttPlaySound
                },
                viewModel::setSttPlaySound
            )
        }
        item {
            sttSilenceDuration().Render(
                SttInputDevice.getSttSilenceDurationOrDefault(settings),
                viewModel::setSttSilenceDuration
            )
        }
        item {
            sttAutoFinish().Render(
                settings.autoFinishSttPopup,
                viewModel::setAutoFinishSttPopup
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

internal data class TvSystemStatus(
    val microphoneGranted: Boolean?,
    val assistant: String?,
    val mediaListenerGranted: Boolean?,
)

// A missing setting means no selected component; an exception means the check is unavailable.
internal fun componentSelected(value: String?, component: ComponentName): Boolean =
    value.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }

internal fun readTvSystemStatus(context: Context): TvSystemStatus {
    val microphone = runCatching {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }.getOrNull()
    val assistant = runCatching {
        val value = Settings.Secure.getString(context.contentResolver, "assistant")
        when {
            value.isNullOrBlank() -> "не удалось определить назначение"
            ComponentName.unflattenFromString(value)?.packageName == context.packageName -> "выбрано это приложение"
            ComponentName.unflattenFromString(value) != null -> "выбрано другое приложение"
            else -> "не удалось определить"
        }
    }.getOrNull()
    val media = runCatching {
        componentSelected(
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners"),
            ComponentName(context, MediaAccessService::class.java),
        )
    }.getOrNull()
    return TvSystemStatus(microphone, assistant, media)
}

internal fun tvStatusText(status: TvSystemStatus, output: SpeechOutputDevice): String {
    fun permission(value: Boolean?) = when (value) {
        true -> "предоставлено"
        false -> "не предоставлено"
        null -> "не удалось проверить"
    }
    val mode = when (output) {
        SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS, SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_UNSET,
        SpeechOutputDevice.UNRECOGNIZED -> "Android TTS (в том числе режим по умолчанию)"
        SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_NOTHING -> "отключён"
        SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_TOAST -> "всплывающее сообщение"
        SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_SNACKBAR -> "сообщение на экране"
        else -> "неизвестен"
    }
    return "Системный ассистент: ${status.assistant ?: "не удалось проверить"}; работа не проверена\n" +
        "Микрофон: разрешение ${permission(status.microphoneGranted)}; запись и распознавание не проверены\n" +
        "RouterAI: конфигурация не проверена; подключение не проверено\n" +
        "Вывод речи: выбран $mode; работа и доступность не проверены\n" +
        "Медиа: доступ слушателя уведомлений ${permission(status.mediaListenerGranted)}; управление не проверено"
}

@Preview
@Composable
private fun MainSettingsScreenPreview() {
    AppTheme {
        Surface(
            color = MaterialTheme.colorScheme.background
        ) {
            MainSettingsScreen(
                navigateToSkillSettings = {},
                viewModel = MainSettingsViewModel(
                    application = Application(),
                    dataStore = newDataStoreForPreviews(),
                ),
            )
        }
    }
}

@Preview
@Composable
private fun MainSettingsScreenWithTopBarPreview() {
    AppTheme {
        Surface(
            color = MaterialTheme.colorScheme.background
        ) {
            MainSettingsScreen(
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                navigateToSkillSettings = {},
                viewModel = MainSettingsViewModel(
                    application = Application(),
                    dataStore = newDataStoreForPreviews()
                )
            )
        }
    }
}
