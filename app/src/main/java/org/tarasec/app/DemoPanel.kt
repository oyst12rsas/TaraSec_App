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
import androidx.compose.runtime.LaunchedEffect
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

    val selectedInstallation = remember(gatewayName, gatewayBaseUrl) {
        val items = InstallationStore.load(activity)
        val selectedId = InstallationStore.selectedId(activity)
        items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
    }
    val demoGateways = listOf(
        "Squash" to "100.68.25.154",
        "Audi" to "100.68.153.251",
        "Standard gateway" to "100.68.165.190"
    )
    val initialGatewayName = selectedInstallation?.name
        ?: gatewayName?.takeIf { it.isNotBlank() }
        ?: "Squash"
    val automaticGatewayIp = selectedInstallation?.serviceIp
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: gatewayBaseUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { InstallationStore.endpointHost(it) }
            ?.takeIf { it.isNotBlank() }
        ?: demoGateways.firstOrNull { it.first.equals(initialGatewayName, ignoreCase = true) }?.second
        ?: "100.68.25.154"
    var selectedGatewayName by remember(selectedInstallation?.id, gatewayName) {
        mutableStateOf(initialGatewayName)
    }
    var configuredServiceIp by remember(selectedInstallation?.id, gatewayBaseUrl) {
        mutableStateOf(automaticGatewayIp)
    }
    var serviceIpDraft by remember(selectedInstallation?.id, gatewayBaseUrl) {
        mutableStateOf(automaticGatewayIp)
    }
    val selectedServiceBase = configuredServiceIp.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it"
    }

    var hotspotIdentity by remember { mutableStateOf<DemoProbeResult?>(null) }
    var hotspotGatewayConfig by remember { mutableStateOf<DemoGatewayConfiguration?>(null) }
    var selectedGatewayConfig by remember { mutableStateOf<DemoGatewayConfiguration?>(null) }
    val directHotspotDetected =
        hotspotIdentity?.reachable == true || hotspotGatewayConfig?.reachable == true
    val activeGatewayConfig = when {
        directHotspotDetected && hotspotGatewayConfig?.reachable == true -> hotspotGatewayConfig
        !directHotspotDetected && selectedGatewayConfig?.reachable == true -> selectedGatewayConfig
        else -> null
    }
    // The selected gateway is authoritative: appDemoConfiguration.php exposes
    // DEMO_NODES and DEMO_NODE_NAMES from that gateway's tarasecfw.conf.
    val gatewayTargets = activeGatewayConfig?.nodes.orEmpty()
    var endpointIpDraft by remember { mutableStateOf("") }
    var customEndpointIp by remember { mutableStateOf("") }
    val configuredTargets = gatewayTargets + listOfNotNull(
        customEndpointIp
            .takeIf { selectedGatewayName == "Standard gateway" && it.isNotBlank() }
            ?.let { DemoTarget("Custom endpoint", it) }
    )
    var basicTargetIp by remember { mutableStateOf("") }
    val basicTarget = configuredTargets.firstOrNull { it.ip == basicTargetIp }
        ?: configuredTargets.firstOrNull()
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
    var secondsUntilRefresh by remember { mutableStateOf(0) }
    var auditApproved by remember { mutableStateOf(false) }
    var showDemo1 by remember { mutableStateOf(true) }
    var showDemo2 by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Demo 1 is ready. Expand Demo 2 when you want to run the SSH self-healing scenario.") }

    LaunchedEffect(configuredTargets) {
        val available = configuredTargets.map { it.ip }
        if (basicTargetIp !in available) {
            basicTargetIp = configuredTargets.firstOrNull()?.ip.orEmpty()
            basicReceiverProbe = null
            basicReceiverState = null
        }
        if (targetIp !in available && configuredTargets.isNotEmpty()) {
            val first = configuredTargets.first()
            target = first
            targetIp = first.ip
            discoveredName = first.name
        }
    }

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

    fun directHotspotActive(): Boolean = directHotspotDetected

    fun selectedVpnActive(): Boolean =
        selectedGatewayConfig?.reachable == true || vpnGatewayState?.reachable == true

    fun activePhoneState(): DemoThreatStatus? = when {
        directHotspotActive() -> localPhoneState
        selectedServiceBase != null -> vpnPhoneState
        else -> null
    }

    fun activeControlBase(): String? = when {
        directHotspotActive() -> localGatewayBase
        selectedServiceBase != null && selectedVpnActive() -> selectedServiceBase
        else -> null
    }

    fun activeGatewayLabel(): String = when {
        directHotspotActive() ->
            hotspotIdentity?.nodeName?.takeIf { hotspotIdentity?.reachable == true && it.isNotBlank() }
                ?: hotspotGatewayConfig?.gatewayName?.takeIf { it.isNotBlank() }
                ?: "TaraSec hotspot"
        selectedServiceBase != null -> selectedGatewayName
        else -> "No active gateway"
    }

    fun pollAll(after: String? = null) {
        val t = currentTarget()
        if (!validIpv4(t.ip)) {
            activity.runOnUiThread { message = "Enter a valid IPv4 address for the Demo 2 receiving node." }
            return
        }

        val localIdentity = localGatewayBase?.let { DemoClient.probeBase(it, "TaraSec hotspot") }
        val localConfig = localGatewayBase?.let { DemoClient.gatewayConfigurationBase(it) }
        val selectedConfig = selectedServiceBase?.let { DemoClient.gatewayConfigurationBase(it) }
        val basicIdentity = basicTarget?.let { DemoClient.probe(it) }
        val basicReceiver = basicTarget?.let { DemoClient.threatStatus(it) }
        val identity = if (basicTarget != null && t.ip == basicTarget.ip) {
            basicIdentity ?: DemoClient.probe(t)
        } else {
            DemoClient.probe(t)
        }
        val receiver = if (basicTarget != null && t.ip == basicTarget.ip) {
            basicReceiver ?: DemoClient.threatStatus(t)
        } else {
            DemoClient.threatStatus(t)
        }
        val local = localGatewayBase?.let { DemoClient.localThreatStatusBase(it) }
        val vpnGateway = selectedServiceBase?.let { DemoClient.threatStatusBase(it) }
        // Phone status belongs to the selected gateway and must not be gated on
        // appInfection.php, which describes a different status query.
        val vpnPhone = selectedServiceBase?.let {
            DemoClient.localThreatStatusBase(it)
        }

        activity.runOnUiThread {
            hotspotIdentity = localIdentity
            hotspotGatewayConfig = localConfig
            selectedGatewayConfig = selectedConfig
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

        val usingDirectHotspot = directHotspotActive()
        val receiverTarget = basicTarget
        Thread {
            val result = DemoClient.setGatewayInfected(base, infected)

            // The receiver status request is itself the real tagged
            // connection. Its endpoint waits, for at most 1.5 seconds, until
            // tarakernel reports that exact TCP session to taralink.
            val updatedGatewayPhone = DemoClient.localThreatStatusBase(base)
            val updatedReceiver = receiverTarget?.let { DemoClient.threatStatus(it) }
            val updatedReceiverProbe = receiverTarget?.let {
                DemoProbeResult(
                    target = it,
                    reachable = updatedReceiver?.reachable == true,
                    nodeName = it.name,
                    message = updatedReceiver?.message.orEmpty()
                )
            }

            activity.runOnUiThread {
                if (usingDirectHotspot) {
                    localPhoneState = updatedGatewayPhone
                } else {
                    vpnPhoneState = updatedGatewayPhone
                }
                if (receiverTarget != null) {
                    basicReceiverProbe = updatedReceiverProbe
                    basicReceiverState = updatedReceiver
                }
                if (!infected) auditApproved = false
                message = result
                busy = false
            }
        }.start()
    }

    DisposableEffect(localGatewayBase, selectedServiceBase, targetIp, basicTarget?.ip) {
        val running = AtomicBoolean(true)
        val worker = Thread {
            while (running.get()) {
                pollAll()
                activity.runOnUiThread { secondsUntilRefresh = 3 }
                for (remaining in 2 downTo 0) {
                    try {
                        Thread.sleep(1000L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (!running.get()) return@Thread
                    activity.runOnUiThread { secondsUntilRefresh = remaining }
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
    val gatewayState = if (directHotspotActive()) localPhoneState else vpnGatewayState
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
                subtitle = "See a phone's security status follow it through TaraSec"
            ) {
                TaraSectionCard(
                    title = "What this demonstrates",
                    subtitle = "One security status, shared across the protected path"
                ) {
                    Text(
                        "Mark this phone as infected and TaraSec records the warning at the selected gateway. The receiving node then sees the same warning, showing how security information follows traffic between networks.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Mark the phone clean again and the warning is withdrawn across the path. This is a safe status demonstration: it does not install malware, infect the phone, or scan personal files.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TaraSectionCard(
                    title = "Demo configuration",
                    subtitle = "Select the gateway used between the VPN networks"
                ) {
                    if (directHotspotDetected) {
                        Text(
                            "Connected directly to a TaraSec hotspot. This hotspot is automatically the demo gateway.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text("Choose WireGuard gateway", style = MaterialTheme.typography.titleMedium)
                        demoGateways.forEach { (name, address) ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    selectedGatewayName = name
                                    serviceIpDraft = address
                                    configuredServiceIp = address
                                    selectedGatewayConfig = null
                                    vpnGatewayState = null
                                    vpnPhoneState = null
                                    basicReceiverProbe = null
                                    basicReceiverState = null
                                    message = "Loading demo endpoints from $name…"
                                }
                            ) {
                                Text(
                                    (if (configuredServiceIp == address) "✓ " else "") +
                                        "$name · $address"
                                )
                            }
                        }
                    }
                    TaraStatusRow(
                        "Selected gateway",
                        if (directHotspotDetected) activeGatewayLabel() else selectedGatewayName
                    )
                    TaraStatusRow(
                        "Active demo path",
                        when {
                            directHotspotActive() -> "Direct TaraSec hotspot"
                            selectedVpnActive() -> "Selected VPN gateway"
                            else -> "No TaraSec gateway detected"
                        }
                    )
                    TaraStatusRow(
                        "Configured receivers",
                        if (activeGatewayConfig != null) configuredTargets.size.toString()
                        else "Configuration unavailable"
                    )
                    if (directHotspotDetected && activeGatewayConfig != null && configuredTargets.isEmpty()) {
                        Text(
                            "This TaraSec hotspot has no demo endpoints configured.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                activity.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://tarasec.org/app/demo.html#configure-endpoints")
                                    )
                                )
                            }
                        ) {
                            Text("How to configure demo endpoints")
                        }
                    }
                    if (configuredTargets.isNotEmpty()) {
                        Text("Demo endpoint", style = MaterialTheme.typography.titleMedium)
                        configuredTargets.forEach { endpoint ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    basicTargetIp = endpoint.ip
                                    target = endpoint
                                    targetIp = endpoint.ip
                                    discoveredName = endpoint.name
                                    basicReceiverProbe = null
                                    basicReceiverState = null
                                }
                            ) {
                                Text(
                                    (if (endpoint.ip == basicTarget?.ip) "✓ " else "") +
                                        "${endpoint.name} · ${endpoint.ip}"
                                )
                            }
                        }
                        if (configuredTargets.size == 1) {
                            Text(
                                "This gateway handles one demo endpoint, so it was selected automatically.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (activeGatewayConfig == null) {
                        val detail = selectedGatewayConfig?.message
                            ?.takeIf { it.isNotBlank() }
                            ?: hotspotGatewayConfig?.message?.takeIf { it.isNotBlank() }
                            ?: "No TaraSec gateway configuration endpoint responded."
                        Text("Gateway detection: $detail", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!directHotspotDetected && selectedGatewayName == "Standard gateway") {
                        Text(
                            "The standard gateway offers its configured endpoints above. You may also test another endpoint.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = endpointIpDraft,
                            onValueChange = {
                                endpointIpDraft = it.filter { c -> c.isDigit() || c == '.' }
                            },
                            label = { Text("Endpoint IP") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            enabled = validIpv4(endpointIpDraft),
                            onClick = {
                                customEndpointIp = endpointIpDraft.trim()
                                basicTargetIp = customEndpointIp
                                target = DemoTarget("Custom endpoint", customEndpointIp)
                                targetIp = customEndpointIp
                                discoveredName = "Custom endpoint"
                                basicReceiverProbe = null
                                basicReceiverState = null
                                message = "Using custom endpoint $customEndpointIp."
                            }
                        ) {
                            Text("Use this endpoint")
                        }
                    }
                }

                Text("Live status", style = MaterialTheme.typography.titleMedium)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        secondsUntilRefresh = 0
                        refresh("Status refreshed")
                    }
                ) {
                    Text("Refresh now")
                }
                Text(
                    if (secondsUntilRefresh > 0) {
                        "Next automatic check in $secondsUntilRefresh second" +
                            if (secondsUntilRefresh == 1) "" else "s"
                    } else {
                        "Checking now…"
                    },
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
                if (basicTarget == null) {
                    TaraStatusRow("Receiver", "No demo receivers configured")
                    Text(
                        "This TaraSec hotspot is valid, but its gateway has not configured any nodes for Demo 1.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    TaraStatusRow(
                        "${basicTarget.name} node",
                        when {
                            basicReceiverProbe == null -> "${basicTarget.ip} · checking"
                            basicReceiverProbe?.reachable == false ->
                                "${basicTarget.ip} · configured but unavailable: ${basicReceiverProbe?.message.orEmpty()}"
                            basicReceiverState?.reachable == false ->
                                "${basicTarget.ip} · status unavailable: ${basicReceiverState?.message.orEmpty()}"
                            basicReceiverState?.reachable == true && basicReceiverState?.infected == true -> "🔴 ${basicTarget.ip} · INFECTED"
                            basicReceiverState?.reachable == true -> "🟢 ${basicTarget.ip} · CLEAN"
                            else -> "${basicTarget.ip} · checking"
                        }
                    )
                    Text(
                        "📱 Phone  →  🛡 $gatewayLabel  →  🖥 ${basicTarget.name}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

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
            configuredTargets.forEach { preset ->
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
