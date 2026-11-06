package org.stypox.dicio.voice

/** Reject delayed, future-dated and implausibly long gestures; never schedule a retry. */
internal fun isRecentAssistEvent(downTime: Long, eventTime: Long, now: Long): Boolean =
    downTime >= 0 && eventTime >= downTime && now >= eventTime &&
        now - eventTime <= 1_000 && now - downTime <= 5_000

/** Matches a complete assist-key gesture that began after this window became visible. */
internal class ExplicitAssistGesture {
    private data class Press(val key: Int, val device: Int, val downTime: Long)
    private var visibleSince: Long? = null
    private var pending: Press? = null
    private val lastDownByDevice = mutableMapOf<Int, Long>()

    fun shown(now: Long) {
        visibleSince = now
        pending = null
        lastDownByDevice.clear()
    }

    fun hidden() {
        visibleSince = null
        pending = null
        lastDownByDevice.clear()
    }

    fun discard() { pending = null }

    fun discard(key: Int, device: Int, downTime: Long) {
        if (pending == Press(key, device, downTime)) pending = null
    }

    fun down(key: Int, device: Int, downTime: Long, repeat: Int, cancelled: Boolean): Boolean {
        val start = visibleSince ?: return false
        val press = Press(key, device, downTime)
        if (cancelled) {
            if (pending != press) return false
            pending = null
            return true
        }
        if (pending == press) return true
        if (repeat != 0 || downTime <= start || downTime <= (lastDownByDevice[device] ?: start)) return false
        lastDownByDevice[device] = downTime
        pending = press
        return true
    }

    fun up(key: Int, device: Int, downTime: Long, cancelled: Boolean): Boolean {
        val press = pending ?: return false
        if (press != Press(key, device, downTime)) return false
        pending = null
        return visibleSince != null && !cancelled
    }
}
