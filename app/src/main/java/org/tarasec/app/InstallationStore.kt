package org.tarasec.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class TaraSecInstallation(
    val id: String,
    val name: String,
    val managementBaseUrl: String,
    val serviceIp: String,
    val registeredAt: Long
)

object InstallationStore {
    private const val PREFS = "tarasec_installations_v1"
    private const val KEY_ITEMS = "items"
    private const val KEY_SELECTED = "selected_id"

    fun load(context: Context): List<TaraSecInstallation> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    val base = normaliseBaseUrl(o.optString("managementBaseUrl", ""))
                    if (id.isBlank() || base.isBlank()) continue
                    add(
                        TaraSecInstallation(
                            id = id,
                            name = o.optString("name", endpointHost(base)).ifBlank { endpointHost(base) },
                            managementBaseUrl = base,
                            serviceIp = o.optString("serviceIp", endpointHost(base)).ifBlank { endpointHost(base) },
                            registeredAt = o.optLong("registeredAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun selectedId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)

    fun setSelected(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (id == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, id)
            }
            .apply()
    }

    fun register(
        context: Context,
        name: String,
        managementBaseUrl: String,
        serviceIp: String
    ): TaraSecInstallation {
        val base = normaliseBaseUrl(managementBaseUrl)
        require(base.isNotBlank()) { "Management URL is required" }
        val existing = load(context).firstOrNull {
            it.managementBaseUrl.equals(base, ignoreCase = true)
        }
        val item = TaraSecInstallation(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { endpointHost(base) },
            managementBaseUrl = base,
            serviceIp = serviceIp.trim().ifBlank { endpointHost(base) },
            registeredAt = existing?.registeredAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        val updated = load(context).filterNot { it.id == item.id } + item
        save(context, updated.sortedBy { it.name.lowercase() })
        setSelected(context, item.id)
        return item
    }

    fun remove(context: Context, id: String) {
        val updated = load(context).filterNot { it.id == id }
        save(context, updated)
        if (selectedId(context) == id) {
            setSelected(context, updated.firstOrNull()?.id)
        }
    }

    private fun save(context: Context, items: List<TaraSecInstallation>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("managementBaseUrl", item.managementBaseUrl)
                put("serviceIp", item.serviceIp)
                put("registeredAt", item.registeredAt)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun normaliseBaseUrl(value: String): String {
        var v = value.trim().trimEnd('/')
        if (v.isBlank()) return ""
        if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) {
            v = "http://$v"
        }
        return v
    }

    fun endpointHost(baseUrl: String): String = try {
        URI(normaliseBaseUrl(baseUrl)).host ?: ""
    } catch (_: Exception) {
        ""
    }
}
