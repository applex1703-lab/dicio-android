package org.stypox.dicio.eval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Missing optional context must not suppress text replies or swallow cancellation. */
internal suspend fun <T> optionalSnapshot(empty: T, load: suspend () -> T): T = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    coroutineContext.ensureActive()
    empty
}
