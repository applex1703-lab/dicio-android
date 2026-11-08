package org.stypox.dicio.skills.timer

import android.os.Looper
import android.os.CountDownTimer
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetTimerThreadTest {
    @Test fun finishWithoutTickStillReportsExpirationOnce() = runBlocking {
        withContext(Dispatchers.Main) {
            var expirations = 0
            var removals = 0
            val timer = SetTimer(Duration.ofMinutes(1), "test", {}, {}, { expirations++ }, {
                removals++
            })
            // Simulate a main thread stalled past the entire countdown and alarm period.
            val field = SetTimer::class.java.getDeclaredField("countDownTimer")
            field.isAccessible = true
            val countdown = field.get(timer) as CountDownTimer
            countdown.cancel()
            countdown.onFinish()
            countdown.onFinish()
            assertEquals(1, expirations)
            assertEquals(1, removals)
            assertEquals(-30_000L, timer.lastTickMillis)
        }
    }

    @Test fun cancelFromMainInvokesRemovalCallbackOnMain() = runBlocking {
        val threads = mutableListOf<Looper?>()
        withContext(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                val timer = SetTimer(Duration.ofMinutes(1), "test", {}, {}, {}, {
                    threads.add(Looper.myLooper())
                })
                timer.cancel()
                timer.cancel()
            }
        }
        assertEquals(listOf(Looper.getMainLooper()), threads)
    }

    @Test fun naturalFinishUpdatesFinalStateAndCannotCancelTwice() {
        val finished = CountDownLatch(1)
        var callbacks = 0
        lateinit var timer: SetTimer
        runBlocking {
            withContext(Dispatchers.Main) {
                timer = SetTimer(Duration.ofMillis(-29_990), null, {}, {}, {}, {
                    callbacks++
                    finished.countDown()
                })
            }
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        runBlocking {
            withContext(Dispatchers.Main) {
                assertEquals(-30_000L, timer.lastTickMillis)
                timer.cancel()
                assertEquals(1, callbacks)
            }
        }
    }

    @Test fun naturalFinishInvokesRemovalCallbackOnMain() {
        val finished = CountDownLatch(1)
        var callbackOnMain = false
        runBlocking {
            withContext(Dispatchers.Main) {
                // The implementation adds a 30-second alarm period; shorten the test only.
                SetTimer(Duration.ofMillis(-29_990), null, {}, {}, {}, {
                    callbackOnMain = Looper.myLooper() == Looper.getMainLooper()
                    finished.countDown()
                })
            }
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(callbackOnMain)
    }
}
