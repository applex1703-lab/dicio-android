package org.stypox.dicio.voice

import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** All bindings are fakes: these tests never connect to Google or use the microphone. */
class KatnissPrebindTest {
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun busyInstanceStartsOnceAfterConfirmedRelease() = onMain {
        val reservation = PrebindReservation()
        var firstUnbinds = 0
        var secondBinds = 0
        var secondUnbinds = 0
        val first = KatnissPrebind({ _, _, _ -> true }, { firstUnbinds++ }, reservation)
        val second = KatnissPrebind({ _, _, _ ->
            assertEquals(1, firstUnbinds)
            secondBinds++
            true
        }, { secondUnbinds++ }, reservation)
        first.setEnabled(true)
        second.setEnabled(true)
        second.start()
        assertEquals(0, secondBinds)
        first.close()
        assertEquals(1, secondBinds)
        first.close()
        second.setEnabled(true)
        assertEquals(1, secondBinds)
        second.close()
        assertEquals(1, secondUnbinds)
    }

    @Test fun waitingInstanceDoesNotStartAfterShutdownOrUncertainUnbind() = onMain {
        for (uncertain in listOf(false, true)) {
            val reservation = PrebindReservation()
            var binds = 0
            val first = KatnissPrebind({ _, _, _ -> true }, {
                if (uncertain) throw IllegalStateException()
            }, reservation)
            val second = KatnissPrebind({ _, _, _ -> binds++; true }, {}, reservation)
            first.setEnabled(true)
            second.setEnabled(true)
            if (!uncertain) second.close()
            first.close()
            second.start()
            assertEquals(0, binds)
            second.close()
            val third = KatnissPrebind({ _, _, _ -> binds++; true }, {}, reservation)
            third.setEnabled(true)
            assertEquals(if (uncertain) 0 else 1, binds)
            third.close()
        }
    }

    @Test fun releaseNotificationHonoursReentrantShutdown() = onMain {
        val reservation = PrebindReservation()
        var secondBinds = 0
        lateinit var second: KatnissPrebind
        val first = KatnissPrebind({ _, _, _ -> true }, { second.close() }, reservation)
        second = KatnissPrebind({ _, _, _ -> secondBinds++; true }, {}, reservation)
        first.setEnabled(true)
        second.setEnabled(true)
        first.close()
        assertEquals(0, secondBinds)
    }

    @Test fun connectedGenerationInvalidatesOnDisableDisconnectAndStaleCallback() = onMain {
        val reservation = PrebindReservation()
        val connections = mutableListOf<ServiceConnection>()
        val prebind = KatnissPrebind({ _, conn, _ -> connections.add(conn); true }, {}, reservation)
        assertNull(reservation.snapshot())
        prebind.setEnabled(true)
        assertNull(reservation.snapshot())
        val first = connections.last()
        first.onServiceConnected(null, Binder())
        val token = reservation.snapshot()
        assertTrue(reservation.isCurrent(token))
        prebind.setEnabled(false)
        assertFalse(reservation.isCurrent(token))
        prebind.setEnabled(true)
        first.onServiceConnected(null, Binder())
        assertNull(reservation.snapshot())
        val second = connections.last()
        second.onServiceConnected(null, Binder())
        val next = reservation.snapshot()
        assertTrue(reservation.isCurrent(next))
        assertFalse(reservation.isCurrent(token))
        second.onServiceDisconnected(null)
        assertNull(reservation.snapshot())
        second.onServiceConnected(null, Binder())
        val reconnected = reservation.snapshot()
        assertTrue(reservation.isCurrent(reconnected))
        assertNotSame(next, reconnected)
        assertFalse(reservation.isCurrent(next))
        second.onServiceConnected(null, Binder())
        assertSame(reconnected, reservation.snapshot())
        prebind.close()
    }

    @Test fun repeatedLossesRecoverOnlyAfterStableConnection() = onMain {
        var time = 0L
        var scheduled: Runnable? = null
        val connections = mutableListOf<ServiceConnection>()
        var unbinds = 0
        val prebind = KatnissPrebind({ _, c, _ -> connections.add(c); true }, { unbinds++ },
            PrebindReservation(), schedule = { scheduled = it }, cancel = { scheduled = null }, now = { time })
        prebind.setEnabled(true)
        connections.last().onServiceConnected(null, Binder())
        connections.last().onBindingDied(null)
        scheduled!!.run()
        connections.last().onServiceConnected(null, Binder())
        time += 60_000
        connections.last().onBindingDied(null)
        scheduled!!.run()
        assertEquals(3, connections.size)
        connections.last().onServiceConnected(null, Binder())
        time += 59_999
        connections.last().onBindingDied(null)
        assertNull(scheduled)
        assertEquals(3, unbinds)
        prebind.close()
    }

