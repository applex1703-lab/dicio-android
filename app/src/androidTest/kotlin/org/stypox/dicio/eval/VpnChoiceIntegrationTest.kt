package org.stypox.dicio.eval

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.stypox.dicio.llm.*

/** Exercises the same dispatcher, pending-choice and presentation components used by the evaluator. */
@RunWith(AndroidJUnit4::class)
class VpnChoiceIntegrationTest {
    private class Backend : VpnBackend {
        val happ = VpnCandidate("com.happproxy", "Happ", true, "signer-a")
        val other = VpnCandidate("other", "Other", false, "signer-b")
        var entries = listOf(happ, other)
        val calls = mutableListOf<String>()
        override fun candidates() = entries
        override fun isVpnPackage(packageName: String) = entries.any { it.packageName == packageName }
        override fun connectHapp(): Boolean { calls.add("happ"); return true }
        override fun open(packageName: String): Boolean { calls.add(packageName); return true }
    }

    @Test fun vpnCardsAreTextualOneShotAndCancellationInvalidates() = runBlocking {
        val backend = Backend()
        val dispatcher = VpnDispatcher(backend, Dispatchers.Unconfined)
        val pending = PendingChoice<ChoiceTarget>()
        val presentation = CurrentVoicePresentation()
        val choice = dispatcher.begin("включи VPN") as VpnDispatch.Choice
        val id = pending.create("", choice.options.associate { it.id to ChoiceTarget.Vpn(it.id) })
        presentation.choice(id, choice.options.map { ChoiceOption(it.id, it.label) }, choice.prompt, "Выберите VPN-приложение")
        assertEquals(listOf("Happ", "Other"), presentation.state.value.choice!!.options.map { it.label })
        val selected = pending.consume(id, choice.options.first().id)!!.second as ChoiceTarget.Vpn
        assertTrue(dispatcher.choose(selected.optionId).actionSent)
        assertNull(pending.consume(id, selected.optionId))
        assertEquals(listOf("happ"), backend.calls)
        val next = dispatcher.begin("подключи VPN") as VpnDispatch.Choice
        val nextId = pending.create("", next.options.associate { it.id to ChoiceTarget.Vpn(it.id) })
        pending.clear()
        dispatcher.clear()
        assertNull(pending.consume(nextId, next.options.first().id))
        assertEquals(listOf("happ"), backend.calls)
    }

    @Test fun searchAndVpnChoicesCannotCrossOrReplay() = runBlocking {
        val backend = Backend()
        val dispatcher = VpnDispatcher(backend, Dispatchers.Unconfined)
        val pending = PendingChoice<ChoiceTarget>()
        val search = SearchTarget("search-id", "YouTube", SearchTargetKind.YOUTUBE, ComponentName("youtube", "Activity"))
        val searchId = pending.create("cats", mapOf(search.id to ChoiceTarget.Search(search)))
        val vpn = dispatcher.begin("подключи vpn") as VpnDispatch.Choice
        val vpnId = pending.create("", vpn.options.associate { it.id to ChoiceTarget.Vpn(it.id) })
        assertNull(pending.consume(searchId, search.id))
        assertNull(pending.consume(vpnId, search.id))
        pending.clear()
        dispatcher.clear()
        val newSearchId = pending.create("cats", mapOf(search.id to ChoiceTarget.Search(search)))
        assertNull(pending.consume(vpnId, vpn.options.first().id))
        val selected = pending.consume(newSearchId, search.id)!!
        assertEquals("cats", selected.first)
        assertEquals(search, (selected.second as ChoiceTarget.Search).target)
        assertNull(pending.consume(newSearchId, search.id))
        assertTrue(backend.calls.isEmpty())
    }
}
