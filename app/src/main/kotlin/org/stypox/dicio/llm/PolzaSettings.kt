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

    data class Config(val enabled: Boolean, val model: String, val apiKey: String) {
        override fun toString() = "Config(enabled=$enabled, model=$model, apiKey=[REDACTED])"
    }

    fun load(): Config = synchronized(LOCK) {
        val atomic = android.util.AtomicFile(file)
        val contents = try {
            atomic.openRead().bufferedReader().use { it.readText() }
        } catch (error: java.io.FileNotFoundException) {
            if (file.exists()) throw error
            return@synchronized Config(false, DEFAULT_MODEL, "")
        }
        val data = org.json.JSONObject(contents)
        val encrypted = data.optString("key")
        val apiKey = if (encrypted.isEmpty()) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,
                Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }
        Config(data.optBoolean("enabled"), data.optString("model"), apiKey)
    }

    fun save(config: Config): Unit = synchronized(LOCK) {
        val data = org.json.JSONObject().put("enabled", config.enabled)
            .put("model", config.model.trim())
        if (config.apiKey.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            data.put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            data.put("key", Base64.encodeToString(
                cipher.doFinal(config.apiKey.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
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

    fun reset(): Unit = synchronized(LOCK) {
        android.util.AtomicFile(file).delete()
    }

    private fun key(): SecretKey {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            throw IllegalStateException("Защищённое хранение ключа требует Android 6 или новее.")
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-6-luna"
        private const val ALIAS = "dicio.routerai.api-key"
        private val LOCK = Any()
    }
}
