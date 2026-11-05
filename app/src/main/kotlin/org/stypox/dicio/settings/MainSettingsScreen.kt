package org.stypox.dicio.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
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
import androidx.compose.material3.Switch
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
import org.stypox.dicio.R
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
import org.stypox.dicio.voice.PREBIND_KEY
import org.stypox.dicio.voice.prebindPreferences

internal fun displayedInputDevice(inputDevice: InputDevice): InputDevice = when (inputDevice) {
    InputDevice.UNRECOGNIZED,
    InputDevice.INPUT_DEVICE_UNSET,
    InputDevice.INPUT_DEVICE_VOSK -> InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
    else -> inputDevice
}


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
    val context = LocalContext.current
    var skillsFocused by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = if (isTv) PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            else PaddingValues(0.dp),
    ) {
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
        item { BackgroundPrebindSetting() }
        item {
            SettingsItem(
                title = "Диагностика соединения Google",
                description = "Проверка соединения без запуска распознавания со стороны Dicio",
                modifier = Modifier.clickable {
                    context.startActivity(Intent(context, org.stypox.dicio.voice.BindDiagnosticActivity::class.java))
                }.testTag("bind_diagnostic_item"),
            )
        }
        item {
            inputDevice().Render(
                displayedInputDevice(settings.inputDevice),
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
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BackgroundPrebindSetting() {
    val context = LocalContext.current
    val preferences = remember(context) { prebindPreferences(context) }
    var enabled by remember(preferences) { mutableStateOf(preferences.getBoolean(PREBIND_KEY, false)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREBIND_KEY || key == null) enabled = preferences.getBoolean(PREBIND_KEY, false)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsItem(
        title = "Фоновая подготовка Google",
        description = "По умолчанию выключено. Сервис Google может оставаться в памяти и выполнять собственные фоновые действия, включая использование сети и возможный доступ к микрофону. Dicio не инициирует запись или распознавание при подготовке. После отключения успешное освобождение не гарантировано; неопределённость видна в журнале DicioPrebind.",
        content = {
            Switch(checked = enabled, onCheckedChange = {
                preferences.edit().putBoolean(PREBIND_KEY, it).apply()
            }, modifier = Modifier.testTag("background_prebind_switch"))
        },
    )
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
