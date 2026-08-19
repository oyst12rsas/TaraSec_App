package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ManagedGatewayStatus(
    val name: String,
    val reachable: Boolean,
    val serverTime: String,
    val managerEmail: String,
    val requestId: Int,
    val assistance: Boolean,
    val threats: Boolean,
    val units: Boolean,
    val notifications: Boolean
)

object GatewayManagerClient {
    fun status(baseUrl: String): ManagedGatewayStatus {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://", true) || base.startsWith("https://", true)) {
            "Gateway URL must start with http:// or https://."
        }

        val connection = URL("$base/script/managerGateway.php?action=status")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)

            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                val error = json.optString("error", "HTTP $code")
                throw IllegalStateException(
                    when (error) {
                        "manager_login_required" -> "Manager login is required on this gateway."
                        "manager_access_revoked" -> "Manager access has been revoked or expired."
                        "manager_gateway_unavailable" -> "Gateway manager status is temporarily unavailable."
                        else -> "Gateway manager request failed: $error"
                    }
                )
            }

            val gateway = json.getJSONObject("gateway")
            val manager = json.getJSONObject("manager")
            val capabilities = json.optJSONObject("capabilities") ?: JSONObject()

            return ManagedGatewayStatus(
                name = gateway.optString("name", "TaraSec gateway"),
                reachable = gateway.optBoolean("reachable", false),
                serverTime = gateway.optString("serverTime", ""),
                managerEmail = manager.optString("email", ""),
                requestId = manager.optInt("requestId", 0),
                assistance = capabilities.optBoolean("assistance", false),
                threats = capabilities.optBoolean("threats", false),
                units = capabilities.optBoolean("units", false),
                notifications = capabilities.optBoolean("notifications", false)
            )
        } finally {
            connection.disconnect()
        }
    }
}
