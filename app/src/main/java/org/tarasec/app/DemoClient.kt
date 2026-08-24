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
        // TaraSec never chooses the gateway here. Android/WireGuard routing decides
        // how the destination is reached. The node only identifies itself.
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
                val discovered = json?.optString("name", "")?.takeIf { it.isNotBlank() }
                    ?: target.name
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

    fun threatStatus(target: DemoTarget): DemoThreatStatus {
        var c: HttpURLConnection? = null
        try {
            c = URL("http://${target.ip}/script/appInfection.php?action=status").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.useCaches = false
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return DemoThreatStatus(false, false, 0, null, "", 0, "none", "HTTP $code")
            val json = JSONObject(body)
            return DemoThreatStatus(
                reachable = json.optBoolean("ok", false),
                infected = json.optBoolean("infected", false),
                severity = json.optInt("severity", 0),
                unitId = if (json.isNull("unitId")) null else json.optInt("unitId"),
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

    fun triggerSinkhole(): String {
        // A connection attempt is enough to exercise the real TaraSec sinkhole path.
        // Whether the socket itself succeeds is irrelevant; the traffic is the test.
        return try {
            Socket().use { socket -> socket.connect(InetSocketAddress("10.47.99.99", 80), 2000) }
            "Safe sinkhole traffic sent"
        } catch (_: Exception) {
            "Safe sinkhole traffic generated; waiting for TaraSec reporting"
        }
    }
}