    @Test fun reentrantShutdownDuringRecoveryCleanupDoesNotScheduleRetry() = onMain {
        var scheduled: Runnable? = null
        var binds = 0
        var unbinds = 0
        lateinit var connection: ServiceConnection
        lateinit var prebind: KatnissPrebind
        prebind = KatnissPrebind({ _, c, _ -> binds++; connection = c; true }, {
            unbinds++
            connection.onBindingDied(null)
            prebind.close()
        }, PrebindReservation(), schedule = { scheduled = it }, cancel = { scheduled = null })
        prebind.setEnabled(true)
        connection.onServiceConnected(null, Binder())
        connection.onBindingDied(null)
        assertEquals(1, binds)
        assertEquals(1, unbinds)
        assertNull(scheduled)
        assertTrue(prebind.closed)
    }

    @Test fun deadBindingGetsOneDelayedRecoveryAfterRelease() = onMain {
        var scheduled: Runnable? = null
        var binds = 0
        var unbinds = 0
        val connections = mutableListOf<ServiceConnection>()
        val prebind = KatnissPrebind({ _, conn, _ -> binds++; connections.add(conn); true },
            { unbinds++ }, PrebindReservation(), schedule = { scheduled = it }, cancel = { scheduled = null })
        prebind.setEnabled(true)
        connections[0].onServiceConnected(null, Binder())
        connections[0].onBindingDied(null)
        assertEquals(1, unbinds)
        val recovery = scheduled!!
        recovery.run()
        assertEquals(2, binds)
        connections[1].onServiceConnected(null, Binder())
        connections[1].onBindingDied(null)
        assertEquals(2, unbinds)
        assertNull(scheduled)
        recovery.run()
        assertEquals(2, binds)
        prebind.close()
    }

    @Test fun deadBindingRecoveryIsCancelledOnCloseOrUncertainUnbind() = onMain {
        for (failUnbind in listOf(false, true)) {
            var scheduled: Runnable? = null
            var binds = 0
            lateinit var conn: ServiceConnection
            val prebind = KatnissPrebind({ _, c, _ -> binds++; conn = c; true },
                { if (failUnbind) throw IllegalStateException() }, PrebindReservation(),
                schedule = { scheduled = it }, cancel = { scheduled = null })
            prebind.setEnabled(true)
            conn.onServiceConnected(null, Binder())
            conn.onBindingDied(null)
            if (failUnbind) assertNull(scheduled) else {
                val stale = scheduled!!
                prebind.close()
                stale.run()
            }
            assertEquals(1, binds)
            prebind.close()
        }
    }

    @Test fun synchronousConnectionIsNotPublishedUntilBindAccepted() = onMain {
        for (accepted in listOf(false, true)) {
            val reservation = PrebindReservation()
            val prebind = KatnissPrebind({ _, conn, _ ->
                conn.onServiceConnected(null, Binder())
                assertNull(reservation.snapshot())
                accepted
            }, {}, reservation)
            prebind.setEnabled(true)
            assertEquals(accepted, reservation.snapshot() != null)
            prebind.close()
            assertNull(reservation.snapshot())
        }
    }

    @Test fun oneAttemptAndShutdownIgnoreLateCallbacks() = onMain {
        var binds = 0
        var unbinds = 0
        lateinit var conn: ServiceConnection
        val prebind = KatnissPrebind({ intent, connection, flags ->
            binds++
            conn = connection
            assertEquals("android.speech.RecognitionService", intent.action)
            assertEquals("com.google.android.katniss", intent.component?.packageName)
            assertEquals("com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService", intent.component?.className)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            true
        }, { unbinds++ }, PrebindReservation())
        prebind.start()
        prebind.start()
        conn.onServiceConnected(null, Binder())
        prebind.close()
        prebind.close()
        conn.onServiceDisconnected(null)
        prebind.start()
        assertEquals(1, binds)
        assertEquals(1, unbinds)
        assertTrue(prebind.closed)
    }

    @Test fun failedCleanupBlocksNextInstance() = onMain {
        val reservation = PrebindReservation()
        var binds = 0
        val first = KatnissPrebind({ _, _, _ -> binds++; true }, { throw IllegalStateException() }, reservation)
        first.start()
        first.close()
        val second = KatnissPrebind({ _, _, _ -> binds++; true }, {}, reservation)
        second.start()
        second.close()
        assertEquals(1, binds)
    }

    @Test fun rejectionExceptionAndConnectionFailureDoNotRetry() = onMain {
        for (mode in 0..3) {
            var binds = 0
            var unbinds = 0
            lateinit var conn: ServiceConnection
            val prebind = KatnissPrebind({ _, connection, _ ->
                binds++
                conn = connection
                when (mode) { 0 -> false; 1 -> throw SecurityException(); else -> true }
            }, { unbinds++ }, PrebindReservation())
            prebind.start()
            if (mode == 2) conn.onNullBinding(null)
            if (mode == 3) conn.onBindingDied(null)
            prebind.start()
            prebind.close()
            assertEquals(1, binds)
            assertEquals(1, unbinds)
        }
    }

