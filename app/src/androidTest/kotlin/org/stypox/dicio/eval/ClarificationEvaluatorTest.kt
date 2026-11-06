package org.stypox.dicio.eval

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.llm.MediaActions
import org.stypox.dicio.llm.PolzaClient
import org.stypox.dicio.llm.PolzaSettings
import org.stypox.dicio.llm.TvActions
import org.stypox.dicio.screenshot.FakeSttInputDeviceWrapper

/** Real evaluator and protocol; only the provider HTTP responses are simulated. */
@RunWith(AndroidJUnit4::class)
class ClarificationEvaluatorTest {
    @Test fun repeatedClarificationReplacesContextAndStillFailsClosed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "clarification-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val alias = "clarification-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val settings = PolzaSettings(isolated, alias)
        val payloads = mutableListOf<JSONObject>()
        val decisions = ArrayDeque(listOf(
            "clarify" to "Первый вопрос?",
            "clarify" to "Второй вопрос?",
            "get_time" to "",
            "clarify" to "Третий вопрос?",
            "clarify" to "Четвёртый вопрос?",
            "reply" to "Готово.",
        ))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val message = JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")
            synchronized(payloads) { payloads.add(JSONObject(message)) }
            val (action, text) = decisions.removeFirst()
            val decision = JSONObject().put("action", action).put("text", text)
                .put("app_id", "").put("settings_section", "").put("search_query", "")
                .put("media_id", "").put("media_operation", "").put("media_seconds", 0)
            val response = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("finish_reason", "stop").put("message", JSONObject().put("content", decision.toString()))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(response.toString().toResponseBody()).build()
        }.build()
        val vpnLock = Mutex()
        val actions = TvActions(app, MediaActions(app))
        val evaluator = SkillEvaluatorImpl(SkillContextImpl.newForPreviews(app),
            Provider<SkillHandler> { error("legacy path must not run") }, FakeSttInputDeviceWrapper(),
            settings, PolzaClient(http), actions, { vpnLock.withLock { actions.clearVpn() } })

        suspend fun say(text: String, expected: String) {
            withContext(Dispatchers.Main) {
                evaluator.processInputEvent(InputEvent.Final(listOf(text to 1f)))
            }
            withTimeout(15_000) { evaluator.voicePresentation.first { it.answer == expected } }
            // Drain the main dispatcher after publication, including saving the clarification.
            withContext(Dispatchers.Main) { }
        }
        fun payload(index: Int) = synchronized(payloads) { payloads[index] }
        try {
            settings.save(PolzaSettings.Config(true, "test/model", "test-key"))
            say("исходная фраза", "Первый вопрос?")
            vpnLock.lock()
            withContext(Dispatchers.Main) {
                evaluator.finishInputSession()
                assertTrue(evaluator.hasPendingClarification())
                evaluator.processInputEvent(InputEvent.Final(listOf("короткий ответ" to 1f)))
                vpnLock.unlock()
            }
            withTimeout(15_000) { evaluator.voicePresentation.first { it.answer == "Второй вопрос?" } }
            withContext(Dispatchers.Main) { }
            val first = payload(1).getJSONObject("previous_clarification")
            assertEquals("исходная фраза", first.getString("request"))
            assertEquals("Первый вопрос?", first.getString("question"))
            withContext(Dispatchers.Main) { evaluator.finishInputSession() }
            say("следующий ответ", "Пожалуйста, повторите команду полностью.")
            val second = payload(2).getJSONObject("previous_clarification")
            assertEquals(setOf("request", "question"), second.keys().asSequence().toSet())
            assertEquals("короткий ответ", second.getString("request"))
            assertEquals("Второй вопрос?", second.getString("question"))
            assertFalse(withContext(Dispatchers.Main) { evaluator.hasPendingClarification() })
            say("новый запрос", "Третий вопрос?")
            assertFalse(payload(3).has("previous_clarification"))
            say("ещё ответ", "Четвёртый вопрос?")
            assertTrue(withContext(Dispatchers.Main) { evaluator.hasPendingClarification() })
            say("отмена", "Отменено.")
            assertFalse(withContext(Dispatchers.Main) { evaluator.hasPendingClarification() })
            assertEquals(5, synchronized(payloads) { payloads.size })
            say("после отмены", "Готово.")
            assertFalse(payload(5).has("previous_clarification"))
        } finally {
            withContext(Dispatchers.Main) { evaluator.cancelPendingInput() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            File(directory, "routerai-settings.json").delete()
            directory.delete()
        }
    }
}
