package org.stypox.dicio.llm

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.KeyStore
import android.os.Build

@RunWith(AndroidJUnit4::class)
class PolzaSettingsEditorTest {
    private val alias = "dicio.polza.test.${UUID.randomUUID()}"
    private var directory: File? = null

    private fun settings(): PolzaSettings {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "polza-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        this.directory = directory
        return PolzaSettings(object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }, alias)
    }

    @After fun cleanup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
        directory?.let { dir ->
            listOf("routerai-settings.json", "routerai-settings.json.bak", "routerai-settings.json.new")
                .forEach { File(dir, it).delete() }
            dir.delete()
        }
    }

    @Test fun validationFailurePreservesExistingState() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        val settings = settings()
        val initial = settings.loadEditor()
        assertThrows(IllegalArgumentException::class.java) {
            settings.saveEditor(true, "x".repeat(201), "key", false, initial.revision)
        }
        assertEquals(initial, settings.loadEditor())
        assertThrows(IllegalArgumentException::class.java) {
            settings.saveEditor(true, "model", "bad\nkey", false, initial.revision)
        }
        assertEquals(initial, settings.loadEditor())
    }

    @Test fun blankKeyPreservesCiphertextAndStaleResetFails() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        val settings = settings()
        val initial = settings.loadEditor()
        settings.saveEditor(true, "model", "key", false, initial.revision)
        val saved = settings.loadEditor()
        settings.saveEditor(true, "other", "", false, saved.revision)
        assertEquals("key", settings.load().apiKey)
        assertThrows(PolzaSettings.StaleEditorException::class.java) { settings.reset(saved.revision) }
        assertThrows(PolzaSettings.StaleEditorException::class.java) {
            settings.saveEditor(false, "model", "", false, saved.revision)
        }
        assertEquals("other", settings.load().model)
    }

    @Test fun staleResetIsRejectedWithoutKeystore() {
        val settings = settings()
        val initial = settings.loadEditor()
        settings.saveEditor(false, "model", "", false, initial.revision)
        assertThrows(PolzaSettings.StaleEditorException::class.java) { settings.reset(initial.revision) }
        assertEquals("model", settings.loadEditor().model)
    }

    @Test fun failedWriteInvalidatesEditor() {
        val settings = settings()
        val initial = settings.loadEditor()
        val dir = checkNotNull(directory)
        check(dir.delete())
        check(dir.createNewFile())
        assertThrows(PolzaSettings.UncertainWriteException::class.java) {
            settings.saveEditor(false, "model", "", false, initial.revision)
        }
        assertThrows(PolzaSettings.StaleEditorException::class.java) {
            settings.reset(initial.revision)
        }
    }

    @Test fun corruptFileCanBeResetOnlyWithCurrentRevision() {
        val settings = settings()
        val file = File(checkNotNull(directory), "routerai-settings.json")
        file.writeText("{invalid")
        val failure = assertThrows(PolzaSettings.EditorReadException::class.java) { settings.loadEditor() }
        assertThrows(Exception::class.java) { settings.load() }
        settings.reset(failure.revision)
        val recovered = settings.loadEditor()
        assertEquals(false, recovered.enabled)
        assertEquals(PolzaSettings.DEFAULT_MODEL, recovered.model)
        assertThrows(PolzaSettings.StaleEditorException::class.java) { settings.reset(failure.revision) }
        settings.saveEditor(false, "new-model", "", false, recovered.revision)
        assertEquals("new-model", settings.loadEditor().model)
    }
}
