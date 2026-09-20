package io.archivebox.app.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Immutable destination captured before networking; persona null means named Default. */
data class ServerConfiguration(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val server: String,
    val token: String,
    val persona: String? = null,
)

data class ServerRegistry(
    val schema_version: Int = 1,
    val servers: List<ServerConfiguration> = emptyList(),
    val active_server_id: String? = null,
    val default_server_ids: List<String> = emptyList(),
) {
    val active_server: ServerConfiguration? get() = servers.find { it.id == active_server_id }
    val default_servers: List<ServerConfiguration> get() = default_server_ids.mapNotNull { id -> servers.find { it.id == id } }

    fun upsert(configuration: ServerConfiguration): ServerRegistry {
        if (servers.any { it.id == configuration.id }) return copy(servers = servers.map { if (it.id == configuration.id) configuration else it })
        return copy(servers = servers + configuration,
            active_server_id = if (servers.isEmpty()) configuration.id else active_server_id,
            default_server_ids = if (servers.isEmpty()) listOf(configuration.id) else default_server_ids)
    }

    fun remove(id: String): ServerRegistry {
        val remaining = servers.filterNot { it.id == id }
        return copy(servers = remaining, active_server_id = if (active_server_id == id) remaining.firstOrNull()?.id else active_server_id,
            default_server_ids = default_server_ids.filterNot { it == id })
    }

    fun toJson(): JSONObject {
        validate()
        val items = servers.map { item -> JSONObject().put("id", item.id).put("name", item.name)
            .put("server", item.server).put("token", item.token).put("persona", item.persona ?: JSONObject.NULL) }
        return JSONObject().put("schema_version", schema_version).put("servers", JSONArray(items))
            .put("active_server_id", active_server_id ?: JSONObject.NULL).put("default_server_ids", JSONArray(default_server_ids))
    }

    companion object {
        fun fromJson(json: JSONObject): ServerRegistry {
            val items = json.getJSONArray("servers")
            val registry = ServerRegistry(
                schema_version = json.getInt("schema_version"),
                servers = (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    ServerConfiguration(id = item.getString("id"), name = item.getString("name"),
                        server = item.getString("server"), token = item.getString("token"),
                        persona = if (item.isNull("persona")) null else item.getString("persona"))
                },
                active_server_id = if (json.isNull("active_server_id")) null else json.getString("active_server_id"),
                default_server_ids = json.getJSONArray("default_server_ids").let { ids -> (0 until ids.length()).map(ids::getString) },
            )
            registry.validate()
            return registry
        }
    }

    fun validate() {
        val ids = servers.map { it.id }.toSet()
        require(schema_version == 1 && ids.size == servers.size &&
            servers.all { runCatching { UUID.fromString(it.id).toString() == it.id && java.net.URI(it.server).let { url -> url.scheme in listOf("http", "https") && url.host != null && url.userInfo == null && url.query == null && url.fragment == null } }.getOrDefault(false) } &&
            (active_server_id == null || active_server_id in ids) &&
            default_server_ids.distinct().size == default_server_ids.size && default_server_ids.all { it in ids }) {
            "Saved server settings are invalid or from an unsupported version."
        }
    }
}
