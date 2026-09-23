package org.tarasec.app

import androidx.activity.ComponentActivity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun DemoPanel(
    gatewayName: String?,
    gatewayBaseUrl: String?,
    managerAuthenticated: Boolean = false,
    onRemediationSignIn: () -> Unit = {},
    showDebugInfo: Boolean = false
) {
    val activity = LocalContext.current as ComponentActivity
    val lifecycle = activity.lifecycle
    val localGatewayBase = remember { LocalGateway.baseUrl(activity) }
    var appInForeground by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appInForeground = true
                Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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
    var selectedGatewayFailureCount by remember { mutableStateOf(0) }
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
    var basicReceiverFailureCount by remember { mutableStateOf(0) }

    var target by remember { mutableStateOf(DemoClient.presets.first()) }
    var targetIp by remember { mutableStateOf(target.ip) }
    var discoveredName by remember { mutableStateOf(target.name) }

    var localPhoneState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var vpnGatewayState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var vpnPhoneState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverProbe by remember { mutableStateOf<DemoProbeResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val actionInProgress = remember { AtomicBoolean(false) }
    val pollGeneration = remember { AtomicInteger(0) }
    var secondsUntilRefresh by remember { mutableStateOf(0) }
    var automaticChecksEnabled by remember { mutableStateOf(true) }
    var intendedPhoneState by remember { mutableStateOf<Boolean?>(null) }
    var auditApproved by remember { mutableStateOf(false) }
    var showDemo1 by remember { mutableStateOf(true) }
    var showDemo2 by remember { mutableStateOf(false) }
    var showDemo3 by remember { mutableStateOf(false) }
    var showDemo4 by remember { mutableStateOf(false) }
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

    // Demo 3 must be able to clear a stale demo-only infection before it can
    // reach the DB server again.  A failed reachability poll must therefore
    // not discard an explicitly selected, built-in TaraSec gateway: doing so
    // creates a deadlock where DB-based gateway discovery is itself blocked.
    // Keep arbitrary addresses behind the normal verified/reachable gate.
    fun demo3ControlBase(): String? = when {
        directHotspotActive() -> localGatewayBase
        selectedServiceBase != null && demoGateways.any { (_, ip) ->
            selectedServiceBase.equals("http://$ip", ignoreCase = true) ||
                selectedServiceBase.equals("https://$ip", ignoreCase = true)
        } -> selectedServiceBase
        else -> activeControlBase()
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
        val generation = pollGeneration.get()
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
            // Ignore a poll that began before a Set CLEAN/INFECTED action.
            // Otherwise its older responses can overwrite the targeted result.
            if (generation != pollGeneration.get() || actionInProgress.get()) {
                return@runOnUiThread
            }
            hotspotIdentity = localIdentity
            hotspotGatewayConfig = localConfig
            if (selectedConfig?.reachable == true) {
                selectedGatewayConfig = selectedConfig
                selectedGatewayFailureCount = 0
            } else {
                selectedGatewayFailureCount += 1
                // A failed check is not a new gateway state. Preserve the last
                // confirmed configuration and report the failure separately.
                if (selectedGatewayConfig?.reachable != true) {
                    selectedGatewayConfig = selectedConfig
                }
            }
            if (basicIdentity?.reachable == true && basicReceiver?.reachable == true) {
                basicReceiverProbe = basicIdentity
                basicReceiverState = basicReceiver
                basicReceiverFailureCount = 0
            } else {
                basicReceiverFailureCount += 1
                // Do not replace a confirmed CLEAN/INFECTED state with
                // "checking" or a timeout from one later request.
                if (
                    basicReceiverProbe?.reachable != true ||
                    basicReceiverState?.reachable != true
                ) {
                    basicReceiverProbe = basicIdentity
                    basicReceiverState = basicReceiver
                }
            }
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
        intendedPhoneState = infected
        automaticChecksEnabled = true
        busy = true
        actionInProgress.set(true)
        pollGeneration.incrementAndGet()
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
                actionInProgress.set(false)
            }
        }.start()
    }

    DisposableEffect(
        localGatewayBase,
        selectedServiceBase,
        targetIp,
        basicTarget?.ip,
        automaticChecksEnabled,
        appInForeground
    ) {
        if (!automaticChecksEnabled || !appInForeground) {
            secondsUntilRefresh = 0
            onDispose { }
        } else {
            val running = AtomicBoolean(true)
            val worker = Thread {
                while (running.get()) {
                    if (!actionInProgress.get()) {
                        pollAll()
                    }
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
    }

    val phoneState = activePhoneState()
    val gatewayLabel = activeGatewayLabel()
    val gatewayState = if (directHotspotActive()) localPhoneState else vpnGatewayState
    val gatewayReachable = when {
        directHotspotActive() -> hotspotIdentity?.reachable == true ||
            hotspotGatewayConfig?.reachable == true
        else -> selectedGatewayConfig?.reachable == true
    }
    val phase = when {
        phoneState == null || !phoneState.reachable -> "CHECKING"
        phoneState.infected && auditApproved -> "REASSESSING"
        phoneState.infected -> "INFECTED"
        auditApproved -> "REHABILITATED"
        else -> "CLEAN"
    }

    LaunchedEffect(
        intendedPhoneState,
        phoneState,
        gatewayReachable,
        basicTarget?.ip,
        basicReceiverState
    ) {
        val intended = intendedPhoneState ?: return@LaunchedEffect
        val phoneReached = phoneState?.reachable == true && phoneState.infected == intended
        val receiverReached = basicTarget == null ||
            (basicReceiverState?.reachable == true && basicReceiverState?.infected == intended)

        if (phoneReached && gatewayReachable && receiverReached) {
            intendedPhoneState = null
            automaticChecksEnabled = false
            secondsUntilRefresh = 0
            message = "Requested " + (if (intended) "INFECTED" else "CLEAN") +
                " state confirmed across the active demo path. Automatic checks paused."
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("TaraSec Security Demos", style = MaterialTheme.typography.titleLarge)

        if (!directHotspotDetected && !selectedVpnActive()) {
            WireGuardQrHelp()
        }
        Text(
            "The demos can be difficult to interpret while they are running. At any time, use the demo's Copy info/debug report for AI button, paste it into an AI assistant such as ChatGPT, and ask what happened or what to do next.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "The copied information includes a TaraSec background link so an AI session that has never seen TaraSec or this app can understand the demo before interpreting the live result.",
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
                                    selectedGatewayFailureCount = 0
                                    vpnGatewayState = null
                                    vpnPhoneState = null
                                    basicReceiverProbe = null
                                    basicReceiverState = null
                                    basicReceiverFailureCount = 0
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
                            selectedVpnActive() -> "$selectedGatewayName · connected through VPN"
                            selectedGatewayConfig == null -> "$selectedGatewayName · checking connection…"
                            else -> "$selectedGatewayName · selected but unavailable"
                        }
                    )
                    TaraStatusRow(
                        "Configured receivers",
                        when {
                            activeGatewayConfig != null -> configuredTargets.size.toString()
                            selectedGatewayConfig == null -> "Checking gateway configuration…"
                            else -> "Unavailable while $selectedGatewayName is disconnected"
                        }
                    )
                    if (
                        !directHotspotDetected &&
                        selectedGatewayConfig != null &&
                        selectedGatewayConfig?.reachable != true
                    ) {
                        Text(
                            "$selectedGatewayName is selected, but the app cannot reach it. Connect the TaraSec VPN or a TaraSec hotspot, then refresh.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        automaticChecksEnabled = !automaticChecksEnabled
                        intendedPhoneState = null
                        secondsUntilRefresh = 0
                        message = if (automaticChecksEnabled) {
                            "Automatic checks resumed."
                        } else {
                            "Automatic checks paused. Use Refresh now for a single check."
                        }
                    }
                ) {
                    Text(
                        if (automaticChecksEnabled) {
                            "Pause automatic checks"
                        } else {
                            "Resume automatic checks"
                        }
                    )
                }
                Text(
                    when {
                        !appInForeground ->
                            "Automatic checks paused while the app is in the background"
                        !automaticChecksEnabled -> "Automatic checks paused"
                        secondsUntilRefresh > 0 ->
                            "Next automatic check in $secondsUntilRefresh second" +
                                if (secondsUntilRefresh == 1) "" else "s"
                        else -> "Checking now…"
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
                        !gatewayReachable -> "$gatewayLabel · unavailable/checking"
                        phoneState?.reachable != true -> "🟢 $gatewayLabel · reachable; phone status unavailable"
                        phoneState.infected -> "🔴 $gatewayLabel · phone marked infected"
                        else -> "🟢 $gatewayLabel · phone clean"
                    }
                )
                if (
                    !directHotspotActive() &&
                    selectedGatewayFailureCount > 0 &&
                    selectedGatewayConfig?.reachable == true
                ) {
                    Text(
                        "The latest gateway check did not complete; showing the last confirmed state.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
                    if (
                        basicReceiverFailureCount > 0 &&
                        basicReceiverProbe?.reachable == true &&
                        basicReceiverState?.reachable == true
                    ) {
                        Text(
                            "The latest ${basicTarget.name} check did not complete; showing the last confirmed status.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
            TaraSectionCard(
                title = "Demo 2 · SSH attribution and self-correction",
                subtitle = "DB-authoritative, session-bound demonstration"
            ) {
                DemoSshPanel(
                    // Demo 2 intentionally reaches dbserver1 directly. The
                    // server reports the source address it observes; that
                    // address, once recognized as a TaraSec gateway, is used
                    // for display, eligibility gating and demo cleanup.
                    baseUrl = "http://100.68.126.0",
                    managerAuthenticated = managerAuthenticated,
                    subscriberSignedIn = SubscriberAccountClient.storedToken(activity) != null,
                    onSignIn = onRemediationSignIn
                )
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDemo3 = !showDemo3 }
        ) {
            Text(
                (if (showDemo3) "▼ " else "▶ ") +
                    "Demo 3 · Request for Assistance"
            )
        }

        if (showDemo3) {
            TaraSectionCard(
                title = "Demo 3 · Community containment",
                subtitle = "Real Assistance Request with automatic recovery"
            ) {
                DemoAssistancePanel(
                    baseUrl = "http://100.68.126.0",
                    initialGatewayControlBase = demo3ControlBase()
                )
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDemo4 = !showDemo4 }
        ) {
            Text(
                (if (showDemo4) "▼ " else "▶ ") +
                    "Demo 4 · NATed hotspot routing"
            )
        }

        if (showDemo4) {
            TaraSectionCard(
                title = "Demo 4 · Tagged route to VPS partner",
                subtitle = "Normal public path when clean; NetBird path when infected"
            ) {
                DemoRoutingPanel(baseUrl = "http://100.68.126.0", gatewayControlBase = activeControlBase())
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
