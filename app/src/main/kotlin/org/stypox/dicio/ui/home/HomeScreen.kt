package org.stypox.dicio.ui.home

import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.ContextWrapper
import org.stypox.dicio.MainActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.hilt.navigation.compose.hiltViewModel
import dev.shreyaspatil.permissionflow.compose.rememberPermissionFlowRequestLauncher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.eval.VoicePresentation
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.nav.SearchTopAppBar
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.InteractionLogPreviews
import org.stypox.dicio.ui.util.SttStatesPreviews
import org.stypox.dicio.util.checkPermissions
import org.stypox.dicio.util.getNonGrantedSecurePermissions
import kotlin.math.abs

@Composable
fun HomeScreen(
    navigationIcon: @Composable () -> Unit,
) {
    val channel = remember { Channel<Boolean>() }
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberPermissionFlowRequestLauncher { isGranted ->
        coroutineScope.launch {
            channel.send(isGranted.values.all { it })
        }
    }
    val context = LocalContext.current
    var activityContext = context
    while (activityContext is ContextWrapper && activityContext !is MainActivity && activityContext.baseContext !== activityContext) {
        activityContext = activityContext.baseContext
    }
    val activity = activityContext as? MainActivity
    var startError by remember { mutableStateOf<String?>(null) }

    suspend fun requestPermissions(permissions: List<Permission>): Boolean {
        val nonGrantedSecurePermissions = getNonGrantedSecurePermissions(
                context,
                permissions.filterIsInstance<Permission.SecurePermission>()
        )
        if (nonGrantedSecurePermissions.isNotEmpty()) {
            // do not request secure permissions directly, it would be confusing, so ask explicitly
            // instead
            return false
        }

        val normalPermissions = permissions.filterIsInstance<Permission.NormalPermission>()
            .map { it.id }.toTypedArray()
        if (checkPermissions(context, *normalPermissions)) {
            // permissions already granted
            return true
        }

        // some permission is not already granted, need to request it
        launcher.launch(normalPermissions)
        return channel.receive()
    }

    val viewModel: HomeScreenViewModel = hiltViewModel()
    // keep assigning permissionRequester at every recomposition because `launcher` changes when
    // the activity is recreated (no rememberSaveable is available)
    viewModel.skillEvaluator.permissionRequester = ::requestPermissions

    val interactionsState = viewModel.skillEvaluator.state.collectAsState()
    val presentation = viewModel.skillEvaluator.voicePresentation.collectAsState()
    val currentOutput = viewModel.skillEvaluator.currentOutput.collectAsState()
    val sttState = viewModel.sttInputDevice.uiState.collectAsState()
    val ownsWork = activity?.voiceInputCoordinator?.ownsWork(activity) == true

    HomeScreen(
        interactionLog = interactionsState.value,
        presentation = presentation.value,
        currentOutput = currentOutput.value,
        skillContext = viewModel.skillContext,
        ownsWork = ownsWork,
        startError = startError,
        sttState = sttState.value,
        onSttClick = {
            startError = if (activity?.startHomeStt() == true) null else "Не удалось запустить голосовой ввод."
        },
        onManualUserInput = {
            startError = null
            activity?.submitHomeText(it)
        },
        onCancel = {
            startError = null
            activity?.let { it.voiceInputCoordinator.cancel(it) }
        },
        navigationIcon = navigationIcon,
        snackbarHost = {
            SnackbarHost(hostState = viewModel.snackbarHostState)
        },
    )
}

@Composable
fun HomeScreen(
    interactionLog: InteractionLog,
    presentation: VoicePresentation,
    currentOutput: SkillOutput?,
    skillContext: SkillContext,
    ownsWork: Boolean,
    startError: String?,
    // if the STT state is null, it means the user disabled the STT
    sttState: SttState?,
    onSttClick: () -> Unit,
    onManualUserInput: (String) -> Unit,
    onCancel: () -> Unit,
    navigationIcon: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
) {
    var searchString by rememberSaveable { mutableStateOf<String?>(null) }
    val isTv = isTelevision()
    BackHandler(enabled = searchString == null && ownsWork && (interactionLog.pendingQuestion != null || sttState == SttState.Listening || sttState == SttState.WaitingForResult || (sttState is SttState.Loading && sttState.thenStartListening))) { onCancel() }

    Scaffold(
        topBar = {
            SearchTopAppBar(
                searchString = searchString,
                setSearchString = { searchString = it },
                onSearch = onManualUserInput,
                hint = stringResource(R.string.text_input_hint),
                title = {
                    Text(text = stringResource(R.string.app_name))
                },
                searchIcon = {
                    Icon(
                        imageVector = Icons.Default.QuestionAnswer,
                        contentDescription = stringResource(R.string.text_input_hint),
                    )
                },
                navigationIcon = navigationIcon,
                initialFocus = isTv,
            )
        },
        content = { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                CurrentResponse(presentation, currentOutput, skillContext,
                    starting = ownsWork && (sttState == SttState.Listening || sttState == SttState.WaitingForResult || (sttState is SttState.Loading && sttState.thenStartListening)),
                    error = startError, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        },
        floatingActionButton = {
            // if the STT state is null, it means the user disabled the STT
            if (sttState != null) {
                SttFab(
                    state = sttState,
                    onClick = onSttClick,
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = snackbarHost,
    )
}

@Preview
@Composable
private fun HomeScreenPreview(@PreviewParameter(InteractionLogPreviews::class) interactionLog: InteractionLog) {
    val sttStatesPreviews = remember { SttStatesPreviews().values.toList() }
    var i by remember { mutableIntStateOf(abs(interactionLog.hashCode())) }

    AppTheme {
        HomeScreen(
            interactionLog = interactionLog,
            presentation = VoicePresentation(status = "Ответ", answer = "42"),
            currentOutput = null,
            skillContext = SkillContextImpl.newForPreviews(LocalContext.current),
            ownsWork = false,
            startError = null,
            sttState = sttStatesPreviews[i % sttStatesPreviews.size],
            onSttClick = { i += 1 },
            onManualUserInput = {},
            onCancel = {},
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                    )
                }
            },
            snackbarHost = {},
        )
    }
}