    @Test fun shutdownDuringBindDefersReleaseAndLateReadyCannotRebind() = onMain {
        var unbinds = 0
        lateinit var prebind: KatnissPrebind
        prebind = KatnissPrebind({ _, _, _ ->
            prebind.close()
            assertEquals(0, unbinds)
            true
        }, { unbinds++ }, PrebindReservation())
        prebind.start()
        prebind.start()
        assertEquals(1, unbinds)
    }

    @Test fun backgroundPreparationIsLimitedToTheRelevantDeviceClass() {
        assertTrue(supportsBackgroundPreparation(30, "TCL"))
        assertTrue(supportsBackgroundPreparation(30, "tcl"))
        assertFalse(supportsBackgroundPreparation(31, "TCL"))
        assertFalse(supportsBackgroundPreparation(30, "other"))
        assertFalse(supportsBackgroundPreparation(30, null))
    }

    @Test fun timeoutAndLateCallbacksDoNotRetry() = onMain {
        var deadline: Runnable? = null
        var binds = 0
        var unbinds = 0
        lateinit var conn: ServiceConnection
        val prebind = KatnissPrebind({ _, connection, _ -> binds++; conn = connection; true },
            { unbinds++ }, PrebindReservation(), schedule = { deadline = it }, cancel = { deadline = null })
        prebind.setEnabled(true)
        val lateDeadline = deadline!!
        lateDeadline.run()
        assertEquals(1, unbinds)
        conn.onServiceConnected(null, Binder())
        lateDeadline.run()
        prebind.setEnabled(true)
        assertEquals(1, binds)
        assertEquals(1, unbinds)
        prebind.close()
    }

    @Test fun connectedCancelsDeadlineAndDisableCancelsPendingDeadline() = onMain {
        var deadline: Runnable? = null
        var unbinds = 0
        lateinit var conn: ServiceConnection
        val prebind = KatnissPrebind({ _, connection, _ -> conn = connection; true },
            { unbinds++ }, PrebindReservation(), schedule = { deadline = it }, cancel = { deadline = null })
        prebind.setEnabled(true)
        val oldDeadline = deadline!!
        conn.onServiceConnected(null, Binder())
        assertNull(deadline)
        // A timer already dispatched before cancellation must be harmless.
        oldDeadline.run()
        assertEquals(0, unbinds)
        prebind.setEnabled(false)
        oldDeadline.run()
        assertEquals(1, unbinds)
        prebind.setEnabled(true)
        assertNotNull(deadline)
        prebind.setEnabled(false)
        assertNull(deadline)
        assertEquals(2, unbinds)
    }

    @Test fun realHandlerTimerCancelsTheScheduledRunnable() {
        val cancelled = CountDownLatch(1)
        val fired = CountDownLatch(1)
        onMain {
            val timer = PrebindTimer(delayMillis = 100)
            val task = Runnable { cancelled.countDown() }
            timer.schedule(task)
            timer.cancel(task)
            timer.schedule(Runnable { fired.countDown() })
        }
        assertTrue("uncancelled timer did not fire", fired.await(3, TimeUnit.SECONDS))
        assertFalse("cancelled timer fired", cancelled.await(300, TimeUnit.MILLISECONDS))
    }

    @Test fun reentrantEnableDisableEnableWaitsForUnbind() = onMain {
        var binds = 0
        var unbinds = 0
        lateinit var prebind: KatnissPrebind
        lateinit var first: ServiceConnection
        prebind = KatnissPrebind({ _, conn, _ ->
            binds++
            if (binds == 1) {
                first = conn
                prebind.setEnabled(false)
                prebind.setEnabled(true)
                assertEquals(1, binds)
                assertEquals(0, unbinds)
            }
            true
        }, { unbinds++ }, PrebindReservation())
        prebind.setEnabled(true)
        assertEquals(2, binds)
        assertEquals(1, unbinds)
        first.onServiceConnected(null, Binder())
        first.onNullBinding(null)
        prebind.setEnabled(true)
        assertEquals(2, binds)
        prebind.close()
        assertEquals(2, unbinds)
    }

    @Test fun reentrantEnableCannotRetryAfterUncertainUnbindOrShutdown() = onMain {
        for (shutdown in listOf(false, true)) {
            var binds = 0
            lateinit var prebind: KatnissPrebind
            prebind = KatnissPrebind({ _, _, _ ->
                binds++
                prebind.setEnabled(false)
                prebind.setEnabled(true)
                if (shutdown) prebind.close()
                true
            }, { if (!shutdown) throw IllegalStateException() }, PrebindReservation())
            prebind.setEnabled(true)
            assertEquals(1, binds)
            prebind.setEnabled(true)
            assertEquals(1, binds)
            prebind.close()
        }
    }
}
