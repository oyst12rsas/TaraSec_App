package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DemoTarget(val name: String, val ip: String)

data class DemoProbeResult(
    val target: DemoTarget,
    val reachable: Boolean,
    val nodeName: String = target.name,
    val message: String = ""
)

data class DemoThreatStatus(
    val reachable: Boolean,
    val infected: Boolean,
    val severity: Int,
    val unitId: Int?,
    val publicIp: String,
    val publicPort: Int,
    val source: String,
    val message: String = "",
    val polledAt: String = "",
    val endpoint: String = "",
    val httpCode: Int = 0,
    val rawJson: String = ""
)

object DemoClient {
    val presets = listOf(
        DemoTarget("Tomato", "100.68.22.33"),
        DemoTarget("Roquefort", "100.68.176.110"),
        DemoTarget("Camembert", "100.68.149.164"),
        DemoTarget("Gouda", "100.68.51.247")
    )

    fun probe(target: DemoTarget): DemoProbeResult {
        var c: HttpURLConnection? = null
        try {
            c = URL("http://${target.ip}/script/appNode.php").openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 3500
            c.useCaches = false
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                val discovered = json?.optString("name", "")?.takeIf { it.isNotBlank() } ?: target.name
                return DemoProbeResult(target, true, discovered, "TaraSec node reachable")
            }
            return DemoProbeResult(target, false, message = "HTTP $code")
        } catch (e: Exception) {
            return try {
                Socket().use { socket -> socket.connect(InetSocketAddress(target.ip, 80), 1500) }
                DemoProbeResult(target, true, message = "Host reachable; TaraSec identity unavailable")
            } catch (_: Exception) {
                DemoProbeResult(target, false, message = e.message ?: "Unreachable")
            }
        } finally {
            c?.disconnect()
        }
    }

    fun threatStatus(target: DemoTarget): DemoThreatStatus = threatStatusBase("http://${target.ip}")

    fun threatStatusBase(baseUrl: String): DemoThreatStatus =
        readThreatStatus(baseUrl, "appInfection.php")

    // For the phone itself, read the gateway's direct internalInfections row.
    // Do not let a fresh severity-0 traffic record override the explicit local
    // Clean/Infected toggle. Remote receivers still use appInfection.php/getTagData().
    fun localThreatStatusBase(baseUrl: String): DemoThreatStatus =
        readThreatStatus(baseUrl, "appLocalInfection.php")

    private fun readThreatStatus(baseUrl: String, script: String): DemoThreatStatus {
        var c: HttpURLConnection? = null
        val polledAt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val base = normaliseBase(baseUrl)
        val endpoint = "$base/script/$script"
        try {
            c = URL(endpoint).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return DemoThreatStatus(false, false, 0, null, "", 0, "none", "HTTP $code", polledAt, endpoint, code, body)
            }
            val json = JSONObject(body)
            return DemoThreatStatus(
                reachable = json.optBoolean("ok", false),
                infected = json.optBoolean("infected", false),
                severity = json.optInt("severity", 0),
                unitId = null,
                publicIp = json.optString("client_ip", ""),
                publicPort = json.optInt("client_port", 0),
                source = json.optString("source", "none"),
                message = json.optString("error", ""),
                polledAt = polledAt,
                endpoint = endpoint,
                httpCode = code,
                rawJson = body
            )
        } catch (e: Exception) {
            return DemoThreatStatus(false, false, 0, null, "", 0, "none", e.message ?: "Status failed", polledAt, endpoint, 0, "")
        } finally {
            c?.disconnect()
        }
    }

    fun setGatewayInfected(gatewayBaseUrl: String, infected: Boolean): String {
        var c: HttpURLConnection? = null
        return try {
            val base = normaliseBase(gatewayBaseUrl)
            c = URL("$base/script/appInfectionControl.php").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 7000
            c.useCaches = false
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("Accept", "application/json")
            val body = "infected=" + URLEncoder.encode(if (infected) "1" else "0", Charsets.UTF_8.name())
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val reply = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(reply) }.getOrNull()
            if (code in 200..299 && json?.optBoolean("ok", false) == true) {
                json.optString("message", if (infected) "Infection requested" else "Clean requested")
            } else {
                json?.optString("error", "Gateway returned HTTP $code") ?: "Gateway returned HTTP $code"
            }
        } catch (e: Exception) {
            "Could not change gateway state: ${e.message ?: "unknown error"}"
        } finally {
            c?.disconnect()
        }
    }

    private fun normaliseBase(value: String): String {
        val v = value.trim().trimEnd('/')
        return if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "http://$v"
    }
}
