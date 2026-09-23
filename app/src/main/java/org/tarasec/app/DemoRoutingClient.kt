package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

data class Demo4Route(
    val partnerName: String,
    val destinationIp: String,
    val netmask: String,
    val taggedTrafficRoute: String,
    val updatedAt: String?,
    val selected: Boolean,
    val applyState: String,
    val applyMessage: String,
    val reportedAt: String?
)

data class Demo4RouteStatus(
    val reachable: Boolean,
    val sourceIp: String,
    val mode: String,
    val routes: List<Demo4Route>,
    val message: String
)

data class Demo4ProbeResult(val reachable: Boolean, val detail: String)

object DemoRoutingClient {
    fun websiteAddresses(): List<String> = runCatching {
        InetAddress.getAllByName("tarasec.org")
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    fun recordObservation(sessionId: String, writerKey: String, phase: String): Demo4ProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("https://tarasec.org/demo4/observe.php?action=record")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = "id=$sessionId&key=$writerKey&phase=$phase"
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            if (code in 200..299 && json.optBoolean("ok", false)) {
                Demo4ProbeResult(true, "TaraSec.org observed ${json.optString("observedIp", "unknown")}")
            } else {
                Demo4ProbeResult(false, json.optString("error", "HTTP $code"))
            }
        } catch (e: Exception) {
            Demo4ProbeResult(false, e.message ?: "Website observation failed")
        } finally {
            connection?.disconnect()
        }
    }

    fun routes(baseUrl: String): Demo4RouteStatus {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(baseUrl.trimEnd('/') + "/script/appDemo4.php")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                return Demo4RouteStatus(
                    false, "", "", emptyList(),
                    json.optString("error", "HTTP $code")
                )
            }

            val result = mutableListOf<Demo4Route>()
            val routes = json.optJSONArray("routes")
            if (routes != null) {
                for (i in 0 until routes.length()) {
                    val route = routes.getJSONObject(i)
                    result += Demo4Route(
                        partnerName = route.optString("partnerName"),
                        destinationIp = route.optString("destinationIp"),
                        netmask = route.optString("netmask"),
                        taggedTrafficRoute = route.optString("taggedTrafficRoute"),
                        updatedAt = route.optString("updatedAt").takeIf { it.isNotBlank() && it != "null" },
                        selected = route.optBoolean("selected", false),
                        applyState = route.optString("applyState", "configured"),
                        applyMessage = route.optString("applyMessage", ""),
                        reportedAt = route.optString("reportedAt").takeIf { it.isNotBlank() && it != "null" }
                    )
                }
            }

            Demo4RouteStatus(
                reachable = true,
                sourceIp = json.optString("sourceIp"),
                mode = json.optString("mode"),
                routes = result,
                message = if (result.isEmpty()) {
                    "The DB server is reachable, but no Demo 4 tagged routes are configured."
                } else {
                    "${result.size} Demo 4 tagged route${if (result.size == 1) "" else "s"} available."
                }
            )
        } catch (e: Exception) {
            Demo4RouteStatus(false, "", "", emptyList(), e.message ?: "Demo 4 route check failed")
        } finally {
            connection?.disconnect()
        }
    }
}
