package org.stypox.dicio.skills.timer

import android.os.Looper
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
    @Test fun cancelFromMainInvokesRemovalCallbackOnMain() = runBlocking {
        val threads = mutableListOf<Looper?>()
        withContext(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                val timer = SetTimer(Duration.ofMinutes(1), "test", {}, {}, {}, {
                    threads.add(Looper.myLooper())
                })
                timer.cancel()
            }
        }
        assertEquals(listOf(Looper.getMainLooper()), threads)
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
