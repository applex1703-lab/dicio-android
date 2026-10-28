package org.stypox.dicio.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import android.content.ContextWrapper
import org.stypox.dicio.MainActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.hilt.navigation.compose.hiltViewModel
import dev.shreyaspatil.permissionflow.compose.rememberPermissionFlowRequestLauncher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.nav.SearchTopAppBar
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.InteractionLogPreviews
import org.stypox.dicio.ui.util.SkillInfoPreviews
import org.stypox.dicio.ui.util.SttStatesPreviews
import org.stypox.dicio.util.checkPermissions
import org.stypox.dicio.util.getNonGrantedSecurePermissions
import kotlin.math.abs

@Composable
fun HomeScreen(
    navigationIcon: @Composable () -> Unit,
    onSettingsClick: () -> Unit = {},
) {
    val channel = remember { Channel<Boolean>() }
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberPermissionFlowRequestLauncher { isGranted ->
        coroutineScope.launch {
            channel.send(isGranted.values.all { it })
        }
    }
    val context = LocalContext.current

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

    val enabledSkillsInfo = viewModel.skillHandler.enabledSkillsInfo.collectAsState()
    val interactionsState = viewModel.skillEvaluator.state.collectAsState()
    val sttState = viewModel.sttInputDevice.uiState.collectAsState()

    HomeScreen(
        skillContext = viewModel.skillContext,
        skills = enabledSkillsInfo.value,
        interactionLog = interactionsState.value,
        sttState = sttState.value,
        onSttClick = {
            var current = context
            while (current is ContextWrapper && current !is MainActivity && current.baseContext !== current) {
                current = current.baseContext
            }
            (current as? MainActivity)?.startHomeStt()
        },
        onManualUserInput = {
            var current = context
            while (current is ContextWrapper && current !is MainActivity && current.baseContext !== current) {
                current = current.baseContext
            }
            (current as? MainActivity)?.submitHomeText(it)
        },
        navigationIcon = navigationIcon,
        onSettingsClick = onSettingsClick,
        snackbarHost = {
            SnackbarHost(hostState = viewModel.snackbarHostState)
        },
    )
}

@Composable
fun HomeScreen(
    skillContext: SkillContext,
    // will be null when skills have not been initialized yet
    skills: List<SkillInfo>?,
    interactionLog: InteractionLog,
    // if the STT state is null, it means the user disabled the STT
    sttState: SttState?,
    onSttClick: () -> Unit,
    onManualUserInput: (String) -> Unit,
    navigationIcon: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    onSettingsClick: () -> Unit = {},
) {
    var searchString by rememberSaveable { mutableStateOf<String?>(null) }
    val isTv = isTelevision()
    val textFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(isTv, searchString) {
        if (isTv && searchString == null) {
            textFocus.requestFocus()
        }
    }

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
            )
        },
        content = { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues)) {
                if (isTv) {
                    TvHomeActions(
                        sttState = sttState,
                        onSttClick = onSttClick,
                        onTextClick = { searchString = "" },
                        onSettingsClick = onSettingsClick,
                        initialFocus = textFocus,
                        onHistoryUp = { scope.launch { listState.animateScrollBy(-listState.layoutInfo.viewportSize.height.toFloat() * 0.8f) } },
                        onHistoryDown = { scope.launch { listState.animateScrollBy(listState.layoutInfo.viewportSize.height.toFloat() * 0.8f) } },
                    )
                }
                InteractionList(
                    skillContext = skillContext,
                    skills = skills,
                    interactionLog = interactionLog,
                    onConfirmedQuestionClick = { searchString = it },
                    modifier = Modifier.weight(1f),
                    listState = listState,
                )
            }
        },
        floatingActionButton = {
            // if the STT state is null, it means the user disabled the STT
            if (!isTv && sttState != null) {
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
            skillContext = SkillContextImpl.newForPreviews(LocalContext.current),
            skills = SkillInfoPreviews().values.toList(),
            interactionLog = interactionLog,
            sttState = sttStatesPreviews[i % sttStatesPreviews.size],
            onSttClick = { i += 1 },
            onManualUserInput = {},
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
