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

object DemoClient {
    val presets = listOf(
        DemoTarget("Tomato", "100.68.22.33"),
        DemoTarget("Roquefort", "100.68.176.110"),
        DemoTarget("Camembert", "100.68.149.164"),
        DemoTarget("Gouda", "100.68.51.247")
    )

    fun probe(target: DemoTarget): DemoProbeResult {
        // Do not select a gateway here. Android/WireGuard routing decides the path.
        // First try the TaraSec HTTP endpoint; fall back to a TCP reachability probe.
        var c: HttpURLConnection? = null
        try {
            c = URL("http://${target.ip}/script/appInfection.php").openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 3500
            c.useCaches = false
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                val discovered = json?.optString("node", "")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("hostname", "")?.takeIf { it.isNotBlank() }
                    ?: target.name
                return DemoProbeResult(target, true, discovered, "TaraSec reachable")
            }
            return DemoProbeResult(target, false, message = "HTTP $code")
        } catch (e: Exception) {
            return try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.ip, 80), 1500)
                }
                DemoProbeResult(target, true, message = "Node reachable")
            } catch (_: Exception) {
                DemoProbeResult(target, false, message = e.message ?: "Unreachable")
            }
        } finally {
            c?.disconnect()
        }
    }
}
