package org.tarasec.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun DemoPanel(gatewayName: String?, gatewayBaseUrl: String?, showDebugInfo: Boolean = false) {
    val activity = LocalContext.current as Activity
    val localGatewayBase = remember { LocalGateway.baseUrl(activity) }

    val selectedInstallation = remember {
        val items = InstallationStore.load(activity)
        val selectedId = InstallationStore.selectedId(activity)
        items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
    }
    val selectedGatewayName = selectedInstallation?.name
        ?: gatewayName?.takeIf { it.isNotBlank() }
        ?: "Squash"
    val selectedServiceIp = selectedInstallation?.serviceIp?.trim()?.takeIf { it.isNotBlank() }
    val selectedServiceBase = selectedServiceIp?.let {
        if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it"
    }

    var target by remember { mutableStateOf(DemoClient.presets.first()) }
    var targetIp by remember { mutableStateOf(target.ip) }
    var discoveredName by remember { mutableStateOf(target.name) }

    var localPhoneState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var vpnGatewayState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var vpnPhoneState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverProbe by remember { mutableStateOf<DemoProbeResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("The Clean/Infected control always refers to this phone. TaraSec chooses the active gateway identity automatically.") }

    fun validIpv4(value: String): Boolean = try {
        val a = InetAddress.getByName(value.trim())
        a is Inet4Address && a.hostAddress == value.trim()
    } catch (_: Exception) { false }

    fun currentTarget() = DemoTarget(
        DemoClient.presets.firstOrNull { it.ip == targetIp.trim() }?.name ?: "Custom node",
        targetIp.trim()
    )

    fun selectedVpnActive(): Boolean = vpnGatewayState?.reachable == true

    fun activePhoneState(): DemoThreatStatus? =
        if (selectedVpnActive() && vpnPhoneState?.reachable == true) vpnPhoneState else localPhoneState

    fun activeControlBase(): String? =
        if (selectedVpnActive() && selectedServiceBase != null) selectedServiceBase else localGatewayBase

    fun activeGatewayLabel(): String =
        if (selectedVpnActive()) selectedGatewayName else "local Wi-Fi hotspot"

    fun pollAll(after: String? = null) {
        val t = currentTarget()
        if (!validIpv4(t.ip)) {
            activity.runOnUiThread { message = "Enter a valid IPv4 address for the receiving node." }
            return
        }
        val identity = DemoClient.probe(t)
        val local = localGatewayBase?.let { DemoClient.localThreatStatusBase(it) }
        val vpnGateway = selectedServiceBase?.let { DemoClient.threatStatusBase(it) }
        val vpnPhone = if (vpnGateway?.reachable == true && selectedServiceBase != null) {
            DemoClient.localThreatStatusBase(selectedServiceBase)
        } else null
        val receiver = DemoClient.threatStatus(t)
        activity.runOnUiThread {
            receiverProbe = identity
            discoveredName = identity.nodeName
            localPhoneState = local
            vpnGatewayState = vpnGateway
            vpnPhoneState = vpnPhone
            receiverState = receiver
            if (after != null) {
                message = if (identity.reachable) after else "Receiving node unavailable: ${identity.message}"
            }
        }
    }

    fun refresh(after: String = "Status updated") {
        Thread { pollAll(after) }.start()
    }

    fun setPhoneState(infected: Boolean) {
        if (busy) return
        val base = activeControlBase() ?: run {
            message = "No TaraSec gateway is currently available for this phone."
            return
        }
        val gatewayLabel = activeGatewayLabel()
        busy = true
        message = if (infected) {
            "Marking this phone infected through $gatewayLabel…"
        } else {
            "Marking this phone clean through $gatewayLabel…"
        }
        Thread {
            val result = DemoClient.setGatewayInfected(base, infected)
            try { Thread.sleep(if (infected) 700L else 400L) } catch (_: InterruptedException) {}

            val t = currentTarget()
            val identity = if (validIpv4(t.ip)) DemoClient.probe(t) else null
            val local = localGatewayBase?.let { DemoClient.localThreatStatusBase(it) }
            val vpnGateway = selectedServiceBase?.let { DemoClient.threatStatusBase(it) }
            val vpnPhone = if (vpnGateway?.reachable == true && selectedServiceBase != null) {
                DemoClient.localThreatStatusBase(selectedServiceBase)
            } else null
            val receiver = if (validIpv4(t.ip)) DemoClient.threatStatus(t) else null
            val confirmed = if (vpnGateway?.reachable == true && vpnPhone?.reachable == true) vpnPhone else local

            activity.runOnUiThread {
                if (identity != null) {
                    receiverProbe = identity
                    discoveredName = identity.nodeName
                }
                localPhoneState = local
                vpnGatewayState = vpnGateway
                vpnPhoneState = vpnPhone
                if (receiver != null) receiverState = receiver
                message = result + if (confirmed?.infected == infected) {
                    " — $gatewayLabel confirmed this phone"
                } else {
                    " — waiting for $gatewayLabel to confirm this phone"
                }
                busy = false
            }
        }.start()
    }

    DisposableEffect(localGatewayBase, selectedServiceBase, targetIp) {
        val running = AtomicBoolean(true)
        val worker = Thread {
            while (running.get()) {
                pollAll()
                try { Thread.sleep(2500L) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
        onDispose {
            running.set(false)
            worker.interrupt()
        }
    }

    @Composable
    fun debugStatus(title: String, state: DemoThreatStatus) {
        TaraSectionCard(title = "$title debug", subtitle = "Exact app status request") {
            TaraStatusRow("Polled at", state.polledAt.ifBlank { "n/a" })
            TaraStatusRow("HTTP", if (state.httpCode > 0) state.httpCode.toString() else "n/a")
            Text("Endpoint: ${state.endpoint.ifBlank { "n/a" }}", style = MaterialTheme.typography.bodySmall)
            Text("Raw JSON:", style = MaterialTheme.typography.bodySmall)
            Text(state.rawJson.ifBlank { "(no response body)" }, style = MaterialTheme.typography.bodySmall)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("TaraSec Demo", style = MaterialTheme.typography.titleLarge)
        Text(
            "You control one thing: this phone. TaraSec resolves whether its active security identity is on the local hotspot or on the selected VPN gateway.",
            style = MaterialTheme.typography.bodySmall
        )

        TaraSectionCard(title = "Local Wi-Fi hotspot", subtitle = "First hop from this phone") {
            TaraStatusRow("Endpoint", localGatewayBase ?: "not detected")
            TaraStatusRow("Wi-Fi route", if (localGatewayBase != null) "Active" else "Not detected")
            localPhoneState?.let {
                TaraStatusRow("Phone identity", it.publicIp.ifBlank { "unknown" })
                TaraStatusRow("Local state API", if (it.reachable) "Reachable" else "Unavailable")
            }
            Text("This is Cigar while connected to the Cigar hotspot.", style = MaterialTheme.typography.bodySmall)
        }

        TaraSectionCard(title = selectedGatewayName, subtitle = "Selected TaraSec gateway / VPN path") {
            TaraStatusRow("Service IP", selectedServiceIp ?: "not configured")
            val vpnText = when {
                selectedServiceBase == null -> "Gateway selected, but VPN service IP is not configured"
                vpnGatewayState?.reachable == true -> "VPN / gateway path active"
                vpnGatewayState == null -> "Checking selected gateway…"
                else -> "VPN appears to be off — turn on your VPN"
            }
            TaraStatusRow("VPN status", vpnText)
            if (vpnGatewayState?.reachable == true) {
                vpnPhoneState?.let {
                    TaraStatusRow("This phone at $selectedGatewayName", it.publicIp.ifBlank { "identity unavailable" })
                }
            } else if (selectedServiceBase != null && vpnGatewayState?.reachable == false) {
                Text("$selectedGatewayName is selected but cannot currently be reached on its service path. Turn on the VPN if this gateway normally uses it.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Receiving node", style = MaterialTheme.typography.titleMedium)
        DemoClient.presets.forEach { preset ->
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                target = preset
                targetIp = preset.ip
                discoveredName = preset.name
                receiverProbe = null
                receiverState = null
            }) {
                Text((if (preset.ip == targetIp) "✓ " else "") + "${preset.name} · ${preset.ip}")
            }
        }
        OutlinedTextField(
            value = targetIp,
            onValueChange = {
                targetIp = it.filter { c -> c.isDigit() || c == '.' }
                target = currentTarget()
                discoveredName = target.name
                receiverProbe = null
                receiverState = null
            },
            label = { Text("Other TaraSec node IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        TaraSectionCard(title = discoveredName, subtitle = "Receiving TaraSec node") {
            TaraStatusRow("Destination", targetIp.trim())
            TaraStatusRow("Reachable", when (receiverProbe?.reachable) { true -> "Yes"; false -> "No"; null -> "Checking…" })
            receiverState?.let { receiver ->
                if (receiver.reachable) {
                    TaraStatusRow("Observed TaraSec state", if (receiver.infected) "🔴 INFECTED" else "🟢 CLEAN")
                    TaraStatusRow("Severity", receiver.severity.toString())
                    if (receiver.publicIp.isNotBlank()) TaraStatusRow("Observed source", "${receiver.publicIp}:${receiver.publicPort}")
                    if (receiver.source.isNotBlank()) TaraStatusRow("Evidence", receiver.source)
                } else if (receiver.message.isNotBlank()) {
                    Text(receiver.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        TaraSectionCard(
            title = "Self-healing attribution",
            subtitle = "False positives are evidence to correct, not permanent labels"
        ) {
            TaraStatusRow("Lifecycle", "Detect → attribute → reassess → correct")
            TaraStatusRow("Audit rule", "Tagged packet accepted by receiver firewall")
            Text(
                "When a receiving TaraSec firewall accepts traffic that arrived with a threat tag, that acceptance can be sent to the DB server and back to the sender as contradictory audit evidence. One accepted packet does not automatically clear a unit, but repeated normal traffic can reduce confidence and trigger automatic rehabilitation.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "The recovery engine can also compare a recently corrected destination with the earlier entered IP. Similar or transposed addresses, close timing and the same session can support a finding that the original attribution was a human input error.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Demo status: UI concept enabled. Receiver-accept audit reporting and automatic clearing require the matching gateway/DB protocol implementation.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text("Current path", style = MaterialTheme.typography.titleMedium)
        Text(
            if (selectedVpnActive()) {
                "Phone → local Wi-Fi hotspot → $selectedGatewayName (VPN/security gateway) → $discoveredName"
            } else {
                "Phone → local Wi-Fi hotspot → Internet / $discoveredName"
            }
        )

        Text("This phone", style = MaterialTheme.typography.titleMedium)
        val state = activePhoneState()
        val gatewayLabel = activeGatewayLabel()
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.wrapContentHeight()) {
                RadioButton(
                    selected = state?.reachable == true && !state.infected,
                    enabled = activeControlBase() != null && !busy,
                    onClick = { setPhoneState(false) }
                )
                Text("Clean")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.wrapContentHeight()) {
                RadioButton(
                    selected = state?.reachable == true && state.infected,
                    enabled = activeControlBase() != null && !busy,
                    onClick = { setPhoneState(true) }
                )
                Text("Infected")
            }
        }
        Text(
            when {
                state == null -> "Resolving this phone's active TaraSec identity…"
                !state.reachable -> "$gatewayLabel cannot currently read this phone's TaraSec state"
                else -> "$gatewayLabel reports this phone ${if (state.infected) "INFECTED" else "CLEAN"} · severity ${state.severity}" +
                    state.publicIp.takeIf { it.isNotBlank() }?.let { " · identity $it" }.orEmpty()
            },
            style = MaterialTheme.typography.bodySmall
        )

        if (showDebugInfo) {
            localPhoneState?.let { debugStatus("Local phone identity", it) }
            vpnGatewayState?.let { debugStatus(selectedGatewayName, it) }
            vpnPhoneState?.let { debugStatus("VPN phone identity", it) }
            receiverState?.let { debugStatus(discoveredName, it) }
        }

        Button(enabled = !busy, onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Working…" else "Refresh now")
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
