package org.stypox.dicio.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TvVoiceRequestActionTest : StringSpec({
    "hidden requests do nothing and a visible action attempts start once" {
        var visible = false
        var starts = 0
        var subscriptions = 0
        val action = TvVoiceRequestAction(
            { visible }, { _, _ -> starts++; true }, {}, { subscriptions++ }, {},
        )
        action.request()
        starts shouldBe 0
        visible = true
        action.request()
        starts shouldBe 1
        subscriptions shouldBe 1
    }

    "existing input retains its subscription and result on a no-op start" {
        var subscribed = true
        var result = "existing result"
        var subscriptions = 0
        val action = TvVoiceRequestAction(
            { true }, { _, _ -> true }, { subscribed = false; result = "" },
            { if (!subscribed) { subscribed = true; subscriptions++ } }, {},
        )
        action.request()
        subscribed shouldBe true
        result shouldBe "existing result"
        subscriptions shouldBe 0
    }

    "revocation before restart replaces the subscription once" {
        var subscribed = true
        var subscriptions = 0
        val action = TvVoiceRequestAction(
            { true }, { _, revoked -> revoked(); true }, { subscribed = false },
            { if (!subscribed) { subscribed = true; subscriptions++ } }, {},
        )
        action.request()
        subscribed shouldBe true
        subscriptions shouldBe 1
    }

    "failure and a hide during start do not create subscriptions" {
        var visible = true
        var subscriptions = 0
        var failures = 0
        val failure = TvVoiceRequestAction(
            { visible }, { _, _ -> false }, {}, { subscriptions++ }, { failures++ },
        )
        failure.request()
        failures shouldBe 1
        val hidden = TvVoiceRequestAction(
            { visible }, { _, _ -> visible = false; true }, {}, { subscriptions++ }, { failures++ },
        )
        hidden.request()
        subscriptions shouldBe 0
        failures shouldBe 1
    }

    "initial and retry select separate coordinator paths" {
        val attempts = mutableListOf<Boolean>()
        val action = TvVoiceRequestAction(
            { true }, { initial, _ -> attempts.add(initial); true }, {}, {}, {},
        )
        action.request()
        action.request(initial = true)
        attempts shouldBe listOf(false, true)
    }

    "permission denial never starts input and later explicit retry can start" {
        var permitted = false
        var starts = 0
        var denied = 0
        val action = TvVoiceRequestAction(
            { true }, { _, _ -> starts++; true }, {}, {}, {},
            { permitted }, { denied++ },
        )
        action.request(initial = true)
        denied shouldBe 1
        starts shouldBe 0
        permitted = true
        starts shouldBe 0
        action.request()
        starts shouldBe 1
    }
})
