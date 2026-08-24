package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

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
    val message: String = ""
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

    // Status is always evaluated remotely. appInfection.php is deliberately a
    // JSON view of that node's getTagData(), so Android displays the same
    // assessment as visiting the node in a browser.
    fun threatStatus(target: DemoTarget): DemoThreatStatus = threatStatusBase("http://${target.ip}")

    fun threatStatusBase(baseUrl: String): DemoThreatStatus {
        var c: HttpURLConnection? = null
        try {
            val base = normaliseBase(baseUrl)
            c = URL("$base/script/appInfection.php").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return DemoThreatStatus(false, false, 0, null, "", 0, "none", "HTTP $code")
            val json = JSONObject(body)
            return DemoThreatStatus(
                reachable = json.optBoolean("ok", false),
                infected = json.optBoolean("infected", false),
                severity = json.optInt("severity", 0),
                unitId = null,
                publicIp = json.optString("client_ip", ""),
                publicPort = json.optInt("client_port", 0),
                source = json.optString("source", "none"),
                message = json.optString("error", "")
            )
        } catch (e: Exception) {
            return DemoThreatStatus(false, false, 0, null, "", 0, "none", e.message ?: "Status failed")
        } finally {
            c?.disconnect()
        }
    }

    // Use the existing TaraSec demo mechanism rather than inventing an Android
    // database mutation. The request is sent to the gateway; reportHacking()
    // then exercises the normal report -> gateway infection -> tagging path.
    fun markInfected(gatewayBaseUrl: String): String {
        var c: HttpURLConnection? = null
        return try {
            val base = normaliseBase(gatewayBaseUrl)
            c = URL("$base/gatekeeper/index.php?f=selfRegInfected&conf=1").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 7000
            c.useCaches = false
            val code = c.responseCode
            if (code in 200..299) "Infection reported through TaraSec gateway" else "Gateway returned HTTP $code"
        } catch (e: Exception) {
            "Could not report infection: ${e.message ?: "unknown error"}"
        } finally {
            c?.disconnect()
        }
    }

    private fun normaliseBase(value: String): String {
        val v = value.trim().trimEnd('/')
        return if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "http://$v"
    }
}
