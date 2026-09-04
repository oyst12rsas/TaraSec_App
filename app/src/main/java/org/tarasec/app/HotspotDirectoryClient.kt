package org.tarasec.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
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
    val description: String?,
    val ssid: String?,
    val priceCreditsPerMiB: Double?,
    val priceLabel: String?
)

data class NearbyTaraSecHotspot(
    val ssid: String,
    val bssid: String,
    val signalDbm: Int,
    val signalLabel: String,
    val verifiedDirectoryEntry: Boolean,
    val hotspotId: String?,
    val priceCreditsPerMiB: Double?,
    val priceLabel: String?,
    val connected: Boolean = false
)

object HotspotDirectoryClient {
    const val DEFAULT_BASE_URL = "http://100.68.126.0"

    private const val DEMO_PRICE_LABEL =
        "Demo prices: 100 MB KSh 15 · 250 MB KSh 30 · 500 MB KSh 55 · 1 GB KSh 100 · 2 GB KSh 180 · 5 GB KSh 400 · 10 GB KSh 700"

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
                            description = item.optString("public_description").takeIf { it.isNotBlank() },
                            ssid = item.optString("ssid").takeIf { it.isNotBlank() },
                            priceCreditsPerMiB = if (item.isNull("price_credits_per_mib")) null else
                                item.optDouble("price_credits_per_mib").takeIf { !it.isNaN() },
                            priceLabel = item.optString("price_label").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connectedHotspotLabel(context: Context): String? {
        val base = LocalGateway.baseUrl(context)?.trimEnd('/') ?: return null
        val connection = runCatching {
            URL("$base/hotspot/tarasec_hotspot_info.php").openConnection() as HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 3500
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return null

            val packageParts = mutableListOf<String>()
            val packages = json.optJSONArray("packages")
            if (packages != null) {
                for (i in 0 until packages.length()) {
                    val item = packages.optJSONObject(i) ?: continue
                    val label = item.optString("label").trim()
                    val price = item.optDouble("price_ksh", Double.NaN)
                    if (label.isNotBlank() && !price.isNaN()) {
                        val priceText = if (price % 1.0 == 0.0) price.toInt().toString() else "%.2f".format(price)
                        packageParts += "$label KSh $priceText"
                    }
                }
            }
            val pricing = if (packageParts.isNotEmpty()) {
                "Demo prices: " + packageParts.joinToString(" · ")
            } else {
                DEMO_PRICE_LABEL
            }

            val usage = json.optJSONObject("usage")
            if (usage != null) {
                val session = usage.optDouble("session_mib", Double.NaN)
                val total = usage.optDouble("total_mib", Double.NaN)
                val remaining = if (usage.isNull("remaining_mib")) Double.NaN else usage.optDouble("remaining_mib", Double.NaN)
                buildString {
                    append(pricing)
                    if (!session.isNaN()) append("\nThis session: %.1f MiB".format(session))
                    if (!total.isNaN()) append(" · Total here: %.1f MiB".format(total))
                    if (!remaining.isNaN()) append(" · Remaining: %.1f MiB".format(remaining))
                }
            } else {
                pricing
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun nearby(context: Context, directory: List<DirectoryHotspot>): List<NearbyTaraSecHotspot> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        runCatching { wifi.startScan() }
        val connectionInfo = runCatching { wifi.connectionInfo }.getOrNull()
        val currentBssid = connectionInfo?.bssid.orEmpty()
        val currentSsid = connectionInfo?.ssid.orEmpty().trim().trim('"')
        val directoryBySsid = directory.mapNotNull { item ->
            item.ssid?.trim()?.takeIf { it.isNotEmpty() }?.let { it.lowercase() to item }
        }.toMap()
        val connectedLocalLabel = connectedHotspotLabel(context)

        val scanCandidates = wifi.scanResults
            .asSequence()
            .filter { it.SSID.trim().startsWith("TaraSec", ignoreCase = true) }
            .groupBy { it.BSSID.lowercase() }
            .values
            .mapNotNull { results -> results.maxByOrNull { it.level } }
            .map { result ->
                val ssid = result.SSID.trim()
                val published = directoryBySsid[ssid.lowercase()]
                val isConnected = currentBssid.isNotBlank() && result.BSSID.equals(currentBssid, ignoreCase = true)
                NearbyTaraSecHotspot(
                    ssid = ssid,
                    bssid = result.BSSID.orEmpty(),
                    signalDbm = result.level,
                    signalLabel = if (isConnected) "Connected" else when {
                        result.level >= -55 -> "Excellent"
                        result.level >= -67 -> "Good"
                        result.level >= -75 -> "Acceptable"
                        else -> "Weak"
                    },
                    verifiedDirectoryEntry = published != null,
                    hotspotId = published?.id,
                    priceCreditsPerMiB = published?.priceCreditsPerMiB,
                    priceLabel = when {
                        isConnected && connectedLocalLabel != null -> connectedLocalLabel
                        published?.priceLabel != null -> published.priceLabel
                        else -> DEMO_PRICE_LABEL
                    },
                    connected = isConnected
                )
            }
            .toMutableList()

        val connectedAlreadyPresent = scanCandidates.any { it.connected }
        if (!connectedAlreadyPresent && currentSsid.startsWith("TaraSec", ignoreCase = true)) {
            val published = directoryBySsid[currentSsid.lowercase()]
            scanCandidates += NearbyTaraSecHotspot(
                ssid = currentSsid,
                bssid = currentBssid,
                signalDbm = connectionInfo?.rssi ?: -127,
                signalLabel = "Connected",
                verifiedDirectoryEntry = published != null,
                hotspotId = published?.id,
                priceCreditsPerMiB = published?.priceCreditsPerMiB,
                priceLabel = connectedLocalLabel ?: published?.priceLabel ?: DEMO_PRICE_LABEL,
                connected = true
            )
        }

        return scanCandidates
            .sortedWith(compareByDescending<NearbyTaraSecHotspot> { it.connected }.thenByDescending { it.signalDbm })
    }
}
