package org.stypox.dicio.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Provider settings stay outside backup; the API key is encrypted with a non-exportable key. */
@Singleton
class PolzaSettings @Inject constructor(@ApplicationContext context: Context) {
    // Separate provider namespace: never migrate Polza credentials to RouterAI.
    private val file = java.io.File(context.noBackupFilesDir, "routerai-settings.json")
    private var keyAlias = ALIAS

    internal constructor(context: Context, keyAlias: String) : this(context) {
        this.keyAlias = keyAlias
    }

    data class Config(val enabled: Boolean, val model: String, val apiKey: String) {
        override fun toString() = "Config(enabled=$enabled, model=$model, apiKey=[REDACTED])"
    }

    enum class KeyStatus { ABSENT, AVAILABLE, UNREADABLE }
    data class EditorState(val enabled: Boolean, val model: String, val keyStatus: KeyStatus, val revision: Long)
    class StaleEditorException : IllegalStateException()
    class UncertainWriteException(cause: Exception) : IllegalStateException("Не удалось подтвердить запись настроек.", cause)
    class EditorReadException(val revision: Long, cause: Exception) : IllegalStateException("Не удалось прочитать настройки.", cause)

    private fun readData(): org.json.JSONObject? {
        val atomic = android.util.AtomicFile(file)
        val contents = try {
            atomic.openRead().bufferedReader().use { it.readText() }
        } catch (error: java.io.FileNotFoundException) {
            if (file.exists()) throw error
            return null
        }
        return org.json.JSONObject(contents)
    }

    private fun decrypt(data: org.json.JSONObject): String {
        val encrypted = data.optString("key")
        if (encrypted.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128,
            Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun load(): Config = synchronized(LOCK) {
        val data = readData() ?: return@synchronized Config(false, DEFAULT_MODEL, "")
        val enabled = data.optBoolean("enabled")
        // Disabled mode must not depend on the availability of the keystore.
        Config(enabled, data.optString("model"), if (enabled) decrypt(data) else "")
    }

    fun loadEditor(): EditorState = synchronized(LOCK) {
        val data = try { readData() } catch (error: Exception) { throw EditorReadException(revision, error) }
            ?: return@synchronized EditorState(false, DEFAULT_MODEL, KeyStatus.ABSENT, revision)
        val keyStatus = if (data.optString("key").isEmpty()) KeyStatus.ABSENT else try {
            if (decrypt(data).isBlank()) KeyStatus.UNREADABLE else KeyStatus.AVAILABLE
        } catch (_: Exception) { KeyStatus.UNREADABLE }
        EditorState(data.optBoolean("enabled"), data.optString("model"), keyStatus, revision)
    }

    /** Blank replacement preserves the encrypted key; removal must be explicit. */
    fun saveEditor(enabled: Boolean, model: String, replacement: String, removeKey: Boolean, expectedRevision: Long): Unit = synchronized(LOCK) {
        if (expectedRevision != revision) throw StaleEditorException()
        require(!(removeKey && replacement.isNotBlank()))
        val old = readData()
        val data = org.json.JSONObject().put("enabled", enabled).put("model", model.trim())
        if (replacement.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            data.put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            data.put("key", Base64.encodeToString(cipher.doFinal(replacement.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        } else if (!removeKey && old != null && old.optString("key").isNotEmpty()) {
            data.put("key", old.getString("key"))
            if (old.has("iv")) data.put("iv", old.get("iv"))
        }
        if (enabled) {
            PolzaProtocol.validateInput(Config(true, model.trim(), decrypt(data)), "validation")
        } else {
            if (model.isNotBlank()) PolzaProtocol.validateInput(Config(false, model.trim(), "placeholder"), "validation")
            if (replacement.isNotBlank()) PolzaProtocol.validateInput(Config(false, "placeholder", replacement.trim()), "validation")
        }
        // A failed atomic write may still have changed on-disk state.
        try { writeData(data) } catch (error: Exception) { throw UncertainWriteException(error) }
        finally { revision++ }
    }

    fun save(config: Config): Unit = synchronized(LOCK) {
        saveEditor(config.enabled, config.model, config.apiKey, false, revision)
    }

    private fun writeData(data: org.json.JSONObject) {
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun reset(expectedRevision: Long): Unit = synchronized(LOCK) {
        if (expectedRevision != revision) throw StaleEditorException()
        // Invalidate editors even if deletion only partially succeeds.
        revision++
        android.util.AtomicFile(file).delete()
        check(!file.exists() && !java.io.File(file.path + ".bak").exists() &&
            !java.io.File(file.path + ".new").exists()) { "Не удалось удалить настройки." }
    }

    private fun key(create: Boolean = true): SecretKey {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            throw IllegalStateException("Защищённое хранение ключа требует Android 6 или новее.")
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(keyAlias, null) as? SecretKey) ?: if (!create) {
            throw IllegalStateException("Ключ защищённого хранилища недоступен.")
        } else KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-6-luna"
        private const val ALIAS = "dicio.routerai.api-key"
        private val LOCK = Any()
        private var revision = 0L
    }
}
