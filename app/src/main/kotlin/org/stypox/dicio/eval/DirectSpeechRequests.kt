package org.stypox.dicio.eval

/** Main-thread request fence for new evaluator-originated speech operations only.
 * Already submitted speech and the shared device queue are not isolated or cancelled here.
 */
internal class DirectSpeechRequests {
    private var current = Any()

    fun next(): Any = Any().also { current = it }
    fun isCurrent(request: Any): Boolean = current === request
    inline fun ifCurrent(request: Any, action: () -> Unit) {
        if (isCurrent(request)) action()
    }
}
