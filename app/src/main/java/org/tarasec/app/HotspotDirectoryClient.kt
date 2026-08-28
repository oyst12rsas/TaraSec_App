package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DirectoryHotspot(
    val id: String,
    val name: String,
    val countryCode: String?,
    val locality: String?,
    val latitude: Double?,
    val longitude: Double?,
    val description: String?
)

object HotspotDirectoryClient {
    const val DEFAULT_BASE_URL = "http://100.68.126.0"

    fun list(baseUrl: String = DEFAULT_BASE_URL, country: String? = null): List<DirectoryHotspot> {
        val suffix = country?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "?country=" + URLEncoder.encode(it.uppercase(), Charsets.UTF_8.name())
        }.orEmpty()
        val endpoint = baseUrl.trimEnd('/') + "/v1/directory" + suffix
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val trimmed = body.trim()

            if (!trimmed.startsWith("{")) {
                val kind = if (trimmed.startsWith("<!doctype", true) || trimmed.startsWith("<html", true)) {
                    "an HTML page"
                } else {
                    "a non-JSON response"
                }
                throw IllegalStateException("TaraSec directory endpoint returned $kind (HTTP $code). Nearby Wi-Fi discovery does not depend on this service.")
            }

            val json = runCatching { JSONObject(trimmed) }.getOrElse {
                throw IllegalStateException("TaraSec directory returned invalid JSON (HTTP $code).")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error", "Directory HTTP $code"))
            }

            val array = json.optJSONArray("hotspots") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        DirectoryHotspot(
                            id = item.optString("hotspot_id"),
                            name = item.optString("name", item.optString("hotspot_id")),
                            countryCode = item.optString("country_code").takeIf { it.isNotBlank() },
                            locality = item.optString("locality").takeIf { it.isNotBlank() },
                            latitude = if (item.isNull("latitude")) null else item.optDouble("latitude"),
                            longitude = if (item.isNull("longitude")) null else item.optDouble("longitude"),
                            description = item.optString("public_description").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
