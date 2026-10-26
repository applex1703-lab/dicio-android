package org.stypox.dicio.llm

/** Pure policy shared by the protocol and executor; seconds, never provider-supplied milliseconds. */
internal enum class MediaOperation { PAUSE, RESUME, STOP, NEXT, PREVIOUS, SEEK_RELATIVE, SEEK_ABSOLUTE }

internal data class MediaTarget(
    val id: String,
    val label: String,
    val state: String,
    val operations: Set<MediaOperation>,
)

internal data class MediaCommand(val id: String, val operation: MediaOperation, val seconds: Long) {
    fun validate(targets: List<MediaTarget>) {
        val target = targets.singleOrNull { it.id == id }
        check(target != null && operation in target.operations) { "Проигрыватель или команда недоступны." }
        when (operation) {
            MediaOperation.SEEK_RELATIVE -> check(seconds in -86_400L..86_400L && seconds != 0L) {
                "Недопустимая величина перемотки."
            }
            MediaOperation.SEEK_ABSOLUTE -> check(seconds in 0L..86_400L) { "Недопустимая позиция." }
            else -> check(seconds == 0L) { "Лишний параметр медиакоманды." }
        }
    }
}

internal object MediaSeek {
    fun destination(operation: MediaOperation, seconds: Long, positionMs: Long, durationMs: Long): Long {
        check(durationMs > 0 && positionMs >= 0) { "Длительность или позиция видео недоступна." }
        check(seconds in -86_400L..86_400L) { "Недопустимая позиция." }
        val requested = when (operation) {
            MediaOperation.SEEK_RELATIVE -> {
                check(seconds != 0L) { "Укажите величину перемотки." }
                // Clamp before adding so even a broken player position cannot overflow.
                val base = positionMs.coerceAtMost(durationMs)
                val delta = seconds * 1000L
                if (delta > 0 && base > Long.MAX_VALUE - delta) durationMs else base + delta
            }
            MediaOperation.SEEK_ABSOLUTE -> {
                check(seconds >= 0L) { "Позиция не может быть отрицательной." }
                check(seconds * 1000L <= durationMs) { "Позиция за пределами видео." }
                seconds * 1000L
            }
            else -> error("Это не команда перемотки.")
        }
        return requested.coerceIn(0L, durationMs)
    }
}
