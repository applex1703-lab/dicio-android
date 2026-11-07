package org.stypox.dicio.eval

import android.util.Log
import android.os.Looper
import androidx.annotation.MainThread
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.skills.fallback.text.TextFallbackOutput
import javax.inject.Singleton
import javax.inject.Provider
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.llm.AssistantIntent
import org.stypox.dicio.llm.ContentSearch
import org.stypox.dicio.llm.SearchTarget
import org.stypox.dicio.llm.VpnDispatcher
import org.stypox.dicio.llm.VpnDispatch
import org.stypox.dicio.llm.HappConnect
import org.stypox.dicio.llm.VpnOpenBlockedException

internal sealed interface ChoiceTarget {
    data class Search(val target: SearchTarget) : ChoiceTarget
    data class Vpn(val optionId: String) : ChoiceTarget
}

internal enum class LocalVpnRequest { GENERAL, HAPP, DISCONNECT, AMBIGUOUS, NONE }
internal fun classifyLocalVpnRequest(text: String): LocalVpnRequest = when {
    VpnDispatcher.isDisconnectRequest(text) -> LocalVpnRequest.DISCONNECT
    VpnDispatcher.isExplicitRequest(text) -> LocalVpnRequest.GENERAL
    HappConnect.isExplicitRequest(text) -> LocalVpnRequest.HAPP
    VpnDispatcher.isAmbiguousCommand(text) -> LocalVpnRequest.AMBIGUOUS
    else -> LocalVpnRequest.NONE
}

/** Synchronous operations below are confined to the Android main thread. */
interface SkillEvaluator {
    val state: StateFlow<InteractionLog>
    val voicePresentation: StateFlow<VoicePresentation>
    /** One current legacy result, cleared on the next request or cancellation; never history. */
    val currentOutput: StateFlow<SkillOutput?>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    @MainThread fun processInputEvent(event: InputEvent)

    @MainThread fun cancelPendingInput()
    /** End a completed UI session without forgetting a pending clarification. */
    @MainThread fun finishInputSession() = cancelPendingInput()
    @MainThread fun hasPendingClarification(): Boolean = false
    @MainThread fun resetVoicePresentation()
    @MainThread fun selectChoice(choiceId: Long, optionId: String) = Unit
}

