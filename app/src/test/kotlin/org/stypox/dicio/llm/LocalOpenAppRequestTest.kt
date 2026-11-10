package org.stypox.dicio.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LocalOpenAppRequestTest : StringSpec({
    val apps = listOf(
        LocalOpenAppRequest.Entry("app_0", "YouTube"),
        LocalOpenAppRequest.Entry("app_1", "Кинопоиск"),
        LocalOpenAppRequest.Entry("app_2", "Smart TV"),
    )

    "candidate filter is catalog independent and shares parser restrictions" {
        listOf("открой YouTube", "включи YouTube", "включить YouTube", "открыть Smart TV",
            "запустить неизвестное", "open YouTube").forEach {
            LocalOpenAppRequest.isCandidate(it) shouldBe true
        }
        LocalOpenAppRequest.parse("запустить неизвестное", apps) shouldBe null
        listOf("", "YouTube", "включи", "включи видео X", "найди фильм в YouTube",
            "включи YouTube и открой настройки", "открой YouTube; настройки",
            "открой YouTube\nоткрой настройки", "открой  YouTube", "открой YouTube!").forEach {
            LocalOpenAppRequest.isCandidate(it) shouldBe false
            LocalOpenAppRequest.parse(it, apps) shouldBe null
        }
    }
    "only direct complete commands match exact current labels" {
        LocalOpenAppRequest.parse("Открой YouTube", apps) shouldBe "app_0"
        listOf("включи", "включить", "открыть", "запустить").forEach { verb ->
            LocalOpenAppRequest.parse("$verb YouTube", apps) shouldBe "app_0"
            LocalOpenAppRequest.parse("$verb YouTube", apps + LocalOpenAppRequest.Entry("other", "youtube")) shouldBe null
            LocalOpenAppRequest.parse("$verb You", apps) shouldBe null
            LocalOpenAppRequest.parse("$verb YouTube и открой настройки", apps) shouldBe null
            LocalOpenAppRequest.parse("$verb видео X", listOf(LocalOpenAppRequest.Entry("id", "видео X"))) shouldBe null
        }
        LocalOpenAppRequest.parse("  запусти КИНОПОИСК  ", apps) shouldBe "app_1"
        LocalOpenAppRequest.parse("open smart tv", apps) shouldBe "app_2"
        LocalOpenAppRequest.parse("открой YouTube", emptyList()) shouldBe null
        LocalOpenAppRequest.parse("открой YouTube", listOf(LocalOpenAppRequest.Entry("new", "YouTube"))) shouldBe "new"
    }
    "no fuzzy, substring, partial, or implicit matches" {
        listOf("YouTube", "открой You", "открой YouTube Music", "открой Youtub",
            "пожалуйста открой YouTube", "открой YouTube пожалуйста", "открой  YouTube",
            "открой YouTube!", "открой приложение YouTube").forEach {
            LocalOpenAppRequest.parse(it, apps) shouldBe null
        }
    }
    "ambiguous or invalid catalog entries fail closed" {
        LocalOpenAppRequest.parse("открой youtube", apps + LocalOpenAppRequest.Entry("other", "YOUTUBE")) shouldBe null
        LocalOpenAppRequest.parse("открой youtube", listOf(LocalOpenAppRequest.Entry("", "YouTube"))) shouldBe null
        LocalOpenAppRequest.parse("открой youtube", listOf(apps[0], apps[0])) shouldBe null
        LocalOpenAppRequest.parse("открой youtube", listOf(apps[0], LocalOpenAppRequest.Entry("app_0", "Other"))) shouldBe null
    }
    "compound, dangerous and content search requests are not intercepted even if a label matches" {
        val unsafe = listOf("YouTube и открой настройки", "YouTube затем выключи", "фильм",
            "видео коты", "поиск видео", "YouTube; настройки", "YouTube\nоткрой настройки",
            "movie", "search video", "YouTube and launch VPN")
        unsafe.forEach { name ->
            LocalOpenAppRequest.parse("открой $name", listOf(LocalOpenAppRequest.Entry("id", name))) shouldBe null
        }
        LocalOpenAppRequest.parse("найди фильм в YouTube", apps) shouldBe null
        LocalOpenAppRequest.parse("открой YouTube и найди фильм", apps) shouldBe null
    }
})
