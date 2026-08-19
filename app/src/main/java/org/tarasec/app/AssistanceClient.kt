package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AssistanceRequestItem(
    val id: Int,
    val ip: String,
    val port: Int,
    val category: String,
    val threshold: Int,
    val handled: Boolean,
    val sentPartners: Boolean,
    val comment: String
)

object AssistanceClient {
    fun create(
        gatewayBaseUrl: String,
        ip: String,
        port: Int,
        threshold: Int,
        category: String = "bruteForce"
    ): AssistanceRequestItem {
        val c = URL("${gatewayBaseUrl.trimEnd('/')}/script/appAssistance.php").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.useCaches = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = listOf(
                "action" to "create",
                "ip" to ip,
                "port" to port.toString(),
                "threshold" to threshold.toString(),
                "category" to category
            ).joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, Charsets.UTF_8.name())}=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
            }
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            return parseOne(readJson(c))
        } finally {
            c.disconnect()
        }
    }

    fun list(gatewayBaseUrl: String): List<AssistanceRequestItem> {
        val c = URL("${gatewayBaseUrl.trimEnd('/')}/script/appAssistance.php?action=list").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.useCaches = false
            val json = readJson(c)
            val arr = json.optJSONArray("requests") ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) add(parseOne(arr.getJSONObject(i)))
            }
        } finally {
            c.disconnect()
        }
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (body.isBlank()) JSONObject() else JSONObject(body)
        if (code !in 200..299 || !json.optBoolean("ok", false)) {
            val error = json.optString("error", "HTTP $code")
            throw IllegalStateException(when (error) {
                "manager_auth_required" -> "Manager login is required before requesting assistance."
                "invalid_ip" -> "Enter a valid IPv4 address."
                "invalid_port" -> "Port must be between 0 and 65535."
                "invalid_threshold" -> "Threat threshold must be between 0 and 10."
                "assistance_unavailable" -> "Assistance requests are not available on this gateway."
                else -> "Assistance request failed: $error"
            })
        }
        return json
    }

    private fun parseOne(json: JSONObject) = AssistanceRequestItem(
        id = json.optInt("assistanceRequestId"),
        ip = json.optString("ip", ""),
        port = json.optInt("port", 0),
        category = json.optString("category", ""),
        threshold = json.optInt("threshold", 0),
        handled = json.optBoolean("handled", false),
        sentPartners = json.optBoolean("sentPartners", false),
        comment = json.optString("comment", "")
    )
}
