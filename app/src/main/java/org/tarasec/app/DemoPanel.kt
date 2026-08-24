package org.tarasec.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URL

private data class DemoTarget(val name: String, val ip: String)

private data class DemoObservation(
    val reachable: Boolean,
    val infected: Boolean,
    val severity: Int,
    val clientIp: String,
    val unitId: Int?,
    val source: String,
    val error: String = ""
)

private object DemoClient {
    fun status(baseUrl: String): DemoObservation = request(baseUrl, clear = false)

    fun clear(baseUrl: String): DemoObservation = request(baseUrl, clear = true)

    private fun request(baseUrl: String, clear: Boolean): DemoObservation {
        val base = normaliseBase(baseUrl)
        val suffix = if (clear) "?action=clear" else "?action=status"
        var c: HttpURLConnection? = null
        return try {
            c = URL("$base/script/appInfection.php$suffix").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            if (clear) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(ByteArray(0)) }
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                DemoObservation(false, false, 0, "", null, "", json.optString("error", "HTTP $code"))
            } else {
                DemoObservation(
                    reachable = true,
                    infected = json.optBoolean("infected", false),
                    severity = json.optInt("severity", 0),
                    clientIp = json.optString("client_ip", ""),
                    unitId = json.optInt("unitId", 0).takeIf { it > 0 },
                    source = json.optString("source", "")
                )
            }
        } catch (e: Exception) {
            DemoObservation(false, false, 0, "", null, "", e.message ?: e.javaClass.simpleName)
        } finally {
            c?.disconnect()
        }
    }

    fun triggerSinkhole() {
        // A short TCP attempt is enough to exercise the existing TaraSec sinkhole path.
        // No malware or privileged Android networking is involved; failure/timeout is expected.
        try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("10.47.99.99", 22), 1200)
            }
        } catch (_: Exception) {
            // Expected: the sinkhole deliberately does not behave like a normal service.
        }
    }

    fun normaliseIpOrUrl(value: String): String {
        val v = value.trim()
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v.trimEnd('/')
        return "http://$v"
    }

    private fun normaliseBase(value: String): String = normaliseIpOrUrl(value)

    fun validIpv4(value: String): Boolean = try {
        val a = InetAddress.getByName(value.trim())
        a is Inet4Address && a.hostAddress == value.trim()
    } catch (_: Exception) {
        false
    }
}

