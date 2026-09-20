package io.archivebox.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ArchiveRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("archivebox", Context.MODE_PRIVATE)
    val api = ArchiveApi()
    private val _connection = MutableStateFlow(readConnection())
    val connection = _connection.asStateFlow()
    private val _setupDismissed = MutableStateFlow(prefs.getBoolean("setupDismissed", false))
    val setupDismissed = _setupDismissed.asStateFlow()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("archivebox.connection", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("archivebox.connection", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun readConnection(): Connection? {
        val value = prefs.getString("connection", null) ?: return null
        return runCatching {
            val data = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
            Connection(json.getString("server"), json.getString("token"), json.optString("persona", "Default"))
        }.getOrNull()
    }

    suspend fun saveConnection(connection: Connection) = withContext(Dispatchers.IO) {
        val normalized = connection.copy(server = normalizeServer(connection.server), token = connection.token.trim())
        require(normalized.token.isNotEmpty()) { "Enter your API key." }
        val json = JSONObject().put("server", normalized.server).put("token", normalized.token).put("persona", normalized.persona)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(json.toString().toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("connection", encrypted).putBoolean("setupDismissed", true).commit()) { "Could not save this connection." }
        _connection.value = normalized
        _setupDismissed.value = true
    }

    fun dismissSetup() {
        prefs.edit().putBoolean("setupDismissed", true).apply()
        _setupDismissed.value = true
    }

    suspend fun clearConnection() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove("connection").commit()) { "Could not remove the connection." }
        _connection.value = null
    }

    fun recentTags(server: String): List<String> {
        val json = JSONArray(prefs.getString("tags.$server", "[]"))
        return (0 until json.length()).map(json::getString)
    }

    fun rememberTags(server: String, tags: List<String>) {
        val recent = normalizeTags(tags.asReversed() + recentTags(server)).take(8)
        prefs.edit().putString("tags.$server", JSONArray(recent).toString()).apply()
    }
}
