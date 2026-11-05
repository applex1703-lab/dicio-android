package org.stypox.dicio.voice

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javax.inject.Inject
import javax.inject.Singleton
import java.lang.ref.WeakReference
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.input.InputEvent

/** Main-thread ownership of the microphone and evaluator, which have independent lifetimes. */
@Singleton
class VoiceInputCoordinator @Inject constructor(
    private val input: SttInputDeviceWrapper,
    private val evaluator: SkillEvaluator,
) {
    private val main = Handler(Looper.getMainLooper())
    private class InputLease(val owner: Any, val revoked: () -> Unit)
    private class EvaluationLease(val owner: Any, val scope: Any, val revoked: () -> Unit)
    private var inputLease: InputLease? by mutableStateOf(null)
    private var evaluationLease: EvaluationLease? by mutableStateOf(null)
    private class Retained(val owner: WeakReference<Any>, val scope: WeakReference<Any>)
    private val tvScope = Any()
    private var retainedOwner: Retained? = null
    private var tearingDown = false
    // A throwing wrapper has not provided a trustworthy cleanup result.
    private var inputFaulted = false
    private var evaluatorFaulted = false
    private val deferredReleases = mutableListOf<Pair<Any, Boolean>>()

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun prepareRetained(owner: Any, scope: Any): Boolean {
        val retained = retainedOwner ?: return true
        if (retained.scope.get() === scope) {
            retainedOwner = Retained(WeakReference(owner), WeakReference(scope))
            return true
        }
        retainedOwner = null
        return try {
            evaluator.cancelPendingInput()
            true
        } catch (error: Exception) {
            evaluatorFaulted = true
            android.util.Log.e("VoiceInputCoordinator", "Evaluator teardown failed", error)
            false
        }
    }

    private fun inputCleanupSafe(): Boolean {
        if (inputFaulted) return false
        return try { input.isCleanupSafe() } catch (error: Exception) {
            inputFaulted = true
            android.util.Log.e("VoiceInputCoordinator", "Could not determine input cleanup state", error)
            false
        }
    }

    /** Observed by Home so another owner's work cannot consume its Back press. */
    fun ownsWork(owner: Any): Boolean {
        checkMain()
        return inputLease?.owner === owner || evaluationLease?.owner === owner
    }

    /** Explicit voice request; preempts prior input and evaluation, but never retries. */
    fun start(owner: Any, onRevoked: () -> Unit = {}): Boolean = startScoped(owner, owner, onRevoked)

    /** Only TV session instances use this process-local scope; lease ownership remains per instance. */
    internal fun startTv(owner: Any, onRevoked: () -> Unit = {}): Boolean = startScoped(owner, tvScope, onRevoked)

    private fun startScoped(owner: Any, scope: Any, onRevoked: () -> Unit): Boolean {
        checkMain()
        if (tearingDown || evaluatorFaulted || !inputCleanupSafe()) return false
        if (inputLease?.owner === owner && evaluationLease?.owner === owner) return true
        if (!prepareRetained(owner, scope)) return false
        if (!clear(inputLease, evaluationLease, notify = true,
                preserveClarification = evaluationLease?.scope === scope, retainFor = owner)) return false
        if (inputLease != null || evaluationLease != null) return false
        evaluator.resetVoicePresentation()
        val evaluation = EvaluationLease(owner, scope, onRevoked)
        evaluationLease = evaluation
        return startInput(owner, {}, evaluator::processInputEvent, evaluation)
    }

    /**
     * Fail closed for another owner's evaluation until explicit release, even after STT ends.
     * Release invokes evaluator teardown but does not prove physical TTS silence if teardown fails.
     */
    fun startPopup(owner: Any, onEvent: (InputEvent) -> Unit): Boolean {
        checkMain()
        if (tearingDown || evaluatorFaulted || !inputCleanupSafe()) return false
        if (inputLease?.owner === owner) return true
        if (inputLease != null || evaluationLease?.let { it.owner !== owner } == true) return false
        if (!prepareRetained(owner, owner)) return false
        return startInput(owner, { onEvent(InputEvent.None) }, onEvent)
    }

    fun startService(owner: Any, onEvent: (InputEvent) -> Unit, onRevoked: () -> Unit): Boolean? {
        checkMain()
        if (tearingDown || inputLease != null || evaluationLease?.let { it.owner !== owner } == true) return null
        if (evaluatorFaulted || !inputCleanupSafe()) return false
        if (!prepareRetained(owner, owner)) return false
        return startInput(owner, onRevoked, onEvent)
    }

    fun stop(owner: Any) {
        checkMain()
        if (!tearingDown && inputLease?.owner === owner) input.stopListening()
    }

    private fun startInput(
        owner: Any,
        onRevoked: () -> Unit,
        onEvent: (InputEvent) -> Unit,
        evaluation: EvaluationLease? = null,
    ): Boolean {
        val lease = InputLease(owner, onRevoked)
        inputLease = lease
        var terminalDelivered = false
        val loaded = input.tryLoad { event ->
            val deliver = deliver@{
                if (inputLease === lease) {
                    if (event is InputEvent.Final || event is InputEvent.Error || event is InputEvent.None) {
                        terminalDelivered = true
                        val cleaned = clear(lease, null, notify = false)
                        if (!cleaned) {
                            if (evaluation == null || evaluationLease === evaluation) {
                                onEvent(InputEvent.Error(java.io.IOException("Voice input cleanup did not complete")))
                            }
                            return@deliver
                        }
                    }
                    if (evaluation == null || evaluationLease === evaluation) onEvent(event)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) deliver() else main.post { deliver() }
        }
        if (!loaded && !terminalDelivered) clear(lease, evaluation, notify = false)
        return loaded || terminalDelivered
    }

    fun startHome(owner: Any): Boolean = start(owner)

    fun selectChoice(owner: Any, choiceId: Long, optionId: String) {
        checkMain()
        if (tearingDown || evaluatorFaulted || evaluationLease?.owner !== owner) return
        evaluator.selectChoice(choiceId, optionId)
    }

    fun submitText(owner: Any, text: String) {
        checkMain()
        if (tearingDown) return
        if (evaluatorFaulted || !prepareRetained(owner, owner)) return
        if (!clear(inputLease, evaluationLease, notify = true,
                preserveClarification = evaluationLease?.scope === owner, retainFor = owner)) return
        if (inputLease != null || evaluationLease != null || evaluatorFaulted || !inputCleanupSafe()) return
        evaluationLease = EvaluationLease(owner, owner) {}
        evaluator.processInputEvent(InputEvent.Final(listOf(text to 1.0f)))
    }

    fun release(owner: Any) {
        checkMain()
        if (tearingDown) {
            deferredReleases.add(owner to true)
            return
        }
        clear(inputLease?.takeIf { it.owner === owner },
            evaluationLease?.takeIf { it.owner === owner }, notify = true, preserveClarification = true)
    }

    fun cancel(owner: Any) {
        checkMain()
        if (tearingDown) {
            deferredReleases.add(owner to false)
            return
        }
        val retained = retainedOwner?.owner?.get() === owner
        val active = evaluationLease?.owner === owner
        clear(inputLease?.takeIf { it.owner === owner },
            evaluationLease?.takeIf { it.owner === owner }, notify = false)
        if (retained && !active) {
            retainedOwner = null
            try { evaluator.cancelPendingInput() }
            catch (error: Exception) {
                evaluatorFaulted = true
                android.util.Log.e("VoiceInputCoordinator", "Evaluator teardown failed", error)
            }
        }
    }

    private fun clear(inputTarget: InputLease?, evaluationTarget: EvaluationLease?, notify: Boolean,
        preserveClarification: Boolean = false, retainFor: Any? = null): Boolean {
        val oldInput = inputTarget?.takeIf { inputLease === it }
        val oldEvaluation = evaluationTarget?.takeIf { evaluationLease === it }
        if (oldInput == null && oldEvaluation == null) return true
        val keep = preserveClarification && oldInput == null && oldEvaluation != null &&
            evaluator.hasPendingClarification()
        if (oldEvaluation != null) retainedOwner = if (keep)
            Retained(WeakReference(retainFor ?: oldEvaluation.owner), WeakReference(oldEvaluation.scope)) else null
        if (oldInput != null) inputLease = null
        if (oldEvaluation != null) evaluationLease = null
        tearingDown = true
        var cleaned = true
        try {
            if (oldInput != null) {
                try {
                    cleaned = input.reinitializeToReleaseResources()
                } catch (error: Exception) {
                    cleaned = false
                    inputFaulted = true
                    android.util.Log.e("VoiceInputCoordinator", "Input teardown failed", error)
                }
            }
            if (oldEvaluation != null) {
                // This API is synchronous: success does not prove asynchronous work or TTS has stopped.
                try {
                    if (keep && cleaned) evaluator.finishInputSession() else {
                        retainedOwner = null
                        evaluator.cancelPendingInput()
                    }
                }
                catch (error: Exception) {
                    cleaned = false
                    evaluatorFaulted = true
                    android.util.Log.e("VoiceInputCoordinator", "Evaluator teardown failed", error)
                }
            }
            if (notify) {
                // Reject reentrant acquisitions until every old owner has been notified.
                try { oldInput?.revoked?.invoke() }
                catch (error: Exception) {
                    android.util.Log.e("VoiceInputCoordinator", "Input revocation failed", error)
                }
                try { oldEvaluation?.revoked?.invoke() }
                catch (error: Exception) {
                    android.util.Log.e("VoiceInputCoordinator", "Evaluation revocation failed", error)
                }
            }
        } finally {
            tearingDown = false
            while (deferredReleases.isNotEmpty()) {
                val (owner, notifyRelease) = deferredReleases.removeAt(0)
                if (notifyRelease) release(owner) else cancel(owner)
            }
        }
        return cleaned
    }
}
