package org.stypox.dicio.eval

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.fallback.text.TextFallbackInfo
import org.stypox.dicio.skills.lyrics.LyricsInfo
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.translation.TranslationInfo
import org.stypox.dicio.skills.weather.WeatherInfo
import org.stypox.dicio.skills.joke.JokeInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillHandler @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val skillContext: SkillContextInternal,
) {
    // TODO improve id handling (maybe just use an int that can point to an Android resource)
    val allSkillInfoList = listOf(
        WeatherInfo,
        SearchInfo,
        LyricsInfo,
        OpenInfo,
        CalculatorInfo,
        NavigationInfo,
        TimerInfo,
        CurrentTimeInfo,
        MediaInfo,
        JokeInfo,
        TranslationInfo,
    )

    // Merely listing skills in settings must not build the matcher or subscribe to settings.
    // The same state is retained after first use so legacy dialogue batches keep working.
    private val matcherDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { createMatcher() }
    private val matcher by matcherDelegate
    internal val isMatcherInitialized: Boolean get() = matcherDelegate.isInitialized()

    val enabledSkillsInfo: StateFlow<List<SkillInfo>?> get() = matcher.enabledSkillsInfo
    val skillRanker: StateFlow<SkillRanker> get() = matcher.skillRanker

    /** Wait for the first real matcher rather than evaluating against the placeholder fallback. */
    suspend fun awaitSkillRanker(): SkillRanker {
        val state = matcher
        val pending = state.currentResult()
        try {
            return pending.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.failureObserved(pending)
            throw error
        }
    }

    private inner class MatcherState(
        val enabledSkillsInfo: MutableStateFlow<List<SkillInfo>?>,
        val skillRanker: MutableStateFlow<SkillRanker>,
    ) {
        private var result = CompletableDeferred<SkillRanker>()
        private val scope = CoroutineScope(Dispatchers.Default)
        private var stopped = false
        private var observedStoppedFailure = false

        init { startCollector() }

        @Synchronized
        fun currentResult(): CompletableDeferred<SkillRanker> {
            if (stopped && observedStoppedFailure) {
                stopped = false
                observedStoppedFailure = false
                result = CompletableDeferred()
                startCollector()
            }
            return result
        }

        @Synchronized
        fun failureObserved(pending: CompletableDeferred<SkillRanker>) {
            if (stopped && result === pending) observedStoppedFailure = true
        }

        @Synchronized
        private fun publishSuccess(ranker: SkillRanker) {
            if (result.isCompleted) result = CompletableDeferred()
            result.complete(ranker)
        }

        @Synchronized
        private fun publishFailure(error: Exception) {
            if (result.isCompleted) result = CompletableDeferred()
            result.completeExceptionally(error)
        }

        private fun startCollector() {
            scope.launch {
                try {
                    localeManager.locale
                        .combine(dataStore.data) { locale, data -> Pair(locale, data.enabledSkillsMap) }
                        .distinctUntilChanged()
                        .collectLatest { (_, enabledSkills) ->
                            try {
                                val skills = allSkillInfoList
                                    .filter { enabledSkills.getOrDefault(it.id, true) }
                                    .mapNotNull { info -> info.build(skillContext)?.let { skill -> Pair(info, skill) } }
                                val ranker = SkillRanker(skills.map { it.second }, TextFallbackInfo.build(skillContext)!!)
                                skillRanker.value = ranker
                                enabledSkillsInfo.value = skills.map { it.first }
                                publishSuccess(ranker)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                // Keep collecting: a later settings or locale change can recover.
                                publishFailure(error)
                            }
                        }
                    throw IllegalStateException("Skill settings stream completed")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(this@MatcherState) {
                        stopped = true
                        publishFailure(error)
                    }
                }
            }
        }
    }

    private fun createMatcher(): MatcherState {
        val enabledSkillsInfo = MutableStateFlow<List<SkillInfo>?>(null)
        val skillRanker = MutableStateFlow(
            SkillRanker(listOf(), TextFallbackInfo.build(skillContext)!!)
        )
        return MatcherState(enabledSkillsInfo, skillRanker)
    }

    companion object {
        fun newForPreviews(context: Context): SkillHandler {
            return SkillHandler(
                UserSettingsModule.newDataStoreForPreviews(),
                LocaleManager.newForPreviews(context),
                SkillContextImpl.newForPreviews(context),
            )
        }
    }
}
