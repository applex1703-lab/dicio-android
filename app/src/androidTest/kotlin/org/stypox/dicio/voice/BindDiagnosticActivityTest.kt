package org.stypox.dicio.voice

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BindDiagnosticActivityTest {
    private fun button(activity: BindDiagnosticActivity): Button =
        ((activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ViewGroup)
            .getChildAt(1) as Button

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { text(view.getChildAt(it)) }
        else -> ""
    }

    private fun assertHonest(activity: BindDiagnosticActivity) {
        val visible = text(activity.findViewById(android.R.id.content))
        assertFalse(visible.contains("без записи", ignoreCase = true))
        assertFalse(visible.contains("запись не", ignoreCase = true))
        assertFalse(visible.contains("не производилась", ignoreCase = true))
        assertFalse(visible.contains("не более 10 секунд", ignoreCase = true))
    }

    @Test fun buttonCanRetryAfterOtherLiveReservationIsReleased() {
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val registry = BindProbeReservation()
                activity.reservation = registry
                val other = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                assertTrue(registry.reserve(activity, other))
                var binds = 0
                activity.bindForProbe = { _, _, _ -> binds++; true }
                activity.unbindForProbe = { }
                assertTrue(button(activity).performClick())
                assertEquals(0, binds)
                assertTrue(button(activity).isEnabled)
                registry.release(other)
                assertTrue(button(activity).performClick())
                assertEquals(1, binds)
            }
        }
    }

    @Test fun actualProcessSingletonIsReleasedByRecreatedActivityLifecycle() {
        var binds = 0
        var unbinds = 0
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Keep the Activity's real process-wide reservation.
                activity.bindForProbe = { _, _, _ -> binds++; true }
                activity.unbindForProbe = { unbinds++ }
                assertTrue(button(activity).performClick())
                assertEquals(1, binds)
            }
            scenario.recreate()
            assertEquals(1, unbinds)
            scenario.onActivity { activity ->
                activity.bindForProbe = { _, _, _ -> binds++; true }
                activity.unbindForProbe = { unbinds++ }
                assertTrue(button(activity).performClick())
                assertEquals(2, binds)
            }
        }
        assertEquals(2, unbinds)
    }

    @Test fun realPlatformBindAndLifecycleUnbindUseInertTestApkService() {
        val connected = CountDownLatch(1)
        var unbinds = 0
        lateinit var actualConnection: ServiceConnection
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.reservation = BindProbeReservation()
                activity.bindForProbe = { original, conn, flags ->
                    assertEquals("com.google.android.katniss", original.component?.packageName)
                    actualConnection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            conn.onServiceConnected(name, binder)
                            connected.countDown()
                        }
                        override fun onServiceDisconnected(name: ComponentName?) = conn.onServiceDisconnected(name)
                        override fun onBindingDied(name: ComponentName?) = conn.onBindingDied(name)
                        override fun onNullBinding(name: ComponentName?) = conn.onNullBinding(name)
                    }
                    val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
                    val intent = Intent().setComponent(ComponentName(testPackage, ProbeTestService::class.java.name))
                    activity.bindService(intent, actualConnection, flags)
                }
                activity.unbindForProbe = {
                    activity.unbindService(actualConnection)
                    unbinds++
                }
                assertTrue(button(activity).performClick())
            }
            assertTrue("test service did not connect", connected.await(8, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(1, unbinds)
        }
        assertEquals(1, unbinds)
    }

    @Test fun explicitStartBindsOnceAndStopUnbindsEvenWithLateCallback() {
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            lateinit var conn: ServiceConnection
            var binds = 0
            var unbinds = 0
            scenario.onActivity { activity ->
                activity.reservation = BindProbeReservation()
                assertHonest(activity)
                val explanation = text(activity.findViewById(android.R.id.content))
                assertTrue(explanation.contains("главном потоке"))
                assertTrue(explanation.contains("не жёсткий предел"))
                assertTrue(explanation.contains("foreground"))
                activity.bindForProbe = { intent, connection, flags ->
                    binds++
                    conn = connection
                    assertEquals("android.speech.RecognitionService", intent.action)
                    assertEquals("com.google.android.katniss", intent.component?.packageName)
                    assertEquals("com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService", intent.component?.className)
                    assertTrue(flags and Context.BIND_AUTO_CREATE != 0)
                    if (Build.VERSION.SDK_INT >= 29) assertTrue(flags and Context.BIND_INCLUDE_CAPABILITIES != 0)
                    true
                }
                activity.unbindForProbe = { unbinds++ }
                assertEquals(0, binds)
                activity.startProbe()
                activity.startProbe()
                assertEquals(1, binds)
                conn.onServiceConnected(null, Binder())
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertHonest(it) }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(1, unbinds)
            conn.onServiceConnected(null, Binder())
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(1, unbinds)
        }
    }

    @Test fun rejectionAndBindExceptionBothUnbindAndPermitRetry() {
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.reservation = BindProbeReservation()
                var unbinds = 0
                activity.unbindForProbe = { unbinds++ }
                activity.bindForProbe = { _, _, _ -> false }
                activity.startProbe()
                assertEquals(1, unbinds)
                activity.bindForProbe = { _, _, _ -> throw SecurityException() }
                activity.startProbe()
                assertEquals(2, unbinds)
                activity.bindForProbe = { _, _, _ -> true }
                activity.startProbe()
                activity.finishProbe("test_cleanup")
                assertEquals(3, unbinds)
                assertHonest(activity)
            }
        }
    }

    @Test fun actualTimerUnbindsWithoutManualFinish() {
        val finished = CountDownLatch(1)
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.reservation = BindProbeReservation()
                activity.bindForProbe = { _, conn, _ -> conn.onServiceConnected(null, Binder()); true }
                activity.unbindForProbe = { finished.countDown() }
                activity.startProbe()
            }
            assertFalse("released before observation window", finished.await(200, TimeUnit.MILLISECONDS))
            assertTrue("deadline did not release bind", finished.await(15, TimeUnit.SECONDS))
            scenario.onActivity { assertHonest(it) }
        }
    }

    @Test fun failedUnbindRemainsBlockedAfterRecreationWithoutRetry() {
        val reservation = BindProbeReservation()
        var binds = 0
        var unbinds = 0
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.reservation = reservation
                activity.bindForProbe = { _, _, _ -> binds++; true }
                activity.unbindForProbe = { unbinds++; throw IllegalStateException() }
                activity.startProbe()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                activity.reservation = reservation
                activity.bindForProbe = { _, _, _ -> binds++; true }
                activity.unbindForProbe = { unbinds++ }
                assertTrue(reservation.isReserved())
                assertTrue(button(activity).performClick())
                assertEquals(1, binds)
                assertEquals(1, unbinds)
                assertFalse(button(activity).isEnabled)
                assertHonest(activity)
            }
        }
        assertEquals(1, unbinds)
    }

    @Test fun stopDuringBindDefersUnbindUntilBindReturns() {
        ActivityScenario.launch(BindDiagnosticActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.reservation = BindProbeReservation()
                var unbinds = 0
                activity.unbindForProbe = { unbinds++ }
                activity.bindForProbe = { _, _, _ ->
                    activity.finishProbe("activity_stopped")
                    assertEquals(0, unbinds)
                    true
                }
                activity.startProbe()
                assertEquals(1, unbinds)
                assertFalse(activity.reservation.isReserved())
            }
        }
    }
}
