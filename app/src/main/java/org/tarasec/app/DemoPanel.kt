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

    val basicTarget = remember { DemoClient.presets.first() }
    var basicReceiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var basicReceiverProbe by remember { mutableStateOf<DemoProbeResult?>(null) }

    var target by remember { mutableStateOf(DemoClient.presets.first()) }
    var targetIp by remember { mutableStateOf(target.ip) }
    var discoveredName by remember { mutableStateOf(target.name) }

    var localPhoneState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var vpnGatewayState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var vpnPhoneState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverProbe by remember { mutableStateOf<DemoProbeResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var auditApproved by remember { mutableStateOf(false) }
    var showDemo1 by remember { mutableStateOf(true) }
    var showDemo2 by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Demo 1 is ready. Expand Demo 2 when you want to run the SSH self-healing scenario.") }

    fun validIpv4(value: String): Boolean = try {
        val a = InetAddress.getByName(value.trim())
        a is Inet4Address && a.hostAddress == value.trim()
    } catch (_: Exception) {
        false
    }

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
            activity.runOnUiThread { message = "Enter a valid IPv4 address for the Demo 2 receiving node." }
            return
        }

        val basicIdentity = DemoClient.probe(basicTarget)
        val basicReceiver = DemoClient.threatStatus(basicTarget)
        val identity = if (t.ip == basicTarget.ip) basicIdentity else DemoClient.probe(t)
        val receiver = if (t.ip == basicTarget.ip) basicReceiver else DemoClient.threatStatus(t)
        val local = localGatewayBase?.let { DemoClient.localThreatStatusBase(it) }
        val vpnGateway = selectedServiceBase?.let { DemoClient.threatStatusBase(it) }
        val vpnPhone = if (vpnGateway?.reachable == true && selectedServiceBase != null) {
            DemoClient.localThreatStatusBase(selectedServiceBase)
        } else {
            null
        }

        activity.runOnUiThread {
            basicReceiverProbe = basicIdentity
            basicReceiverState = basicReceiver
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
            "Demo 1: marking this phone infected through $gatewayLabel…"
        } else {
            "Demo 1: marking this phone clean through $gatewayLabel…"
        }

        Thread {
            val result = DemoClient.setGatewayInfected(base, infected)
            try {
                Thread.sleep(if (infected) 700L else 400L)
            } catch (_: InterruptedException) {
            }
            pollAll()
            activity.runOnUiThread {
                if (!infected) auditApproved = false
                message = result
                busy = false
            }
        }.start()
    }

    DisposableEffect(localGatewayBase, selectedServiceBase, targetIp) {
        val running = AtomicBoolean(true)
        val worker = Thread {
            while (running.get()) {
                pollAll()
                try {
                    Thread.sleep(2500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.start() }
        onDispose {
            running.set(false)
            worker.interrupt()
        }
    }

    val phoneState = activePhoneState()
    val gatewayLabel = activeGatewayLabel()
    val gatewayState = if (selectedVpnActive()) vpnGatewayState else localPhoneState
    val phase = when {
        phoneState == null || !phoneState.reachable -> "CHECKING"
        phoneState.infected && auditApproved -> "REASSESSING"
        phoneState.infected -> "INFECTED"
        auditApproved -> "REHABILITATED"
        else -> "CLEAN"
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("TaraSec Security Demos", style = MaterialTheme.typography.titleLarge)
        Text(
            "The quick infection demo and the SSH self-healing demo are separate. Expand only the one you want to run.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDemo1 = !showDemo1 }
        ) {
            Text((if (showDemo1) "▼ " else "▶ ") + "Demo 1 · Basic Phone → Gateway → Tomato")
        }

        if (showDemo1) {
            TaraSectionCard(
                title = "Demo 1 · Basic infection demo",
                subtitle = "Phone → gateway → Tomato; no SSH required"
            ) {
                Text(
                    "This is the original quick TaraSec demonstration. Toggle this phone clean/infected on the gateway and watch the same path through to Tomato.",
                    style = MaterialTheme.typography.bodySmall
                )

                TaraStatusRow(
                    "Phone / unit",
                    when {
                        phoneState?.reachable != true -> "Checking…"
                        phoneState.infected -> "🔴 INFECTED"
                        else -> "🟢 CLEAN"
                    }
                )
                TaraStatusRow(
                    "Gateway",
                    when {
                        gatewayState?.reachable != true -> "$gatewayLabel · unavailable/checking"
                        phoneState?.reachable != true -> "🟢 $gatewayLabel · reachable; phone status unavailable"
                        phoneState.infected -> "🔴 $gatewayLabel · phone marked infected"
                        else -> "🟢 $gatewayLabel · phone clean"
                    }
                )
                TaraStatusRow(
                    "${basicTarget.name} node",
                    when {
                        basicReceiverProbe?.reachable != true -> "${basicTarget.ip} · unavailable/checking"
                        basicReceiverState?.reachable == true && basicReceiverState?.infected == true -> "🔴 ${basicTarget.ip} · INFECTED"
                        basicReceiverState?.reachable == true -> "🟢 ${basicTarget.ip} · CLEAN"
                        else -> "${basicTarget.ip} · checking"
                    }
                )
                Text(
                    "📱 Phone  →  🛡 $gatewayLabel  →  🖥 ${basicTarget.name}",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = activeControlBase() != null && !busy,
                        onClick = { setPhoneState(false) }
                    ) {
                        Text("Set CLEAN")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = activeControlBase() != null && !busy,
                        onClick = { setPhoneState(true) }
                    ) {
                        Text("Set INFECTED")
                    }
                }

                TaraStatusRow("Gateway control", activeControlBase() ?: "No TaraSec gateway detected")
                Text(
                    "Gatekeeper/internalInfections can be open at the same time to show the gateway database state changing independently of the app.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDemo2 = !showDemo2 }
        ) {
            Text((if (showDemo2) "▼ " else "▶ ") + "Demo 2 · SSH self-healing")
        }

        if (showDemo2) {
            Text(
                "Generate real SSH traffic and watch evidence propagate through the phone, gateway, receivers and Gatekeeper. This demo is intentionally more involved than Demo 1.",
                style = MaterialTheme.typography.bodySmall
            )

            TaraSectionCard(title = "Live demo path", subtitle = "Phone → gateway → receiver") {
                TaraStatusRow(
                    "Phone / unit",
                    when (phase) {
                        "INFECTED", "REASSESSING" -> "🔴 INFECTED"
                        "CLEAN", "REHABILITATED" -> "🟢 CLEAN"
                        else -> "Checking…"
                    }
                )
                TaraStatusRow(
                    "Gateway",
                    when (phase) {
                        "INFECTED" -> "🔴 $gatewayLabel · tagging subsequent traffic"
                        "REASSESSING" -> "🟠 $gatewayLabel · reassessing"
                        "REHABILITATED" -> "🟢 $gatewayLabel · threat tag withdrawn"
                        "CLEAN" -> "🟢 $gatewayLabel · no threat tag"
                        else -> "$gatewayLabel · checking"
                    }
                )
                TaraStatusRow("Receiver", "$discoveredName · ${targetIp.trim()}")
                TaraStatusRow("Audit phase", phase)
                Text("📱 Phone  →  🛡 $gatewayLabel  →  🖥 $discoveredName", style = MaterialTheme.typography.titleMedium)
            }

            TaraSectionCard(title = "1 · Start clean", subtitle = "Confirm the baseline before generating evidence") {
                Text(
                    "The phone and gateway should both show CLEAN. You can also open Gatekeeper on the gateway and receivers to confirm the same state.",
                    style = MaterialTheme.typography.bodySmall
                )
                TaraStatusRow(
                    "Observed now",
                    if (phoneState?.reachable == true && !phoneState.infected) "Ready · CLEAN" else "Waiting for CLEAN"
                )
            }

            TaraSectionCard(title = "2 · SSH to rejecting Server A", subtitle = "Create real firewall rejection evidence") {
                Text(
                    "Open an SSH client such as Termux and connect to the designated rejecting TaraSec server. Its iptables policy should reject the attempt and create the normal hackReport evidence.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Expected result: Server A reports the event → the gateway learns the attribution → this phone becomes 🔴 INFECTED → subsequent traffic is tagged.",
                    style = MaterialTheme.typography.bodySmall
                )
                TaraStatusRow(
                    "Gateway result",
                    if (phoneState?.reachable == true && phoneState.infected) "Observed · INFECTED / tagging" else "Waiting for Server A report"
                )
            }

            TaraSectionCard(title = "3 · SSH to accepting Server B", subtitle = "Challenge the existing attribution") {
                Text(
                    "With the phone already red, SSH to the designated accepting sandbox server. The connection may authenticate as a restricted demo user, but the traffic arrived carrying the existing TaraSec threat tag.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Server B should therefore create a hackReport record for tagged traffic even though its local firewall accepted the connection.",
                    style = MaterialTheme.typography.bodySmall
                )
                TaraStatusRow(
                    "Selected receiver",
                    when (receiverProbe?.reachable) {
                        true -> "$discoveredName reachable"
                        false -> "$discoveredName unavailable"
                        null -> "Checking…"
                    }
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phoneState?.reachable == true && phoneState.infected && receiverProbe?.reachable == true,
                    onClick = {
                        auditApproved = true
                        message = "Firewall assessment approved for audit. Watch Gatekeeper Live Audit for the real backend evidence and current infection state."
                    }
                ) {
                    Text("Server B accepted — approve audit")
                }
            }

            TaraSectionCard(title = "4 · Automatic audit", subtitle = "The expected end state is clean again") {
                TaraStatusRow("Human / firewall confirmation", if (auditApproved) "APPROVED" else "Waiting")
                TaraStatusRow(
                    "Current phone state",
                    when {
                        phoneState?.reachable != true -> "Unavailable"
                        phoneState.infected -> "🔴 INFECTED"
                        else -> "🟢 CLEAN"
                    }
                )
                Text(
                    if (auditApproved && phoneState?.reachable == true && !phoneState.infected) {
                        "Automatically rehabilitated: the gateway is clean again and the threat tag has been withdrawn. Historical hackReport evidence remains available in Gatekeeper."
                    } else if (auditApproved) {
                        "Audit approved. The app keeps polling the real gateway state; it will only show rehabilitation when the backend actually clears the infection."
                    } else {
                        "After approval, TaraSec should compare the original rejection with the accepted tagged traffic and any other evidence before changing the attribution."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            TaraSectionCard(title = "Verify Demo 2 in Gatekeeper", subtitle = "Independent view of the same records") {
                Text(
                    "Open Gatekeeper on the gateway, Server A and Server B while running the SSH demo. Use Gatekeeper → hackReports → watch live, or open index.php?f=liveAudit directly.",
                    style = MaterialTheme.typography.bodySmall
                )
                TaraStatusRow("Gateway", selectedServiceBase ?: localGatewayBase ?: "not detected")
                TaraStatusRow("Receiver", "http://${targetIp.trim()}/gatekeeper/index.php?f=liveAudit")
                Text(
                    "The live page shows the selected hackReport evidence beside its linked internalInfections record and refreshes once per second.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text("Demo 2 receiving node", style = MaterialTheme.typography.titleMedium)
            DemoClient.presets.forEach { preset ->
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        target = preset
                        targetIp = preset.ip
                        discoveredName = preset.name
                        receiverProbe = null
                        receiverState = null
                    }
                ) {
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

            TaraSectionCard(title = discoveredName, subtitle = "Demo 2 receiving TaraSec node") {
                TaraStatusRow("Destination", targetIp.trim())
                TaraStatusRow(
                    "Reachable",
                    when (receiverProbe?.reachable) {
                        true -> "Yes"
                        false -> "No"
                        null -> "Checking…"
                    }
                )
                receiverState?.let { receiver ->
                    if (receiver.reachable) {
                        TaraStatusRow("Observed TaraSec state", if (receiver.infected) "🔴 INFECTED" else "🟢 CLEAN")
                        TaraStatusRow("Severity", receiver.severity.toString())
                        if (receiver.publicIp.isNotBlank()) {
                            TaraStatusRow("Observed source", "${receiver.publicIp}:${receiver.publicPort}")
                        }
                        if (receiver.source.isNotBlank()) {
                            TaraStatusRow("Evidence", receiver.source)
                        }
                    }
                }
            }
        }

        if (showDebugInfo) {
            localPhoneState?.let { Text("Local status: ${it.rawJson}", style = MaterialTheme.typography.bodySmall) }
            vpnPhoneState?.let { Text("VPN phone status: ${it.rawJson}", style = MaterialTheme.typography.bodySmall) }
            basicReceiverState?.let { Text("Demo 1 Tomato status: ${it.rawJson}", style = MaterialTheme.typography.bodySmall) }
            receiverState?.let { Text("Demo 2 receiver status: ${it.rawJson}", style = MaterialTheme.typography.bodySmall) }
        }

        Button(enabled = !busy, onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Working…" else "Refresh status")
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
