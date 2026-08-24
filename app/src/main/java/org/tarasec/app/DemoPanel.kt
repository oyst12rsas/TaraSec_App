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
import java.net.Inet4Address
import java.net.InetAddress

@Composable
fun DemoPanel(gatewayName: String?, gatewayBaseUrl: String?) {
    val activity = LocalContext.current as Activity
    var target by remember { mutableStateOf(DemoClient.presets.first()) }
    var targetIp by remember { mutableStateOf(target.ip) }
    var discoveredName by remember { mutableStateOf(target.name) }
    var gatewayState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose a destination and check the TaraSec path.") }

    fun validIpv4(value: String): Boolean = try {
        val a = InetAddress.getByName(value.trim())
        a is Inet4Address && a.hostAddress == value.trim()
    } catch (_: Exception) { false }

    fun currentTarget(): DemoTarget = DemoTarget(
        DemoClient.presets.firstOrNull { it.ip == targetIp.trim() }?.name ?: "Custom node",
        targetIp.trim()
    )

    fun refresh(after: String = "Status updated") {
        if (busy) return
        val t = currentTarget()
        if (!validIpv4(t.ip)) { message = "Enter a valid IPv4 address for the receiving node."; return }
        busy = true
        Thread {
            val identity = DemoClient.probe(t)
            val gateway = gatewayBaseUrl?.takeIf { it.isNotBlank() }?.let { DemoClient.threatStatusBase(it) }
            val receiver = DemoClient.threatStatus(t)
            activity.runOnUiThread {
                discoveredName = identity.nodeName
                gatewayState = gateway
                receiverState = receiver
                message = if (identity.reachable) after else "Destination reached poorly: ${identity.message}"
                busy = false
            }
        }.start()
    }

    fun infect() {
        if (busy) return
        val base = gatewayBaseUrl?.takeIf { it.isNotBlank() }
        if (base == null) {
            message = "The gateway must be registered in the app before it can mark this unit infected."
            return
        }
        val t = currentTarget()
        if (!validIpv4(t.ip)) { message = "Enter a valid IPv4 address for the receiving node."; return }
        busy = true
        message = "Registering this unit as infected through the TaraSec gateway…"
        Thread {
            val result = DemoClient.markInfected(base)
            try { Thread.sleep(3000L) } catch (_: InterruptedException) { }
            val identity = DemoClient.probe(t)
            val gateway = DemoClient.threatStatusBase(base)
            val receiver = DemoClient.threatStatus(t)
            activity.runOnUiThread {
                discoveredName = identity.nodeName
                gatewayState = gateway
                receiverState = receiver
                message = result
                busy = false
            }
        }.start()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("TaraSec Demo", style = MaterialTheme.typography.titleLarge)
        Text("WireGuard determines the route. Every displayed infection state is fetched from getTagData() on the node whose status is being shown.", style = MaterialTheme.typography.bodySmall)

        Text("Receiving node", style = MaterialTheme.typography.titleMedium)
        DemoClient.presets.forEach { preset ->
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                target = preset; targetIp = preset.ip; discoveredName = preset.name
                gatewayState = null; receiverState = null
            }) { Text((if (preset.ip == targetIp) "✓ " else "") + "${preset.name} · ${preset.ip}") }
        }

        OutlinedTextField(
            value = targetIp,
            onValueChange = {
                targetIp = it.filter { c -> c.isDigit() || c == '.' }
                target = currentTarget(); discoveredName = target.name
                gatewayState = null; receiverState = null
            },
            label = { Text("Other TaraSec node IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Text("Path", style = MaterialTheme.typography.titleMedium)
        Text("Phone → ${gatewayName ?: "gateway selected by WireGuard"} → $discoveredName")
        Text("Destination: ${targetIp.trim()}", style = MaterialTheme.typography.bodySmall)

        gatewayState?.let { state ->
            TaraSectionCard(title = gatewayName ?: "Gateway", subtitle = "getTagData() on gateway") {
                TaraStatusRow("Reachable", if (state.reachable) "Yes" else "No")
                if (state.reachable) {
                    TaraStatusRow("Unit state", if (state.infected) "🔴 INFECTED" else "🟢 CLEAN")
                    TaraStatusRow("Severity", state.severity.toString())
                    if (state.source.isNotBlank()) TaraStatusRow("Evidence", state.source)
                } else if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        receiverState?.let { state ->
            TaraSectionCard(title = discoveredName, subtitle = "getTagData() on ${targetIp.trim()}") {
                TaraStatusRow("Reachable", if (state.reachable) "Yes" else "No")
                if (state.reachable) {
                    TaraStatusRow("Reports this unit", if (state.infected) "🔴 INFECTED" else "🟢 CLEAN")
                    TaraStatusRow("Severity", state.severity.toString())
                    if (state.publicIp.isNotBlank()) TaraStatusRow("Observed source", "${state.publicIp}:${state.publicPort}")
                    if (state.source.isNotBlank()) TaraStatusRow("Evidence", state.source)
                } else if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !busy, onClick = { refresh() }) { Text(if (busy) "Working…" else "Check") }
            Button(enabled = !busy, onClick = { infect() }) { Text("Mark infected") }
        }

        Text(message, style = MaterialTheme.typography.bodySmall)
        Text("The app does not calculate or override TaraSec status. Visiting the same node in a browser and checking it here should therefore give the same assessment.", style = MaterialTheme.typography.bodySmall)
    }
}
