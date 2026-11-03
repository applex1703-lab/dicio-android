package org.stypox.dicio.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.*
import org.junit.Test
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.UserSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SttInputDeviceWrapperTest {
    private class Store : DataStore<UserSettings> {
        val settings = MutableStateFlow(UserSettings.getDefaultInstance())
        override val data: Flow<UserSettings> = settings
        override suspend fun updateData(transform: suspend (UserSettings) -> UserSettings): UserSettings {
            return transform(settings.value).also { settings.value = it }
        }
    }
    private class Device : SttInputDevice {
        override val uiState = MutableStateFlow<SttState>(SttState.Loaded)
        var callback: ((InputEvent) -> Unit)? = null
        var destroys = 0
        var fail = false
        var failCall = false
        var onLoad: (((InputEvent) -> Unit)?) -> Unit = { }
        var gate: CompletableDeferred<Unit>? = null
        override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
            if (failCall) throw IllegalStateException("load")
            callback = thenStartListeningEventListener
            onLoad(thenStartListeningEventListener)
            return true
        }
        override fun onClick(eventListener: (InputEvent) -> Unit) {
            if (failCall) throw IllegalStateException("click")
            callback = eventListener
            onLoad(eventListener)
        }
        override fun stopListening() = Unit
        override suspend fun destroy() {
            destroys++
            gate?.await()
            if (fail) throw IllegalStateException("injected cleanup failure")
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun wrapper(store: Store, build: (InputDevice) -> SttInputDevice?): SttInputDeviceWrapperImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SttInputDeviceWrapperImpl(context, store, LocaleManager.newForPreviews(context), build)
    }

    @Test fun failureBlocksAllEntryPointsAndLateCallbackWithoutRetry() {
        val first = Device().apply { fail = true }
        var builds = 0
        val wrapper = wrapper(Store()) { builds++; first }
        val events = mutableListOf<InputEvent>()
        main {
            assertTrue(wrapper.tryLoad { events.add(it) })
            val callback = first.callback!!
            assertFalse(wrapper.reinitializeToReleaseResources())
            assertFalse(wrapper.isCleanupSafe())
            callback(InputEvent.Final(listOf("late" to 1f)))
            assertFalse(wrapper.tryLoad(null))
            assertFalse(wrapper.tryLoad { events.add(it) })
            wrapper.onClick { events.add(it) }
            assertTrue(events.single() is InputEvent.Error)
            assertFalse(wrapper.reinitializeToReleaseResources())
            assertEquals(1, first.destroys)
            assertEquals(1, builds)
        }
    }

    @Test fun pendingCleanupDoesNotPublishReplacement() {
        val gate = CompletableDeferred<Unit>()
        val first = Device().apply { this.gate = gate }
        var builds = 0
        val wrapper = wrapper(Store()) { builds++; if (builds == 1) first else Device() }
        main {
            assertFalse(wrapper.reinitializeToReleaseResources())
            assertFalse(wrapper.isCleanupSafe())
            assertFalse(wrapper.tryLoad(null))
            assertEquals(1, builds)
            gate.complete(Unit)
        }
        instrumentation.waitForIdleSync()
        main {
            assertTrue(wrapper.tryLoad(null))
            assertTrue(wrapper.isCleanupSafe())
            assertEquals(2, builds)
            assertEquals(1, first.destroys)
        }
    }

    @Test fun settingsChangeFailureInvalidatesCallbackAndDoesNotReplace() {
        val store = Store()
        val first = Device().apply { fail = true }
        var builds = 0
        val wrapper = wrapper(store) { builds++; first }
        val interrupted = CountDownLatch(1)
        val events = mutableListOf<InputEvent>()
        main {
            assertTrue(wrapper.tryLoad { events.add(it); if (it is InputEvent.Error) interrupted.countDown() })
            store.settings.value = store.settings.value.toBuilder().setInputDevice(InputDevice.INPUT_DEVICE_NOTHING).build()
        }
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        main {
            first.callback!!(InputEvent.Final(listOf("late" to 1f)))
            assertEquals(1, events.size)
            assertTrue(events.single() is InputEvent.Error)
            assertFalse(wrapper.tryLoad(null))
            assertEquals(1, first.destroys)
            assertEquals(1, builds)
        }
    }

    @Test fun busyRequestsDoNotReplaceFirstListener() {
        val first = Device()
        val wrapper = wrapper(Store()) { first }
        val received = mutableListOf<InputEvent>()
        val rejected = mutableListOf<InputEvent>()
        main {
            assertTrue(wrapper.tryLoad { received.add(it) })
            val original = first.callback!!
            assertFalse(wrapper.tryLoad { rejected.add(it) })
            wrapper.onClick { rejected.add(it) }
            original(InputEvent.Final(listOf("first" to 1f)))
            assertEquals(1, received.size)
            assertTrue(received.single() is InputEvent.Final)
            assertEquals(2, rejected.size)
            assertTrue(rejected.all { it is InputEvent.Error })
        }
    }

    @Test fun queuedOffMainCallbackCannotPassMainThreadInvalidation() {
        val first = Device()
        val wrapper = wrapper(Store()) { first }
        val events = mutableListOf<InputEvent>()
        main {
            assertTrue(wrapper.tryLoad { events.add(it) })
            val old = first.callback!!
            val thread = Thread { old(InputEvent.Final(listOf("late" to 1f))) }
            thread.start()
            thread.join()
            assertTrue(wrapper.reinitializeToReleaseResources())
        }
        instrumentation.waitForIdleSync()
        main { assertTrue(events.isEmpty()) }
    }

    @Test fun constructionFailurePoisonsWrapperWithoutRetry() {
        val first = Device()
        var builds = 0
        val wrapper = wrapper(Store()) {
            builds++
            if (builds == 1) first else throw IllegalStateException("construction")
        }
        main {
            assertFalse(wrapper.reinitializeToReleaseResources())
            assertFalse(wrapper.tryLoad(null))
            assertFalse(wrapper.reinitializeToReleaseResources())
            assertEquals(1, first.destroys)
            assertEquals(2, builds)
        }
    }

    @Test fun deviceAndClientExceptionsReleaseListenerAndDevice() {
        for (click in listOf(false, true)) {
            val first = Device().apply { failCall = true }
            var builds = 0
            val wrapper = wrapper(Store()) { builds++; if (builds == 1) first else Device() }
            val errors = mutableListOf<InputEvent>()
            main {
                if (click) wrapper.onClick { errors.add(it) } else assertFalse(wrapper.tryLoad { errors.add(it) })
                assertEquals(1, first.destroys)
                assertTrue(errors.single() is InputEvent.Error)
                assertTrue(wrapper.tryLoad { })
            }
        }
        val first = Device()
        var builds = 0
        val wrapper = wrapper(Store()) { builds++; if (builds == 1) first else Device() }
        main {
            assertTrue(wrapper.tryLoad { throw IllegalStateException("client") })
            first.callback!!(InputEvent.Started)
            assertEquals(1, first.destroys)
            assertTrue(wrapper.tryLoad { })
        }
    }

    @Test fun interruptedCallbackExceptionDoesNotStopSettingsCollector() {
        val store = Store()
        val devices = mutableListOf<Device>()
        val wrapper = wrapper(store) { Device().also { devices.add(it) } }
        main {
            assertTrue(wrapper.tryLoad { throw IllegalStateException("client") })
            store.settings.value = store.settings.value.toBuilder().setInputDevice(InputDevice.INPUT_DEVICE_NOTHING).build()
        }
        instrumentation.waitForIdleSync()
        val secondInterrupted = CountDownLatch(1)
        main {
            assertTrue(wrapper.tryLoad { if (it is InputEvent.Error) secondInterrupted.countDown() })
            store.settings.value = store.settings.value.toBuilder().setInputDevice(InputDevice.INPUT_DEVICE_VOSK).build()
        }
        assertTrue(secondInterrupted.await(5, TimeUnit.SECONDS))
        main { assertEquals(3, devices.size) }
    }

    @Test fun throwingOldTerminalCallbackDoesNotDestroyReentrantOwner() {
        for (click in listOf(false, true)) {
            val device = Device()
            val wrapper = wrapper(Store()) { device }
            val newEvents = mutableListOf<InputEvent>()
            main {
                val old: (InputEvent) -> Unit = {
                    assertTrue(wrapper.tryLoad { event -> newEvents.add(event) })
                    throw IllegalStateException("old client")
                }
                if (click) wrapper.onClick(old) else assertTrue(wrapper.tryLoad(old))
                val oldCallback = device.callback!!
                oldCallback(InputEvent.Final(listOf("old" to 1f)))
                assertEquals(0, device.destroys)
                device.callback!!(InputEvent.Final(listOf("new" to 1f)))
                assertEquals(listOf(InputEvent.Final(listOf("new" to 1f))), newEvents)
            }
        }
    }

    @Test fun oldDeviceCallThrowAfterTerminalInterruptsReentrantOwnerAndFailsClosed() {
        for (click in listOf(false, true)) {
            val device = Device().apply { fail = true }
            var loads = 0
            device.onLoad = { callback ->
                if (++loads == 1) {
                    callback!!(InputEvent.Final(listOf("old" to 1f)))
                    throw IllegalStateException("old device call")
                }
            }
            var builds = 0
            val wrapper = wrapper(Store()) { builds++; device }
            val newEvents = mutableListOf<InputEvent>()
            main {
                val old: (InputEvent) -> Unit = {
                    assertTrue(wrapper.tryLoad { event -> newEvents.add(event) })
                }
                if (click) wrapper.onClick(old) else assertFalse(wrapper.tryLoad(old))
                assertEquals(1, device.destroys)
                assertEquals(1, newEvents.size)
                assertTrue(newEvents.single() is InputEvent.Error)
                assertFalse(wrapper.tryLoad(null))
                assertEquals(1, builds)
                device.callback!!(InputEvent.Final(listOf("late" to 1f)))
                assertEquals(1, newEvents.size)
            }
        }
    }

    @Test fun oldCallbackExceptionDoesNotReleaseRepublishedSameInstance() {
        val device = Device()
        val wrapper = wrapper(Store()) { device }
        val events = mutableListOf<InputEvent>()
        main {
            assertTrue(wrapper.tryLoad {
                assertTrue(wrapper.reinitializeToReleaseResources())
                assertTrue(wrapper.tryLoad { event -> events.add(event) })
                throw IllegalStateException("old callback")
            })
            val old = device.callback!!
            old(InputEvent.Final(listOf("old" to 1f)))
            assertEquals(1, device.destroys)
            old(InputEvent.Final(listOf("stale" to 1f)))
            device.callback!!(InputEvent.Final(listOf("new" to 1f)))
            assertEquals(listOf(InputEvent.Final(listOf("new" to 1f))), events)
        }
    }
}
