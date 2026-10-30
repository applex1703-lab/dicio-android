package org.stypox.dicio.eval

import android.util.Log
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

interface SkillEvaluator {
    val state: StateFlow<InteractionLog>
    val voicePresentation: StateFlow<VoicePresentation>
    /** One current legacy result, cleared on the next request or cancellation; never history. */
    val currentOutput: StateFlow<SkillOutput?>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    fun processInputEvent(event: InputEvent)

    fun cancelPendingInput()
    fun resetVoicePresentation()
}

class SkillEvaluatorImpl(
    private val skillContext: SkillContextInternal,
    private val skillHandler: Provider<SkillHandler>,
    private val sttInputDevice: SttInputDeviceWrapper,
    private val polzaSettings: org.stypox.dicio.llm.PolzaSettings,
    private val polzaClient: org.stypox.dicio.llm.PolzaClient,
    private val tvActions: org.stypox.dicio.llm.TvActions,
) : SkillEvaluator {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val requests = LatestInputRequest(scope)
    private val legacyLock = Mutex()

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
    override fun resetVoicePresentation() { presentation.clear() }

    // must be kept up to date even when the activity is recreated, for this reason it is `var`
    override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }

    override fun processInputEvent(event: InputEvent) {
        scope.launch {
            requests.replace { suspendProcessInputEvent(event) }
        }
    }

    override fun cancelPendingInput() {
        scope.launch {
            requests.cancel()
            outputSlot.clear()
            _state.value = InteractionLog(emptyList(), null)
            presentation.clear()
            skillContext.speechOutputDevice.stopSpeaking()
            skillContext.speechOutputDevice.cleanup()
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
                addErrorInteractionFromPending(event.throwable)
            }
            is InputEvent.Final -> {
                outputSlot.clear()
                if (event.utterances.isEmpty()) {
                    _state.value = InteractionLog(emptyList(), null)
                    presentation.noInput()
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
                    addErrorInteractionFromPending(IllegalStateException("Не удалось прочитать настройки RouterAI."))
                    return
                }
                if (config.enabled) {
                    try {
                        _state.value = _state.value.copy(pendingQuestion =
                            _state.value.pendingQuestion?.copy(waitingForLlm = true))
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        val apps = tvActions.catalog()
                        val media = tvActions.mediaSnapshot()
                        val catalogDone = android.os.SystemClock.elapsedRealtime()
                        Log.i("DicioPolza", "Catalog ready elapsedMs=${catalogDone - startedAt} count=${apps.size}")
                        val decision = polzaClient.decide(config, event.utterances[0].first,
                            apps.associate { it.id to it.label }, media.targets)
                        coroutineContext.ensureActive()
                        val providerDone = android.os.SystemClock.elapsedRealtime()
                        Log.i("DicioPolza", "Decision received elapsedMs=${providerDone - catalogDone} type=${decision.javaClass.simpleName}")
                        val result = tvActions.execute(decision, apps, skillContext.locale, media)
                        Log.i("DicioPolza", "Response received elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} chars=${result.text.length} speak=${result.speak}")
                        coroutineContext.ensureActive()
                        outputSlot.clear()
                        previousFallback = null
                        _state.value = InteractionLog(emptyList(), null)
                        presentation.answer(result.text, silent = !result.speak)
                        if (result.speak) {
                            withContext(Dispatchers.Main) { skillContext.speechOutputDevice.speak(result.text) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        addErrorInteractionFromPending(error)
                    }
                } else {
                    evaluateMatchingSkill(event.utterances.map { it.first })
                }
            }
            InputEvent.None -> {
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
            return@withLock
        }
    }

    private fun addErrorInteractionFromPending(throwable: Throwable) {
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
