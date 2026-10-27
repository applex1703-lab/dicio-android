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
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer
import javax.inject.Singleton
import org.dicio.skill.standard.util.MatchHelper

interface SkillEvaluator {
    val state: StateFlow<InteractionLog>
    val voicePresentation: StateFlow<VoicePresentation>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    fun processInputEvent(event: InputEvent)

    fun cancelPendingInput()
    fun resetVoicePresentation()
}

class SkillEvaluatorImpl(
    private val skillContext: SkillContextInternal,
    private val skillHandler: SkillHandler,
    private val sttInputDevice: SttInputDeviceWrapper,
    private val polzaSettings: org.stypox.dicio.llm.PolzaSettings,
    private val polzaClient: org.stypox.dicio.llm.PolzaClient,
    private val tvActions: org.stypox.dicio.llm.TvActions,
) : SkillEvaluator {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val requests = LatestInputRequest(scope)

    private val skillRanker: SkillRanker
        get() = skillHandler.skillRanker.value

    private val _state = MutableStateFlow(
        InteractionLog(
            interactions = listOf(),
            pendingQuestion = null,
        )
    )
    override val state: StateFlow<InteractionLog> = _state
    private val _voicePresentation = MutableStateFlow(VoicePresentation())
    override val voicePresentation: StateFlow<VoicePresentation> = _voicePresentation
    override fun resetVoicePresentation() { _voicePresentation.value = VoicePresentation() }

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
            _state.value = _state.value.copy(pendingQuestion = null)
            skillContext.speechOutputDevice.stopSpeaking()
            skillContext.speechOutputDevice.cleanup()
        }
    }

    private suspend fun suspendProcessInputEvent(event: InputEvent) {
        when (event) {
            InputEvent.Started -> {
                _voicePresentation.value = VoicePresentation()
                _state.value = _state.value.copy(pendingQuestion = null)
                skillContext.speechOutputDevice.stopSpeaking()
                (skillContext.speechOutputDevice as? org.stypox.dicio.di.SpeechOutputDeviceWrapper)?.beginSession()
            }
            is InputEvent.Error -> {
                addErrorInteractionFromPending(event.throwable)
            }
            is InputEvent.Final -> {
                if (event.utterances.isEmpty()) return
                _voicePresentation.value = VoicePresentation(request = event.utterances[0].first, status = "Думаю…")
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterances[0].first,
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
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
                        val output = org.stypox.dicio.llm.AssistantOutput(result.text, result.speak)
                        addInteractionFromPending(output)
                        _voicePresentation.value = _voicePresentation.value.copy(
                            status = if (result.speak) "Ответ" else "Выполнено",
                            answer = if (result.speak) result.text else "", dismiss = !result.speak)
                        if (result.speak) {
                            withContext(Dispatchers.Main) { skillContext.speechOutputDevice.speak(result.text) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        addErrorInteractionFromPending(error)
                    }
                } else {
                    withContext(Dispatchers.Default) {
                        evaluateMatchingSkill(event.utterances.map { it.first })
                    }
                }
            }
            InputEvent.None -> {
                _voicePresentation.value = _voicePresentation.value.copy(status = "Речь не распознана",
                    answer = "Нажмите кнопку Ассистента, чтобы попробовать снова.")
                _state.value = _state.value.copy(pendingQuestion = null)
            }
            is InputEvent.Partial -> {
                _voicePresentation.value = VoicePresentation(request = event.utterance, status = "Слушаю…")
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterance,
                        // the next input can be a continuation of the last interaction only if the
                        // last skill invocation provided some skill batches (which are the only way
                        // to continue an interaction/conversation)
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
            }
        }
    }

    private suspend fun evaluateMatchingSkill(utterances: List<String>) {
        val (chosenInput, chosenSkill) = try {
            utterances.firstNotNullOfOrNull { input: String ->
                skillContext.standardMatchHelper = MatchHelper(skillContext.parserFormatter, input)
                skillRanker.getBest(skillContext, input)?.let { skillWithResult ->
                    Pair(input, skillWithResult)
                }
            } ?: Pair(utterances[0], skillRanker.getFallbackSkill(skillContext, utterances[0]))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            return
        } finally {
            // standardMatchHelper only needs to be set while calling score() on skills, so once
            // all matching and scoring is done, free up the memory it uses (which may be
            // significant since the purpose of MatchHelper is to cache information about the input)
            skillContext.standardMatchHelper = null
        }
        val skillInfo = chosenSkill.skill.correspondingSkillInfo

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
                addInteractionFromPending(MissingPermissionsSkillOutput(skillInfo))
                return
            }

            skillContext.previousOutput =
                _state.value.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
            val output = chosenSkill.generateOutput(skillContext)
            coroutineContext.ensureActive()

            val interactionPlan = output.getInteractionPlan(skillContext)
            addInteractionFromPending(output)
            _voicePresentation.value = _voicePresentation.value.copy(status = "Ответ",
                answer = output.getSpeechOutput(skillContext))
            output.getSpeechOutput(skillContext).let {
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
            addErrorInteractionFromPending(throwable)
            return
        }
    }

    private fun addErrorInteractionFromPending(throwable: Throwable) {
        _voicePresentation.value = _voicePresentation.value.copy(status = "Не удалось выполнить запрос",
            answer = throwable.localizedMessage ?: "Повторите запрос по кнопке Ассистента.", dismiss = false)
        Log.e(TAG, "Error while evaluating skills", throwable)
        addInteractionFromPending(ErrorSkillOutput(throwable, true))
    }

    private fun addInteractionFromPending(skillOutput: SkillOutput) {
        val log = _state.value
        val pendingUserInput = log.pendingQuestion?.userInput
        val pendingContinuesLastInteraction = log.pendingQuestion?.continuesLastInteraction
            ?: skillRanker.hasAnyBatches()
        val pendingSkill = log.pendingQuestion?.skillBeingEvaluated
        val questionAnswer = QuestionAnswer(pendingUserInput, skillOutput)

        _state.value = log.copy(
            interactions = log.interactions.toMutableList().also { inters ->
                if (pendingContinuesLastInteraction && inters.isNotEmpty()) {
                    inters[inters.size - 1] = inters[inters.size - 1].let { i -> i.copy(
                        questionsAnswers = i.questionsAnswers.toMutableList()
                            .apply { add(questionAnswer) }
                    ) }
                } else {
                    inters.add(
                        Interaction(
                            skill = pendingSkill,
                            questionsAnswers = listOf(questionAnswer)
                        )
                    )
                }
            },
            pendingQuestion = null,
        )
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
        skillHandler: SkillHandler,
        sttInputDevice: SttInputDeviceWrapper,
        polzaSettings: org.stypox.dicio.llm.PolzaSettings,
        polzaClient: org.stypox.dicio.llm.PolzaClient,
        tvActions: org.stypox.dicio.llm.TvActions,
    ): SkillEvaluator {
        return SkillEvaluatorImpl(skillContext, skillHandler, sttInputDevice, polzaSettings, polzaClient, tvActions)
    }
}
