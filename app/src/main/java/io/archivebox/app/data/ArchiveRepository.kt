package io.archivebox.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    companion object { private val writeLock = Mutex() }
    private val _registry = MutableStateFlow(readRegistry())
    val registry = _registry.asStateFlow()
    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "server_registry") _registry.value = readRegistry()
    }
    init { prefs.registerOnSharedPreferenceChangeListener(preferencesChanged) }
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

    private fun readRegistry(): ServerRegistry {
        val value = prefs.getString("server_registry", null) ?: return ServerRegistry()
        val data = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        return ServerRegistry.fromJson(json)
    }

    private fun persistRegistry(registry: ServerRegistry) {
        val json = registry.toJson()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(json.toString().toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("server_registry", encrypted).commit()) { "Could not save server settings." }
        _registry.value = registry
    }

    suspend fun saveConnection(connection: ServerConfiguration, select: Boolean = true) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val normalized = connection.copy(server = normalizeServer(connection.server), token = connection.token.trim())
            require(normalized.token.isNotEmpty()) { "Enter your API key." }
            val updated = readRegistry().upsert(normalized)
            persistRegistry(if (select) updated.copy(active_server_id = normalized.id, default_server_ids = listOf(normalized.id)) else updated)
            dismissSetup()
        }
    }

    suspend fun selectConnection(id: String) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val current = readRegistry()
            val selected = requireNotNull(current.servers.firstOrNull { it.id == id }) { "This connection is no longer saved." }
            require(selected.token.isNotBlank()) { "Enter your API key." }
            persistRegistry(current.copy(active_server_id = id))
        }
    }

    suspend fun removeConnection(id: String) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            persistRegistry(readRegistry().remove(id))
            prefs.edit().remove("tags.$id").apply()
        }
    }

    fun dismissSetup() {
        prefs.edit().putBoolean("setupDismissed", true).apply()
        _setupDismissed.value = true
    }

    suspend fun clearConnection() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val current = readRegistry()
            current.active_server_id?.let { persistRegistry(current.remove(it)) }
        }
    }

    fun recentTags(server_id: String): List<String> {
        val json = JSONArray(prefs.getString("tags.$server_id", "[]"))
        return (0 until json.length()).map(json::getString)
    }

    fun rememberTags(server_id: String, tags: List<String>) {
        val recent = normalizeTags(tags.asReversed() + recentTags(server_id)).take(8)
        prefs.edit().putString("tags.$server_id", JSONArray(recent).toString()).apply()
    }
}
