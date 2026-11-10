package org.stypox.dicio.llm

import java.util.Locale

/** An untargeted request: selecting a live session and executing it are separate steps. */
internal data class LocalMediaIntent(val operation: MediaOperation, val seconds: Long = 0L)

internal object LocalMediaRequest {
    private val fixed = mapOf(
        "пауза" to MediaOperation.PAUSE,
        "поставь на паузу" to MediaOperation.PAUSE,
        "поставь на паузу видео" to MediaOperation.PAUSE,
        "приостанови" to MediaOperation.PAUSE,
        "продолжай" to MediaOperation.RESUME,
        "продолжи" to MediaOperation.RESUME,
        "продолжить" to MediaOperation.RESUME,
        "продолжи воспроизведение" to MediaOperation.RESUME,
        "продолжи видео" to MediaOperation.RESUME,
        "стоп" to MediaOperation.STOP,
        "останови воспроизведение" to MediaOperation.STOP,
        "останови видео" to MediaOperation.STOP,
    )
    private val seek = Regex(
        "(?:перемотай|перемотать) (вперед|вперёд|назад)(?: на ([0-9]{1,6}) " +
            "(секунду|секунды|секунд|минуту|минуты|минут|час|часа|часов))?"
    )
    private val seekAmountFirst = Regex(
        "(?:перемотай|перемотать) на ([0-9]{1,6}) " +
            "(секунду|секунды|секунд|минуту|минуты|минут|час|часа|часов) (вперед|вперёд|назад)"
    )

    fun parse(text: String): LocalMediaIntent? {
        val normalized = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        fixed[normalized]?.let { return LocalMediaIntent(it) }
        val match = seek.matchEntire(normalized)
        val amountFirst = if (match == null) seekAmountFirst.matchEntire(normalized) else null
        if (match == null && amountFirst == null) return null
        val direction = match?.groupValues?.get(1) ?: amountFirst!!.groupValues[3]
        val number = match?.groupValues?.get(2) ?: amountFirst!!.groupValues[1]
        val unit = match?.groupValues?.get(3) ?: amountFirst!!.groupValues[2]
        val magnitude = if (number.isEmpty()) 10L else {
            val value = number.toLongOrNull() ?: return null
            val multiplier = when (unit) {
                "секунду", "секунды", "секунд" -> 1L
                "минуту", "минуты", "минут" -> 60L
                "час", "часа", "часов" -> 3600L
                else -> return null
            }
            if (value == 0L || value > 86_400L / multiplier) return null
            value * multiplier
        }
        return LocalMediaIntent(MediaOperation.SEEK_RELATIVE,
            if (direction == "назад") -magnitude else magnitude)
    }
}