class SkillEvaluatorImpl(
    private val skillContext: SkillContextInternal,
    private val skillHandler: Provider<SkillHandler>,
    private val sttInputDevice: SttInputDeviceWrapper,
    private val polzaSettings: org.stypox.dicio.llm.PolzaSettings,
    private val polzaClient: org.stypox.dicio.llm.PolzaClient,
    private val tvActions: org.stypox.dicio.llm.TvActions,
    private val clearVpn: suspend () -> Unit = { tvActions.clearVpn() },
) : SkillEvaluator {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val requests = LatestInputRequest(scope)
    private val sessionBoundary = InputSessionBoundary(scope, clearVpn)
    private val legacyLock = Mutex()
    private val pendingChoice = PendingChoice<ChoiceTarget>()
    private val pendingClarification = PendingClarification(scope) { android.os.SystemClock.elapsedRealtime() }

    private val _state = MutableStateFlow(
        InteractionLog(
            interactions = listOf(),
            pendingQuestion = null,
        )
    )
    override val state: StateFlow<InteractionLog> = _state
    private val presentation = CurrentVoicePresentation()
    override val voicePresentation: StateFlow<VoicePresentation> = presentation.state
    private val outputSlot = CurrentLegacyOutput()
    override val currentOutput: StateFlow<SkillOutput?> = outputSlot.state
    private var previousFallback: TextFallbackOutput? = null
    private fun checkEntry() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "SkillEvaluator requires the main thread" }
        check(!sessionBoundary.isEnding) { "SkillEvaluator teardown is in progress" }
    }
    override fun resetVoicePresentation() {
        checkEntry()
        requests.cancel()
        pendingChoice.clear()
        sessionBoundary.afterVpnClear()
        presentation.clear()
    }

    // must be kept up to date even when the activity is recreated, for this reason it is `var`
    override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }

    override fun processInputEvent(event: InputEvent) {
        checkEntry()
        requests.cancel()
        pendingChoice.clear()
        sessionBoundary.afterVpnClear {
            requests.replace { suspendProcessInputEvent(event) }
        }
    }

    override fun cancelPendingInput() {
        checkEntry()
        clearInput(preserveClarification = false)
    }

    override fun finishInputSession() {
        checkEntry()
        clearInput(preserveClarification = true)
    }

    override fun hasPendingClarification(): Boolean {
        checkEntry()
        return pendingClarification.isAvailable()
    }

    private fun clearInput(preserveClarification: Boolean) {
        sessionBoundary.end {
            requests.cancel()
            pendingChoice.clear()
            if (!preserveClarification) pendingClarification.clear()
            outputSlot.clear()
            _state.value = InteractionLog(emptyList(), null)
            presentation.clear()
            stopAndCleanup(
                skillContext.speechOutputDevice::stopSpeaking,
                skillContext.speechOutputDevice::cleanup,
            )
        }
    }

    override fun selectChoice(choiceId: Long, optionId: String) {
        checkEntry()
        // Consume synchronously before starting any coroutine or action.
        val (query, target) = pendingChoice.consume(choiceId, optionId) ?: return
        presentation.choosing()
        requests.replace {
            try {
                // A queued clarification must not play over the selected silent action.
                skillContext.speechOutputDevice.stopSpeaking()
                when (target) {
                    is ChoiceTarget.Search -> {
                        val result = tvActions.executeSearchTarget(target.target, query)
                        coroutineContext.ensureActive()
                        presentation.answer(result.text, silent = true)
                    }
                    is ChoiceTarget.Vpn -> {
                        val result = tvActions.chooseVpn(target.optionId)
                        coroutineContext.ensureActive()
                        presentation.answer(result.text, silent = true)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                // Do not log errors from a search: they may contain the query.
                val message = error.localizedMessage ?: "Не удалось выполнить выбор."
                presentation.error(message)
                skillContext.speechOutputDevice.speak(message)
            }
        }
    }

    private suspend fun suspendProcessInputEvent(event: InputEvent) {
        when (event) {
            InputEvent.Started -> {
                outputSlot.clear()
                presentation.clear()
                _state.value = InteractionLog(emptyList(), null)
                skillContext.speechOutputDevice.stopSpeaking()
                (skillContext.speechOutputDevice as? org.stypox.dicio.di.SpeechOutputDeviceWrapper)?.beginSession()
            }
            is InputEvent.Error -> {
                pendingClarification.clear()
                addErrorInteractionFromPending(event.throwable)
            }
            is InputEvent.Final -> {
                outputSlot.clear()
                if (event.utterances.isEmpty()) {
                    pendingClarification.clear()
                    _state.value = InteractionLog(emptyList(), null)
                    presentation.noInput()
                    return
                }
                val request = event.utterances[0].first
                // Conservative expiry: even a VPN referred to by an unknown alias cannot leave
                // a previous dispatch looking current after a new request.
                tvActions.clearVpnStatus()
                val localVpn = classifyLocalVpnRequest(request)
                if (localVpn == LocalVpnRequest.GENERAL || localVpn == LocalVpnRequest.HAPP || localVpn == LocalVpnRequest.DISCONNECT) {
                    pendingClarification.clear()
                    runExplicitVpn(request)
                    return
                }
                if (localVpn == LocalVpnRequest.AMBIGUOUS) {
                    pendingClarification.clear()
                    _state.value = InteractionLog(emptyList(), null)
                    val question = "Скажите явно: подключи VPN, выключи VPN или выключи Happ VPN."
                    presentation.answer(question)
                    skillContext.speechOutputDevice.stopSpeaking()
                    skillContext.speechOutputDevice.speak(question)
                    return
                }
                val previousClarification = pendingClarification.consume()
                if (previousClarification != null && isClarificationCancellation(event.utterances[0].first)) {
                    _state.value = InteractionLog(emptyList(), null)
                    presentation.answer("Отменено.")
                    return
                }
                presentation.thinking(event.utterances[0].first)
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterances[0].first,
                        continuesLastInteraction = false,
                        skillBeingEvaluated = null,
                    )
                )
                val config = try {
                    val settingsStarted = android.os.SystemClock.elapsedRealtime()
                    withContext(Dispatchers.IO) { polzaSettings.load() }.also {
                        Log.i("DicioPolza", "Settings loaded elapsedMs=${android.os.SystemClock.elapsedRealtime() - settingsStarted}")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    coroutineContext.ensureActive()
                    addErrorInteractionFromPending(IllegalStateException("Не удалось прочитать настройки RouterAI."))
                    return
                }
                if (config.enabled) {
                    try {
                        _state.value = _state.value.copy(pendingQuestion =
                            _state.value.pendingQuestion?.copy(waitingForLlm = true))
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        val apps = tvActions.catalog(forceRefresh = previousClarification != null)
                        val media = tvActions.mediaSnapshot()
                        val catalogDone = android.os.SystemClock.elapsedRealtime()
                        Log.i("DicioPolza", "Catalog ready elapsedMs=${catalogDone - startedAt} count=${apps.size}")
                        val clarificationInput = resolveClarificationInput(previousClarification, request,
                            apps.map { StandaloneApp(it.id, it.label) })
                        val standalone = clarificationInput.standalone
                        val clarification = clarificationInput.context
                        val decision = polzaClient.decide(config, event.utterances[0].first,
                            apps.associate { it.id to it.label }, media.targets, clarification?.context,
                            contextIsFresh = { clarification?.isFresh(android.os.SystemClock.elapsedRealtime()) != false })
                        coroutineContext.ensureActive()
                        if (standalone?.allows(decision) == false) {
                            _state.value = InteractionLog(emptyList(), null)
                            val question = "Пожалуйста, повторите команду полностью."
                            presentation.answer(question)
                            skillContext.speechOutputDevice.speak(question)
                            return
                        }
                        if (decision is AssistantIntent.ConnectHapp ||
                            (VpnDispatcher.mentionsVpn(request) && !decision.allowedAfterClarification())) {
                            _state.value = InteractionLog(emptyList(), null)
                            val question = "Скажите явно: подключи VPN или подключи Happ VPN."
                            presentation.answer(question)
                            skillContext.speechOutputDevice.speak(question)
                            return
                        }
                        if (clarification != null && !decision.allowedAfterClarification()) {
                            _state.value = InteractionLog(emptyList(), null)
                            val question = "Пожалуйста, повторите команду полностью."
                            presentation.answer(question)
                            skillContext.speechOutputDevice.speak(question)
                            return
                        }
                        if (decision is AssistantIntent.ChooseSearch) {
                            try {
                                val query = ContentSearch.validateQuery(decision.query)
                                val targets = tvActions.searchTargets()
                                coroutineContext.ensureActive()
                                check(targets.isNotEmpty()) { "Нет доступных сервисов поиска." }
                                val id = pendingChoice.create(query, targets.associate { it.id to ChoiceTarget.Search(it) })
                                outputSlot.clear()
                                previousFallback = null
                                _state.value = InteractionLog(emptyList(), null)
                                val question = "Где искать?"
                                presentation.choice(id, targets.map { ChoiceOption(it.id, it.label) }, question)
                                skillContext.speechOutputDevice.speak(question)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                coroutineContext.ensureActive()
                                pendingChoice.clear()
                                outputSlot.clear()
                                previousFallback = null
                                _state.value = InteractionLog(emptyList(), null)
                                presentation.error(error.localizedMessage ?: "Не удалось получить сервисы поиска.")
                                skillContext.speechOutputDevice.speak(error.localizedMessage ?: "Не удалось получить сервисы поиска.")
                            }
                            return
                        }
                        val providerDone = android.os.SystemClock.elapsedRealtime()
                        Log.i("DicioPolza", "Decision received elapsedMs=${providerDone - catalogDone} type=${decision.javaClass.simpleName}")
                        val result = tvActions.execute(decision, apps, skillContext.locale, media, event.utterances[0].first)
                        Log.i("DicioPolza", "Response received elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} chars=${result.text.length} speak=${result.speak}")
                        coroutineContext.ensureActive()
                        outputSlot.clear()
                        previousFallback = null
                        _state.value = InteractionLog(emptyList(), null)
                        presentation.answer(result.text, silent = !result.speak)
                        if (decision is AssistantIntent.Clarify) {
                            // Replace, never extend, the previous one-use context.
                            pendingClarification.save(request, decision.text)
                        }
                        if (result.speak) {
                            withContext(Dispatchers.Main) { skillContext.speechOutputDevice.speak(result.text) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        coroutineContext.ensureActive()
                        addErrorInteractionFromPending(error)
                        skillContext.speechOutputDevice.speak(error.localizedMessage ?: "Не удалось выполнить запрос.")
                    }
                } else {
                    evaluateMatchingSkill(event.utterances.map { it.first })
                }
            }
            InputEvent.None -> {
                pendingClarification.clear()
                outputSlot.clear()
                presentation.noInput()
                _state.value = InteractionLog(emptyList(), null)
            }
            is InputEvent.Partial -> {
                presentation.listening(event.utterance)
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterance,
                        continuesLastInteraction = false,
                        skillBeingEvaluated = null,
                    )
                )
            }
        }
    }

    /** Complete local commands never inherit a prior clarification or go through the model. */
    private suspend fun runExplicitVpn(request: String) {
        outputSlot.clear()
        previousFallback = null
        _state.value = InteractionLog(emptyList(), null)
        presentation.thinking(request)
        try {
            skillContext.speechOutputDevice.stopSpeaking()
            if (VpnDispatcher.isDisconnectRequest(request)) {
                val result = tvActions.disconnectVpn(request)
                coroutineContext.ensureActive()
                presentation.answer(result.text, silent = result.actionSent)
                if (!result.actionSent) skillContext.speechOutputDevice.speak(result.text)
                return
            }
            if (HappConnect.isExplicitRequest(request)) {
                val result = tvActions.execute(AssistantIntent.ConnectHapp, emptyList(), skillContext.locale, userRequest = request)
                coroutineContext.ensureActive()
                presentation.answer(result.text, silent = true)
                return
            }
            when (val result = tvActions.beginVpn(request)) {
                is VpnDispatch.Message -> {
                    coroutineContext.ensureActive()
                    presentation.answer(result.text, silent = result.actionSent)
                    if (!result.actionSent) skillContext.speechOutputDevice.speak(result.text)
                }
                is VpnDispatch.Choice -> {
                    coroutineContext.ensureActive()
                    val id = pendingChoice.create("", result.options.associate { it.id to ChoiceTarget.Vpn(it.id) })
                    presentation.choice(id, result.options.map { ChoiceOption(it.id, it.label) }, result.prompt, "Выберите VPN-приложение")
                    skillContext.speechOutputDevice.speak(result.prompt)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            pendingChoice.clear()
            tvActions.clearVpn()
            val message = error.localizedMessage ?: "Не удалось открыть VPN-приложение."
            presentation.error(message)
            skillContext.speechOutputDevice.speak(message)
        }
    }

    private suspend fun evaluateMatchingSkill(utterances: List<String>) = legacyLock.withLock {
        // Capture one ready ranker for the entire request. The next request rechecks the handler,
        // so settings/locale replacements are observed without splitting a dialogue plan across rankers.
        val skillRanker = try {
            skillHandler.get().awaitSkillRanker()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            addErrorInteractionFromPending(error)
            return@withLock
        }
        coroutineContext.ensureActive()
        val (chosenInput, chosenSkill) = try {
            withContext(Dispatchers.Default) { utterances.firstNotNullOfOrNull { input: String ->
                skillContext.standardMatchHelper = MatchHelper(skillContext.parserFormatter, input)
                skillRanker.getBest(skillContext, input)?.let { skillWithResult ->
                    Pair(input, skillWithResult)
                }
            } ?: Pair(utterances[0], skillRanker.getFallbackSkill(skillContext, utterances[0])) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            coroutineContext.ensureActive()
            addErrorInteractionFromPending(throwable)
            return@withLock
        } finally {
            // standardMatchHelper only needs to be set while calling score() on skills, so once
            // all matching and scoring is done, free up the memory it uses (which may be
            // significant since the purpose of MatchHelper is to cache information about the input)
            skillContext.standardMatchHelper = null
        }
        coroutineContext.ensureActive()
        val skillInfo = chosenSkill.skill.correspondingSkillInfo
        presentation.thinking(chosenInput)

        _state.value = _state.value.copy(
            pendingQuestion = PendingQuestion(
                userInput = chosenInput,
                // the skill ranker would have discarded all batches, if the chosen skill was not
                // the continuation of the last interaction (since continuing an
                // interaction/conversation is done through the stack of batches)
                continuesLastInteraction = skillRanker.hasAnyBatches(),
                skillBeingEvaluated = skillInfo,
            )
        )

        try {
            val permissions = skillInfo.neededPermissions
            if (permissions.isNotEmpty() && !permissionRequester(permissions)) {
                // permissions were not granted, show message
                coroutineContext.ensureActive()
                previousFallback = null
                _state.value = InteractionLog(emptyList(), null)
                presentation.error(MissingPermissionsSkillOutput(skillInfo).getSpeechOutput(skillContext))
                return@withLock
            }

            skillContext.previousOutput = previousFallback
            val output = try {
                withContext(Dispatchers.Default) { chosenSkill.generateOutput(skillContext) }
            } finally {
                skillContext.previousOutput = null
            }
            coroutineContext.ensureActive()

            val interactionPlan = output.getInteractionPlan(skillContext)
            previousFallback = output as? TextFallbackOutput
            _state.value = InteractionLog(emptyList(), null)
            val speech = output.getSpeechOutput(skillContext)
            outputSlot.replace(if (output is ErrorSkillOutput) null else output)
            presentation.answer(speech)
            speech.let {
                if (it.isNotBlank()) {
                    withContext (Dispatchers.Main) {
                        skillContext.speechOutputDevice.speak(it)
                    }
                }
            }

            when (interactionPlan) {
                InteractionPlan.FinishInteraction -> {
                    // current conversation has ended, reset to the default batch of skills
                    skillRanker.removeAllBatches()
                }
                is InteractionPlan.FinishSubInteraction -> {
                    skillRanker.removeTopBatch()
                }
                is InteractionPlan.Continue -> {
                    // nothing to do, just continue with current batches
                }
                is InteractionPlan.StartSubInteraction -> {
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
                is InteractionPlan.ReplaceSubInteraction -> {
                    skillRanker.removeTopBatch()
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
            }

            // Manual TV input: preserve dialogue state, but never reopen the microphone.

        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            coroutineContext.ensureActive()
            addErrorInteractionFromPending(throwable)
            if (throwable is VpnOpenBlockedException) skillContext.speechOutputDevice.speak(throwable.message ?: "Для VPN нужна явная команда.")
            return@withLock
        }
    }

    private fun addErrorInteractionFromPending(throwable: Throwable) {
        if (throwable is VpnOpenBlockedException) tvActions.clearVpnStatus()
        pendingClarification.clear()
        outputSlot.clear()
        previousFallback = null
        _state.value = InteractionLog(emptyList(), null)
        presentation.error(throwable.localizedMessage ?: "Повторите запрос по кнопке Ассистента.")
        Log.e(TAG, "Error while evaluating skills", throwable)
    }

    companion object {
        val TAG = SkillEvaluator::class.simpleName
    }
}

internal fun AssistantIntent.allowedAfterClarification(): Boolean =
    this is AssistantIntent.Reply || this is AssistantIntent.Clarify

@Module
@InstallIn(SingletonComponent::class)
class SkillEvaluatorModule {
    @Provides
    @Singleton
    fun provideSkillEvaluator(
        skillContext: SkillContextInternal,
        skillHandler: Provider<SkillHandler>,
        sttInputDevice: SttInputDeviceWrapper,
        polzaSettings: org.stypox.dicio.llm.PolzaSettings,
        polzaClient: org.stypox.dicio.llm.PolzaClient,
        tvActions: org.stypox.dicio.llm.TvActions,
    ): SkillEvaluator {
        return SkillEvaluatorImpl(skillContext, skillHandler, sttInputDevice, polzaSettings, polzaClient, tvActions)
    }
}
