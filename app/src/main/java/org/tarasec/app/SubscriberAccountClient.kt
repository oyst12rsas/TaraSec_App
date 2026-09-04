package org.tarasec.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val SUBSCRIBER_API_BASE = "https://tarasec.org/api/v1/subscriber"
private const val IDENTITY_API_BASE = "https://tarasec.org/api/v1/identity"
private const val SUBSCRIBER_TOKEN_KEY = "global-subscriber-token"

data class SubscriberUsage(
    val sessionId: Long,
    val hotspot: String,
    val countryCode: String?,
    val priceLabel: String?,
    val priceCreditsPerMiB: String,
    val startedAt: String,
    val endedAt: String?,
    val mib: String,
    val chargedCredits: String
)

data class SubscriberCreditFacility(
    val status: String,
    val creditLimitCredits: String,
    val debtCredits: String,
    val availableCredit: String,
    val drawEnabled: Boolean
)

data class SubscriberAccount(
    val customerId: Long,
    val email: String?,
    val phone: String?,
    val balanceCredits: String,
    val creditFacility: SubscriberCreditFacility,
    val paymentEnabled: Boolean,
    val usages: List<SubscriberUsage>
)

data class HotspotActivationResult(
    val account: SubscriberAccount,
    val internetAvailable: Boolean
)

object SubscriberAccountClient {
    fun storedToken(context: Context): String? = SecureCredentialStore.get(context, SUBSCRIBER_TOKEN_KEY)

    fun clearToken(context: Context) {
        SecureCredentialStore.remove(context, SUBSCRIBER_TOKEN_KEY)
    }

    fun identityLoginUrl(provider: String): String {
        val normalized = provider.lowercase()
        require(normalized == "google" || normalized == "facebook") { "Unsupported identity provider" }
        return IDENTITY_API_BASE + "/identity-start.php?provider=" +
            URLEncoder.encode(normalized, "UTF-8") + "&app_redirect=" +
            URLEncoder.encode("tarasec://identity", "UTF-8")
    }

    fun exchangeIdentityCode(context: Context, code: String): SubscriberAccount {
        val deviceKey = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw IllegalStateException("Android device identity is unavailable")
        val body = form(
            "code" to code.trim(),
            "device_key" to deviceKey.lowercase(),
            "device_label" to "Android ${Build.MODEL}"
        )
        val json = requestAbsolute(IDENTITY_API_BASE + "/identity-exchange.php", "POST", body, null)
        val token = json.optString("token")
        if (token.isBlank()) throw IllegalStateException("TaraSec identity exchange did not return a subscriber token")
        SecureCredentialStore.put(context, SUBSCRIBER_TOKEN_KEY, token)
        return account(context)
    }

    fun login(context: Context, identifier: String, password: String): SubscriberAccount {
        val body = form(
            "identifier" to identifier.trim(),
            "password" to password,
            "device_label" to "Android ${Build.MODEL}"
        )
        val json = request("/subscriber-login.php", "POST", body, null)
        val token = json.optString("token")
        if (token.isBlank()) throw IllegalStateException("TaraSec login did not return a subscriber token")
        SecureCredentialStore.put(context, SUBSCRIBER_TOKEN_KEY, token)
        return account(context)
    }

    fun account(context: Context): SubscriberAccount {
        val token = storedToken(context) ?: throw IllegalStateException("Not signed in")
        val json = request("/subscriber-account.php", "GET", null, token)
        val sessionsJson = json.optJSONArray("sessions")
        val usages = buildList {
            if (sessionsJson != null) {
                for (i in 0 until sessionsJson.length()) {
                    val s = sessionsJson.getJSONObject(i)
                    add(
                        SubscriberUsage(
                            sessionId = s.optLong("session_id"),
                            hotspot = s.optString("hotspot", "TaraSec hotspot"),
                            countryCode = s.optString("country_code").takeIf { it.isNotBlank() && it != "null" },
                            priceLabel = s.optString("price_label").takeIf { it.isNotBlank() && it != "null" },
                            priceCreditsPerMiB = s.optString("price_credits_per_mib", "0"),
                            startedAt = s.optString("started_at", ""),
                            endedAt = s.optString("ended_at").takeIf { it.isNotBlank() && it != "null" },
                            mib = s.optString("mib", "0"),
                            chargedCredits = s.optString("charged_credits", "0")
                        )
                    )
                }
            }
        }
        val facilityJson = json.optJSONObject("credit_facility")
        val facility = SubscriberCreditFacility(
            status = facilityJson?.optString("status", "disabled") ?: "disabled",
            creditLimitCredits = facilityJson?.optString("credit_limit_credits", "0") ?: "0",
            debtCredits = facilityJson?.optString("debt_credits", "0") ?: "0",
            availableCredit = facilityJson?.optString("available_credit", "0") ?: "0",
            drawEnabled = facilityJson?.optBoolean("draw_enabled", false) == true
        )
        return SubscriberAccount(
            customerId = json.optLong("customer_id"),
            email = json.optString("email").takeIf { it.isNotBlank() && it != "null" },
            phone = json.optString("phone").takeIf { it.isNotBlank() && it != "null" },
            balanceCredits = json.optString("balance_credits", "0"),
            creditFacility = facility,
            paymentEnabled = json.optJSONObject("payment")?.optBoolean("enabled", false) == true,
            usages = usages
        )
    }

