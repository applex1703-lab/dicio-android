package org.stypox.dicio.eval

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule

@RunWith(AndroidJUnit4::class)
class SkillHandlerLazyTest {
    @Test fun datastoreFailureIsReportedAndNextRequestRetries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val underlying = UserSettingsModule.newDataStoreForPreviews()
        val attempts = AtomicInteger()
        val failure = IOException("settings failed")
        val store = object : DataStore<UserSettings> {
            override val data: Flow<UserSettings> = flow {
                if (attempts.incrementAndGet() == 1) throw failure
                emitAll(underlying.data)
            }
            override suspend fun updateData(transform: suspend (UserSettings) -> UserSettings) =
                underlying.updateData(transform)
        }
        val handler = SkillHandler(store, LocaleManager.newForPreviews(context),
            SkillContextImpl.newForPreviews(context))
        val caught = try {
            withTimeout(10_000) { handler.awaitSkillRanker() }
            null
        } catch (error: IOException) { error }
        assertSame(failure, caught)
        assertNotNull(withTimeout(10_000) { handler.awaitSkillRanker() })
        assertEquals(2, attempts.get())
    }

    @Test fun cancellingOneWaiterDoesNotCancelMatcherOrOtherWaiters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val underlying = UserSettingsModule.newDataStoreForPreviews()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = object : DataStore<UserSettings> {
            override val data: Flow<UserSettings> = flow {
                started.complete(Unit)
                gate.await()
                emitAll(underlying.data)
            }
            override suspend fun updateData(transform: suspend (UserSettings) -> UserSettings) =
                underlying.updateData(transform)
        }
        val handler = SkillHandler(store, LocaleManager.newForPreviews(context),
            SkillContextImpl.newForPreviews(context))
        val cancelled = async { handler.awaitSkillRanker() }
        withTimeout(10_000) { started.await() }
        cancelled.cancelAndJoin()
        val other = async { handler.awaitSkillRanker() }
        gate.complete(Unit)
        assertNotNull(withTimeout(10_000) { other.await() })
    }

    @Test fun listingSkillsDoesNotCreateOrSubscribeMatcherAndLegacySettingsStillUpdate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val underlying = UserSettingsModule.newDataStoreForPreviews()
        val reads = AtomicInteger()
        val observed = object : DataStore<UserSettings> {
            override val data: Flow<UserSettings> get() {
                reads.incrementAndGet()
                return underlying.data
            }
            override suspend fun updateData(transform: suspend (UserSettings) -> UserSettings) =
                underlying.updateData(transform)
        }
        val handler = SkillHandler(observed, LocaleManager.newForPreviews(context),
            SkillContextImpl.newForPreviews(context))

        assertTrue(handler.allSkillInfoList.any { it.id == "calculator" })
        assertFalse(handler.isMatcherInitialized)
        assertEquals(0, reads.get())

        val ranker = withTimeout(10_000) { handler.awaitSkillRanker() }
        assertNotNull(ranker)
        assertTrue(handler.isMatcherInitialized)
        assertTrue(reads.get() > 0)
        assertSame(handler.skillRanker, handler.skillRanker)
        assertTrue(handler.enabledSkillsInfo.value!!.any { it.id == "calculator" })

        underlying.updateData { it.toBuilder().putEnabledSkills("calculator", false).build() }
        withTimeout(10_000) {
            handler.enabledSkillsInfo.first { skills -> skills != null && skills.none { it.id == "calculator" } }
        }
        underlying.updateData { it.toBuilder().putEnabledSkills("calculator", true).build() }
        withTimeout(10_000) {
            handler.enabledSkillsInfo.first { skills -> skills?.any { it.id == "calculator" } == true }
        }
    }
}
