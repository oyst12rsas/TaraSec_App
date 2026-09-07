package org.tarasec.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
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
    private const val PRICE_PREFS = "tarasec_hotspot_price_cache"
    private const val SUBSCRIBER_ACCOUNT_URL = "https://tarasec.org/api/v1/subscriber/subscriber-account.php"

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

    private fun cachedPriceLabel(context: Context, ssid: String): String? =
        context.getSharedPreferences(PRICE_PREFS, Context.MODE_PRIVATE)
            .getString(ssid.trim().lowercase(), null)
            ?.takeIf { it.isNotBlank() }

    private fun cachePriceLabel(context: Context, ssid: String, label: String) {
        if (ssid.isBlank() || label.isBlank()) return
        context.getSharedPreferences(PRICE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ssid.trim().lowercase(), label)
            .apply()
    }

    private fun wifiNetwork(context: Context): android.net.Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun connectedGatewayKey(context: Context): String? {
        val base = LocalGateway.baseUrl(context)?.trimEnd('/') ?: return null
        val host = runCatching { URL(base).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val network = wifiNetwork(context) ?: return null
        val connection = runCatching {
            network.openConnection(URL("http://$host:8080/hotspot/tarasec_identity.php")) as HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 3500
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optString("gateway_key").trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun connectedCentralUsageLabel(context: Context): String? {
        val token = SubscriberAccountClient.storedToken(context) ?: return null
        val gatewayKey = connectedGatewayKey(context) ?: return null
        val connection = runCatching {
            URL(SUBSCRIBER_ACCOUNT_URL).openConnection() as HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-TaraSec-Subscriber-Token", token)
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("ok", false)) return null
            val sessions = json.optJSONArray("sessions") ?: return null
            var matched: JSONObject? = null
            for (i in 0 until sessions.length()) {
                val item = sessions.optJSONObject(i) ?: continue
                if (item.optString("gateway_key") == gatewayKey && item.isNull("ended_at")) {
                    matched = item
                    break
                }
            }
            if (matched == null) {
                for (i in 0 until sessions.length()) {
                    val item = sessions.optJSONObject(i) ?: continue
                    if (item.optString("gateway_key") == gatewayKey) {
                        matched = item
                        break
                    }
                }
            }
            matched?.let {
                val mib = it.optString("mib", "0")
                val charged = it.optString("charged_credits", "0")
                val rate = it.optString("price_credits_per_mib", "0")
                "TaraSec roaming: $rate credits/MiB\nThis session: $mib MiB · Charged: $charged credits"
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun connectedHotspotLabel(context: Context): String? {
        val base = LocalGateway.baseUrl(context)?.trimEnd('/') ?: return null
        val host = runCatching { URL(base).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val endpoint = URL("http://$host:8080/hotspot/tarasec_hotspot_info.php")
        val network = wifiNetwork(context) ?: return null
        val connection = runCatching {
            network.openConnection(endpoint) as HttpURLConnection
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
            if (packageParts.isEmpty()) return null
            "Prices: " + packageParts.joinToString(" · ")
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun refreshConnectedPricingAndUsage(
        context: Context,
        current: List<NearbyTaraSecHotspot>
    ): List<NearbyTaraSecHotspot> {
        if (current.none { it.connected }) return current
        val connected = current.firstOrNull { it.connected } ?: return current
        val local = connectedHotspotLabel(context)
        val usage = connectedCentralUsageLabel(context)
        if (local != null) cachePriceLabel(context, connected.ssid, local)
        val label = listOfNotNull(local ?: cachedPriceLabel(context, connected.ssid), usage)
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
            ?: connected.priceLabel
        return current.map { candidate ->
            if (candidate.connected) candidate.copy(priceLabel = label) else candidate
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun collectFreshScanResults(wifi: WifiManager): List<ScanResult> {
        val byBssid = linkedMapOf<String, ScanResult>()

        fun mergeSnapshot() {
            for (result in wifi.scanResults) {
                val bssid = result.BSSID.orEmpty().lowercase()
                if (bssid.isBlank()) continue
                val previous = byBssid[bssid]
                if (previous == null || result.level > previous.level) {
                    byBssid[bssid] = result
                }
            }
        }

        mergeSnapshot()
        runCatching { wifi.startScan() }

        repeat(4) {
            Thread.sleep(750)
            mergeSnapshot()
        }

        return byBssid.values.toList()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun nearby(
        context: Context,
        directory: List<DirectoryHotspot>,
        includeConnectedPricing: Boolean = true
    ): List<NearbyTaraSecHotspot> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val connectionInfo = runCatching { wifi.connectionInfo }.getOrNull()
        val currentBssid = connectionInfo?.bssid.orEmpty()
        val currentSsid = connectionInfo?.ssid.orEmpty().trim().trim('"')

        val directoryBySsid = directory.mapNotNull { item ->
            item.ssid?.trim()?.takeIf { it.isNotEmpty() }?.let { it.lowercase() to item }
        }.toMap()
        val connectedLocalLabel = if (includeConnectedPricing) connectedHotspotLabel(context) else null
        val connectedUsageLabel = if (includeConnectedPricing) connectedCentralUsageLabel(context) else null
        val connectedLabel = listOfNotNull(connectedLocalLabel, connectedUsageLabel)
            .joinToString("\n").takeIf { it.isNotBlank() }
        if (connectedLocalLabel != null && currentSsid.startsWith("TaraSec", ignoreCase = true)) {
            cachePriceLabel(context, currentSsid, connectedLocalLabel)
        }

        val scanCandidates = collectFreshScanResults(wifi)
            .asSequence()
            .filter { it.SSID.trim().startsWith("TaraSec", ignoreCase = true) }
            .groupBy { it.BSSID.lowercase() }
            .values
            .mapNotNull { results -> results.maxByOrNull { it.level } }
            .map { result ->
                val ssid = result.SSID.trim()
                val published = directoryBySsid[ssid.lowercase()]
                val cached = cachedPriceLabel(context, ssid)
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
                        isConnected && connectedLabel != null -> connectedLabel
                        published?.priceLabel != null -> published.priceLabel
                        else -> cached
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
                priceLabel = connectedLabel ?: published?.priceLabel ?: cachedPriceLabel(context, currentSsid),
                connected = true
            )
        }

        return scanCandidates
            .sortedWith(compareByDescending<NearbyTaraSecHotspot> { it.connected }.thenByDescending { it.signalDbm })
    }
}
