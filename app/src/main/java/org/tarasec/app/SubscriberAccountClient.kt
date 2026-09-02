package org.tarasec.app

import android.content.Context
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
