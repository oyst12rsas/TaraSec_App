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
    val message: String = "",
    val tarasecIdentified: Boolean = false,
    val httpResponded: Boolean = false
)

data class DemoGatewayConfiguration(
    val reachable: Boolean,
    val gatewayName: String,
    val nodes: List<DemoTarget>,
    val configured: Boolean,
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
        DemoTarget("Porsche", "100.68.187.10"),
        DemoTarget("Roquefort", "100.68.176.110"),
        DemoTarget("Camembert", "100.68.149.164"),
        DemoTarget("Gouda", "100.68.51.247")
    )

    fun probe(target: DemoTarget): DemoProbeResult {
        var responded = false
        var lastError = "No HTTP response"
        // The identity API is preferred. Older installations may expose only
        // Gatekeeper; an arbitrary HTTP 200/404 is not TaraSec identity.
        for (path in listOf("script/appNode.php", "gatekeeper/index.php")) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("http://${target.ip}/$path").openConnection() as HttpURLConnection
                connection.connectTimeout = 2500
                connection.readTimeout = 3500
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                responded = true
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText().take(131072) }.orEmpty()
                lastError = "HTTP $code"
                if (path == "script/appNode.php") {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (code in 200..299 && json?.optBoolean("ok", false) == true &&
                        json.optString("role") == "tarasec-node") {
                        return DemoProbeResult(target, true,
                            json.optString("name").ifBlank { target.name },
                            "TaraSec node reachable", true, true)
                    }
                } else if (code in 200..299 &&
                    (body.contains("<meta name=\"tarasec-product\" content=\"gatekeeper\">", ignoreCase = true) ||
                        (body.contains("This script is made for version", ignoreCase = true) &&
                            body.contains("Your database is version", ignoreCase = true) &&
                            body.contains("\$nRequiredDbVersion")) ||
                        body.contains("Taransvar Gatekeeper", ignoreCase = true) ||
                        (body.contains("bGatekeeperAdmin") && body.contains("gatekeeper.js")))) {
                    return DemoProbeResult(target, true, target.name,
                        "TaraSec Gatekeeper detected; checking demo API", true, true)
                }
            } catch (error: Exception) {
                lastError = error.message ?: "Connection failed"
            } finally {
                connection?.disconnect()
            }
        }
        return DemoProbeResult(target, responded, message = if (responded)
            "HTTP service responds, but TaraSec identity was not verified"
        else "Endpoint HTTP service unreachable: $lastError",
            tarasecIdentified = false, httpResponded = responded)
    }

    fun endpointProblem(identity: DemoProbeResult?, status: DemoThreatStatus?): String = when {
        identity?.httpResponded != true && (status?.httpCode ?: 0) == 0 ->
            "Endpoint unreachable: no HTTP response. Check the IP, power, VPN, routing or firewall."
        identity?.tarasecIdentified != true && status?.reachable != true ->
            "Host responds, but TaraSec was not identified. Check the address and TaraSec installation."
        status?.httpCode == 404 || status?.httpCode == 410 ->
            "TaraSec detected, but the Demo 1 infection API is missing. Deploy or update the demo APIs on this node."
        status?.reachable != true ->
            "TaraSec detected, but its Demo 1 API is unavailable: ${status?.message.orEmpty()}. Check the service and configuration."
        else -> ""
    }

    fun gatewayConfigurationBase(baseUrl: String): DemoGatewayConfiguration {
        var c: HttpURLConnection? = null
        return try {
            val base = normaliseBase(baseUrl)
            c = URL("$base/script/appDemoConfiguration.php").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                DemoGatewayConfiguration(false, "", emptyList(), false, "HTTP $code")
            } else {
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    DemoGatewayConfiguration(false, "", emptyList(), false, json.optString("error", "Invalid gateway response"))
                } else {
                    val nodesJson = json.optJSONArray("nodes")
                    val nodes = buildList {
                        if (nodesJson != null) {
                            for (i in 0 until nodesJson.length()) {
                                val item = nodesJson.optJSONObject(i) ?: continue
                                val ip = item.optString("address", "").trim()
                                if (ip.isBlank()) continue
                                val configuredName = item.optString("name", "").trim()
                                val knownName = presets.firstOrNull { it.ip == ip }?.name
                                add(DemoTarget(configuredName.ifBlank { knownName ?: ip }, ip))
                            }
                        }
                    }
                    val selectedDemo1 = json.optJSONObject("selection")
                        ?.optJSONObject("demo1")
                        ?.let {
                            val ip = it.optString("address", "").trim()
                            if (ip.isBlank()) null else DemoTarget(
                                it.optString("name", "").trim().ifBlank {
                                    presets.firstOrNull { preset -> preset.ip == ip }?.name ?: ip
                                },
                                ip
                            )
                        }
                    val presentedNodes = buildList {
                        if (selectedDemo1 != null) add(selectedDemo1)
                        nodes.forEach { node -> if (none { it.ip == node.ip }) add(node) }
                    }
                    DemoGatewayConfiguration(
                        reachable = true,
                        gatewayName = json.optString("gateway", "").trim(),
                        nodes = presentedNodes,
                        configured = json.optBoolean("configured", false)
                    )
                }
            }
        } catch (e: Exception) {
            DemoGatewayConfiguration(false, "", emptyList(), false, e.message ?: "Gateway configuration failed")
        } finally {
            c?.disconnect()
        }
    }

    fun probeBase(baseUrl: String, fallbackName: String = "TaraSec gateway"): DemoProbeResult {
        val base = normaliseBase(baseUrl)
        val target = DemoTarget(fallbackName, runCatching { URL(base).host }.getOrDefault(base))
        var c: HttpURLConnection? = null
        return try {
            c = URL("$base/script/appNode.php").openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 3500
            c.useCaches = false
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                DemoProbeResult(target, false, fallbackName, "HTTP $code")
            } else {
                val json = JSONObject(body)
                val name = json.optString("name", "").trim().ifBlank { fallbackName }
                DemoProbeResult(target, true, name, "TaraSec node reachable")
            }
        } catch (e: Exception) {
            DemoProbeResult(target, false, fallbackName, e.message ?: "Identity check failed")
        } finally {
            c?.disconnect()
        }
    }

    fun threatStatus(target: DemoTarget): DemoThreatStatus = threatStatusBase("http://${target.ip}")

    fun threatStatusBase(baseUrl: String): DemoThreatStatus =
        readThreatStatus(baseUrl, "appInfection.php")

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
            val body = "infected=" + URLEncoder.encode(if (infected) "1" else "0", Charsets.UTF_8.name()) + "&demo=1"
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