    fun activateCurrentHotspot(context: Context): HotspotActivationResult {
        val token = storedToken(context) ?: throw IllegalStateException("Not signed in")
        val gatewayBase = LocalGateway.baseUrl(context)
            ?: throw IllegalStateException("No connected Wi-Fi gateway detected")
        val gatewayHost = URL(gatewayBase).host
        if (gatewayHost.isBlank()) throw IllegalStateException("Invalid Wi-Fi gateway")
        val localBase = "http://$gatewayHost:8080/hotspot"
        val identity = requestAbsolute("$localBase/tarasec_identity.php", "GET", null, null)
        val gatewayKey = identity.optString("gateway_key").trim()
        if (gatewayKey.isBlank()) throw IllegalStateException("This hotspot is not registered for global TaraSec access")
        val grant = request(
            "/device-bind-code.php",
            "POST",
            form("gateway_key" to gatewayKey),
            token
        )
        val code = grant.optString("code").trim()
        if (code.isBlank()) throw IllegalStateException("TaraSec did not issue a hotspot activation code")
        requestAbsolute(
            "$localBase/portal_global_bind.php",
            "POST",
            form("code" to code),
            null
        )
        val internetAvailable = waitForWifiInternet(context)
        return HotspotActivationResult(account(context), internetAvailable)
    }

    private fun waitForWifiInternet(context: Context): Boolean {
        repeat(6) { attempt ->
            if (checkWifiInternet(context)) return true
            if (attempt < 5) Thread.sleep(2000)
        }
        return false
    }

    fun currentWifiIsTaraSecHotspot(context: Context): Boolean {
        val gatewayBase = LocalGateway.baseUrl(context) ?: return false
        val gatewayHost = runCatching { URL(gatewayBase).host }.getOrNull().orEmpty()
        if (gatewayHost.isBlank()) return false
        return runCatching {
            val identity = requestAbsolute(
                "http://$gatewayHost:8080/hotspot/tarasec_identity.php",
                "GET",
                null,
                null
            )
            identity.optString("role") == "tarasec-hotspot" ||
                identity.optString("service") == "tarasec"
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun nearbyTaraSecWifiIsVisible(context: Context): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        return try {
            wifi.scanResults.any { result ->
                result.SSID.trim().startsWith("TaraSec", ignoreCase = true)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun checkWifiInternet(context: Context): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val wifi = connectivity.activeNetwork?.takeIf { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false

        val capabilities = connectivity.getNetworkCapabilities(wifi)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
            return true
        }

        // HTTPS cannot be replaced by a captive-portal page without failing
        // certificate validation. Multiple independent destinations avoid a
        // false negative when one provider's connectivity endpoint is blocked.
        val probes = listOf(
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        for (probe in probes) {
            var connection: HttpURLConnection? = null
            try {
                connection = wifi.openConnection(URL(probe)) as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.useCaches = false
                if (connection.responseCode in 200..299) return true
            } catch (_: Exception) {
                // Try the other independent endpoint.
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    fun drawCredit(context: Context, amountCredits: String): SubscriberAccount {
        val token = storedToken(context) ?: throw IllegalStateException("Not signed in")
        request(
            "/subscriber-credit-draw.php",
            "POST",
            form("amount_credits" to amountCredits.trim()),
            token
        )
        return account(context)
    }

    private fun request(path: String, method: String, body: String?, token: String?): JSONObject =
        requestAbsolute(SUBSCRIBER_API_BASE + path, method, body, token)

    private fun requestAbsolute(url: String, method: String, body: String?, token: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("X-TaraSec-Subscriber-Token", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("TaraSec subscriber service returned HTTP $code")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                val reason = json.optString("reason", "HTTP $code")
                throw IllegalStateException(reason.replace('_', ' '))
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun form(vararg values: Pair<String, String>): String = values.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }
}
