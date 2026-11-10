package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalMediaRequestTest : StringSpec({
    "fixed requests map to zero-argument media operations" {
        for ((text, operation) in mapOf(
            " ПАУЗА " to MediaOperation.PAUSE,
            "поставь   на паузу" to MediaOperation.PAUSE,
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
        )) LocalMediaRequest.parse(text) shouldBe LocalMediaIntent(operation, 0)
    }

    "relative seeks use signed seconds and default to ten" {
        for ((text, seconds) in mapOf(
            "перемотай вперед" to 10L,
            "перемотать вперёд" to 10L,
            "перемотай назад" to -10L,
            "перемотай вперед на 30 секунд" to 30L,
            "перемотай назад на 2 минуты" to -120L,
            "перемотай на 10 секунд вперёд" to 10L,
            "перемотай на 10 секунд назад" to -10L,
            "перемотать на 2 минуты вперед" to 120L,
            "перемотай на 24 часа назад" to -86400L,
            "перемотать вперед на 1 час" to 3600L,
            "перемотай назад на 24 часа" to -86400L,
            "перемотай вперед на 86400 секунд" to 86400L,
        )) {
            val intent = LocalMediaRequest.parse(text)
            intent shouldBe LocalMediaIntent(MediaOperation.SEEK_RELATIVE, seconds)
            MediaCommand("media_0", intent!!.operation, intent.seconds).validate(
                listOf(MediaTarget("media_0", "player", "paused", setOf(MediaOperation.SEEK_RELATIVE)))
            )
        }
    }

    "only complete unambiguous templates match" {
        for (text in listOf(
            "", "пауза видео про кошек", "найди пауза", "найди продолжай", "стоп машина",
            "пауза и продолжай", "пауза потом стоп", "продолжай фильм", "останови",
            "поставь на паузу видео про кошек", "продолжи видео про котов", "останови видео про котов",
            "найди продолжи видео", "продолжи видео и останови видео",
            "назад", "перемотай", "перемотай назад и поставь на паузу",
            "перемотай назад на 10", "перемотай назад на 10 кадров",
            "перемотай назад на 0 секунд", "перемотай вперед на 86401 секунду",
            "перемотай вперед на 25 часов", "перемотай вперед на 999999999999999999999 секунд",
            "перемотай вперед на -10 секунд", "перемотай вперед на 1.5 минуты",
            "перемотай вперед на 1 минуту 30 секунд", "перемотай на 10 секунд",
            "перемотай вперед на 10 секунд видео про котов", "перемотай вперед!",
            "перемотай на 0 секунд назад", "перемотай на 25 часов вперед",
            "перемотай на 10 кадров вперед", "перемотай на 10 секунд вперед видео про котов",
            "найди перемотай на 10 секунд вперед", "перемотай на 10 секунд назад и продолжи видео",
        )) LocalMediaRequest.parse(text) shouldBe null
    }
})
