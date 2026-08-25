package org.tarasec.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PaymentBackendStatus(
    val reachable: Boolean,
    val configured: Boolean,
    val provider: String,
    val environment: String,
    val methods: List<String>,
    val message: String = ""
)

object PaymentClient {
    fun status(baseUrl: String): PaymentBackendStatus {
        var c: HttpURLConnection? = null
        return try {
            val base = InstallationStore.normaliseBaseUrl(baseUrl)
            c = URL("$base/script/paymentSession.php").openConnection() as HttpURLConnection
            c.connectTimeout = 3500
            c.readTimeout = 5000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return PaymentBackendStatus(false, false, "", "", emptyList(), "Non-JSON payment response")

            val arr = json.optJSONArray("methods") ?: JSONArray()
            val methods = buildList {
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
            PaymentBackendStatus(
                reachable = true,
                configured = json.optBoolean("configured", false),
                provider = json.optString("provider", ""),
                environment = json.optString("environment", ""),
                methods = methods,
                message = json.optString("error", if (code in 200..299) "" else "HTTP $code")
            )
        } catch (e: Exception) {
            PaymentBackendStatus(false, false, "", "", emptyList(), e.message ?: "Payment backend unavailable")
        } finally {
            c?.disconnect()
        }
    }
}