@Composable
fun DemoPanel(gatewayName: String?, gatewayBaseUrl: String?) {
    val activity = LocalContext.current as Activity
    val presets = remember {
        listOf(
            DemoTarget("Tomato", "100.68.22.33"),
            DemoTarget("Roquefort", "100.68.176.110"),
            DemoTarget("Camembert", "100.68.149.164"),
            DemoTarget("Gouda", "100.68.51.247")
        )
    }

    var targetName by remember { mutableStateOf("Tomato") }
    var targetIp by remember { mutableStateOf("100.68.22.33") }
    var gatewayState by remember { mutableStateOf<DemoObservation?>(null) }
    var receiverState by remember { mutableStateOf<DemoObservation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose a destination and check the TaraSec path.") }

    fun refresh(after: String = "Status updated") {
        if (busy) return
        if (!DemoClient.validIpv4(targetIp)) {
            message = "Enter a valid IPv4 address for the receiving node."
            return
        }
        busy = true
        Thread {
            val gateway = gatewayBaseUrl?.takeIf { it.isNotBlank() }?.let { DemoClient.status(it) }
            val receiver = DemoClient.status(DemoClient.normaliseIpOrUrl(targetIp))
            activity.runOnUiThread {
                gatewayState = gateway
                receiverState = receiver
                message = after
                busy = false
            }
        }.start()
    }

    fun infect() {
        if (busy) return
        if (!DemoClient.validIpv4(targetIp)) {
            message = "Enter a valid IPv4 address for the receiving node."
            return
        }
        busy = true
        message = "Sending a safe sinkhole probe through the active WireGuard/TaraSec route…"
        Thread {
            DemoClient.triggerSinkhole()
            try { Thread.sleep(2500L) } catch (_: InterruptedException) { }
            val gateway = gatewayBaseUrl?.takeIf { it.isNotBlank() }?.let { DemoClient.status(it) }
            val receiver = DemoClient.status(DemoClient.normaliseIpOrUrl(targetIp))
            activity.runOnUiThread {
                gatewayState = gateway
                receiverState = receiver
                message = "Safe test sent. The receiver result is queried independently."
                busy = false
            }
        }.start()
    }

    fun clear() {
        if (busy) return
        if (!DemoClient.validIpv4(targetIp)) {
            message = "Enter a valid IPv4 address for the receiving node."
            return
        }
        busy = true
        message = "Clearing this unit's demo state…"
        Thread {
            val gateway = gatewayBaseUrl?.takeIf { it.isNotBlank() }?.let { DemoClient.clear(it) }
            val receiver = DemoClient.clear(DemoClient.normaliseIpOrUrl(targetIp))
            try { Thread.sleep(800L) } catch (_: InterruptedException) { }
            activity.runOnUiThread {
                gatewayState = gateway
                receiverState = receiver
                message = "Clear requested for this unit only."
                busy = false
            }
        }.start()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("TaraSec Demo", style = MaterialTheme.typography.titleLarge)
        Text(
            "WireGuard defines the route and therefore the gateway. TaraSec only tests the unit state and what an independent receiving node observes.",
            style = MaterialTheme.typography.bodySmall
        )

        Text("Receiving node", style = MaterialTheme.typography.titleMedium)
        presets.forEach { target ->
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { targetName = target.name; targetIp = target.ip }
            ) {
                Text((if (target.ip == targetIp) "✓ " else "") + "${target.name} · ${target.ip}")
            }
        }
        OutlinedTextField(
            value = targetIp,
            onValueChange = {
                targetIp = it.trim()
                targetName = presets.firstOrNull { p -> p.ip == targetIp }?.name ?: "Custom node"
            },
            label = { Text("Other TaraSec node IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Text("Path", style = MaterialTheme.typography.titleMedium)
        Text("Phone → ${gatewayName ?: "gateway selected by WireGuard"} → $targetName")
        if (!gatewayBaseUrl.isNullOrBlank()) {
            Text("Gateway management: $gatewayBaseUrl", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("No gateway management URL is registered in the app. The test traffic still follows the active WireGuard route.", style = MaterialTheme.typography.bodySmall)
        }

        gatewayState?.let { state ->
            TaraSectionCard(title = gatewayName ?: "Gateway", subtitle = "What the local gateway reports for this app/unit") {
                TaraStatusRow("Reachable", if (state.reachable) "Yes" else "No")
                if (state.reachable) {
                    TaraStatusRow("Unit state", if (state.infected) "🔴 INFECTED" else "🟢 CLEAN")
                    TaraStatusRow("Severity", state.severity.toString())
                    state.unitId?.let { TaraStatusRow("Unit ID", it.toString()) }
                    if (state.source.isNotBlank()) TaraStatusRow("Evidence", state.source)
                } else if (state.error.isNotBlank()) Text(state.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        receiverState?.let { state ->
            TaraSectionCard(title = targetName, subtitle = "Independent observation from $targetIp") {
                TaraStatusRow("Reachable", if (state.reachable) "Yes" else "No")
                if (state.reachable) {
                    TaraStatusRow("Reports this unit", if (state.infected) "🔴 INFECTED" else "🟢 CLEAN")
                    TaraStatusRow("Severity", state.severity.toString())
                    if (state.clientIp.isNotBlank()) TaraStatusRow("Observed source", state.clientIp)
                    state.unitId?.let { TaraStatusRow("Resolved unit ID", it.toString()) }
                    if (state.source.isNotBlank()) TaraStatusRow("Evidence", state.source)
                } else if (state.error.isNotBlank()) Text(state.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !busy, onClick = { refresh() }) { Text("Check") }
            Button(enabled = !busy, onClick = { infect() }) { Text("Mark infected") }
        }
        Button(enabled = !busy, onClick = { clear() }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear my demo state")
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
        Text(
            "Each phone is evaluated independently. Multiple testers can use the same gateway at the same time without sharing demo state when unit resolution is working correctly.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
