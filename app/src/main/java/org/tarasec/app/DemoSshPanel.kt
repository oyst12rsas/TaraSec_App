package org.tarasec.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun DemoSshPanel(
    baseUrl: String?,
    managerAuthenticated: Boolean,
    subscriberSignedIn: Boolean,
    onSignIn: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var setups by remember(baseUrl) { mutableStateOf<List<DemoSshSetup>>(emptyList()) }
    var selectedId by rememberSaveable(baseUrl) { mutableStateOf<Int?>(null) }
    // Session ID and secret token must survive Activity recreation (for
    // example, portrait/landscape rotation) so polling resumes the same
    // DB-authoritative demo rather than silently starting over.
    var session by rememberSaveable(baseUrl) { mutableStateOf<DemoSshSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var eligibility by remember(baseUrl) { mutableStateOf<DemoEligibility?>(null) }
    var observedGateway by remember(baseUrl) { mutableStateOf<DemoGateway?>(null) }
    var remediationVisible by rememberSaveable(baseUrl) { mutableStateOf(false) }
    var message by rememberSaveable(baseUrl) {
        mutableStateOf(if (baseUrl.isNullOrBlank()) "Select a reachable TaraSec gateway first." else "Loading SSH demo setups…")
    }

    LaunchedEffect(baseUrl) {
        if (baseUrl.isNullOrBlank()) return@LaunchedEffect
        val (loaded, error) = withContext(Dispatchers.IO) { DemoSshClient.setups(baseUrl) }
        val gateway = withContext(Dispatchers.IO) { DemoSshClient.observedGateway(baseUrl) }
        val check = if (gateway.recognized && gateway.address.isNotBlank()) {
            withContext(Dispatchers.IO) {
                DemoSshClient.gatewayEligibility("http://${gateway.address}")
            }
        } else {
            DemoEligibility(
                eligible = false,
                remediationRequired = false,
                demoResetAvailable = false,
                message = gateway.message.ifBlank {
                    "Connect through a recognized TaraSec gateway before starting Demo 2."
                }
            )
        }
        setups = loaded
        selectedId = loaded.firstOrNull()?.id
        eligibility = check
        observedGateway = gateway
        remediationVisible = check.remediationRequired
        message = when {
            error.isNotBlank() -> error
            !gateway.recognized -> gateway.message.ifBlank {
                "Connect through a recognized TaraSec gateway before starting Demo 2."
            }
            check.message.isNotBlank() -> check.message
            else -> "Choose a setup, then start a short-lived demonstration."
        }
    }

    var displayedSecondsRemaining by remember(session?.sessionId) {
        mutableStateOf(session?.secondsRemaining ?: 0)
    }

    // Tick locally for a smooth countdown. Each DB status response resets this
    // value to the authoritative remaining seconds, correcting clock drift.
    LaunchedEffect(session?.sessionId, session?.secondsRemaining) {
        displayedSecondsRemaining = session?.secondsRemaining ?: 0
        while (displayedSecondsRemaining > 0 && session?.terminal() == false) {
            delay(1000)
            displayedSecondsRemaining = (displayedSecondsRemaining - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(baseUrl, session?.sessionId, session?.state) {
        val base = baseUrl ?: return@LaunchedEffect
        var current = session ?: return@LaunchedEffect
        if (current.sessionId < 1 || current.terminal()) return@LaunchedEffect
        while (!current.terminal()) {
            delay(2000)
            current = withContext(Dispatchers.IO) { DemoSshClient.status(base, current) }
            session = current
            if (current.message.isNotBlank()) message = current.message
        }
    }

    fun copy(value: String, label: String) {
        clipboard.setText(AnnotatedString(value))
        message = "$label copied."
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "The current gateway carries the demonstration traffic. The DB server assigns Node A, Node B and a temporary shared classroom credential.",
            style = MaterialTheme.typography.bodySmall
        )
        TaraStatusRow(
            "Current gateway",
            observedGateway?.let { gateway ->
                when {
                    gateway.recognized -> "${gateway.name} · ${gateway.address}"
                    gateway.address.isNotBlank() -> "Unrecognized route · ${gateway.address}"
                    else -> gateway.message.ifBlank { "Checking…" }
                }
            } ?: "Checking…"
        )

        if (session == null) {
            if (remediationVisible) {
                TaraSectionCard(
                    title = "Security review",
                    subtitle = "Demo eligibility could not be confirmed"
                ) {
                    Text(
                        "Demo 2 cannot start while this unit is infected on the current gateway. Demo-only state can be reset here; genuine security findings require the normal cleaning workflow.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    when {
                        managerAuthenticated -> {
                            Text(
                                "Certified hotspot owner session: technical review controls and the authorized hotspot context will appear here as the remediation service is expanded.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        subscriberSignedIn -> {
                            Text(
                                "Signed-in hotspot user: only this unit's review status and guided next steps are shown.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> {
                            Text(
                                "Sign in to continue to the appropriate remediation view.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onSignIn
                            ) { Text("Sign in with Google or TaraSec") }
                        }
                    }
                    if (eligibility?.demoResetAvailable == true) {
                        OutlinedButton(
                            enabled = !busy && observedGateway?.recognized == true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val gateway = observedGateway
                                    ?.takeIf { it.recognized && it.address.isNotBlank() }
                                    ?: return@OutlinedButton
                                val control = "http://${gateway.address}"
                                val base = baseUrl ?: return@OutlinedButton
                                busy = true
                                message = "Clearing previous demonstration state…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        DemoClient.setGatewayInfected(control, false)
                                    }
                                    delay(2500)
                                    val check = withContext(Dispatchers.IO) {
                                        DemoSshClient.gatewayEligibility(control)
                                    }
                                    eligibility = check
                                    remediationVisible = check.remediationRequired
                                    message = if (check.eligible) {
                                        "Previous demonstration state cleared. Demo 2 may now start."
                                    } else {
                                        result + " Waiting for the clean state to propagate."
                                    }
                                    busy = false
                                }
                            }
                        ) { Text("Reset demo state on this gateway") }
                    }
                    OutlinedButton(
                        enabled = !busy && !baseUrl.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val base = baseUrl ?: return@OutlinedButton
                            busy = true
                            scope.launch {
                                val gateway = withContext(Dispatchers.IO) {
                                    DemoSshClient.observedGateway(base)
                                }
                                val check = if (gateway.recognized && gateway.address.isNotBlank()) {
                                    withContext(Dispatchers.IO) {
                                        DemoSshClient.gatewayEligibility("http://${gateway.address}")
                                    }
                                } else {
                                    DemoEligibility(false, false, false, gateway.message.ifBlank {
                                        "Connect through a recognized TaraSec gateway before starting Demo 2."
                                    })
                                }
                                eligibility = check
                                observedGateway = gateway
                                remediationVisible = check.remediationRequired
                                message = if (gateway.recognized) {
                                    check.message
                                } else {
                                    gateway.message.ifBlank {
                                        "Connect through a recognized TaraSec gateway before starting Demo 2."
                                    }
                                }
                                busy = false
                            }
                        }
                    ) { Text("Check eligibility again") }
                }
            }

            if (setups.isEmpty()) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Available setups", style = MaterialTheme.typography.titleMedium)
                setups.forEach { setup ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { selectedId = setup.id }
                    ) {
                        Text((if (selectedId == setup.id) "✓ " else "") + setup.name)
                    }
                    if (selectedId == setup.id) {
                        TaraStatusRow("Node A", "${setup.nodeA}:${setup.nodeAPort} · rejects SSH")
                        TaraStatusRow("Node B", "${setup.nodeB}:${setup.nodeBPort} · non-executing honeypot")
                        TaraStatusRow("Session window", "${setup.expiresIn} seconds")
                    }
                }
                Button(
                    enabled = !busy && selectedId != null && !baseUrl.isNullOrBlank() &&
                        eligibility?.eligible == true &&
                        observedGateway?.recognized == true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val base = baseUrl ?: return@Button
                        val setupId = selectedId ?: return@Button
                        busy = true
                        message = "Checking whether Demo 2 may start…"
                        scope.launch {
                            val gateway = withContext(Dispatchers.IO) {
                                DemoSshClient.observedGateway(base)
                            }
                            val check = if (gateway.recognized && gateway.address.isNotBlank()) {
                                withContext(Dispatchers.IO) {
                                    DemoSshClient.gatewayEligibility("http://${gateway.address}")
                                }
                            } else {
                                DemoEligibility(false, false, false, gateway.message.ifBlank {
                                    "Connect through a recognized TaraSec gateway before starting Demo 2."
                                })
                            }
                            eligibility = check
                            observedGateway = gateway
                            remediationVisible = check.remediationRequired
                            if (!gateway.recognized) {
                                session = null
                                message = gateway.message.ifBlank {
                                    "Connect through a recognized TaraSec gateway before starting Demo 2."
                                }
                            } else if (check.eligible) {
                                val created = withContext(Dispatchers.IO) {
                                    DemoSshClient.create(base, setupId)
                                }
                                session = if (created.sessionId > 0) created else null
                                message = created.message.ifBlank {
                                    "Session started. Make the Node A connection first."
                                }
                            } else {
                                session = null
                                message = check.message.ifBlank {
                                    "Security review is required before Demo 2 can start."
                                }
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(
                        when {
                            busy -> "Checking…"
                            eligibility?.eligible == true &&
                                observedGateway?.recognized == true -> "Start SSH demo"
                            observedGateway?.recognized == false -> "Recognized gateway required"
                            else -> "Security review required"
                        }
                    )
                }
            }
        } else {
            val current = session!!
            TaraSectionCard(title = "Live DB session", subtitle = "Session #${current.sessionId}") {
                TaraStatusRow("State", stateLabel(current.state))
                TaraStatusRow("Node A report", nodeAStatus(current))
                TaraStatusRow("Gateway/DB update", gatewayStatus(current))
                TaraStatusRow("Node B report", nodeBStatus(current))
                TaraStatusRow("Attempts at Node B", current.attempts.toString())
                if (current.progressMessage.isNotBlank()) {
                    Text(current.progressMessage, style = MaterialTheme.typography.bodySmall)
                }
                TaraStatusRow(
                    "Time remaining",
                    formatCountdown(displayedSecondsRemaining)
                )
            }

            TaraSectionCard(title = "1 · Connect to Node A", subtitle = "Create ordinary rejection evidence") {
                Text(
                    "Run this first. Node A rejects the connection; that evidence causes TaraSec to mark and tag this unit.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(current.nodeACommand(), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { copy(current.nodeACommand(), "Node A command") }
                ) { Text("Copy Node A SSH command") }
            }

            TaraSectionCard(title = "2 · Connect to Node B", subtitle = "Prove the tagged connection is legitimate") {
                Text(
                    "After the unit becomes infected, use the temporary credential below. Node B simulates SSH but exposes no files, accounts or shell.",
                    style = MaterialTheme.typography.bodySmall
                )
                TaraStatusRow("Host", current.nodeB)
                TaraStatusRow("Port", current.nodeBPort.toString())
                TaraStatusRow("Username", current.username)
                TaraStatusRow("Password", current.password)
                Text(current.nodeBCommand(), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { copy(current.nodeBCommand(), "Node B command") }
                ) { Text("Copy Node B SSH command") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { copy(current.password, "Password") }
                ) { Text("Copy password") }
            }

            Text(resultExplanation(current.state), style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !busy && !baseUrl.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val base = baseUrl ?: return@Button
                    busy = true
                    scope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            DemoSshClient.status(base, current)
                        }
                        session = updated
                        message = updated.message.ifBlank { "Session status refreshed." }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Refreshing…" else "Refresh session") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    session = null
                    message = "Ready to start another SSH demo."
                }
            ) { Text("Close session") }
        }

        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatCountdown(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

private fun nodeAStatus(session: DemoSshSession): String =
    if (session.nodeAObserved) "🔴 SSH rejection received" else "⚪ Waiting"

private fun gatewayStatus(session: DemoSshSession): String = when {
    session.state == "cleared" -> "🟢 Demo infection cleared"
    session.unitMarked -> "🔴 Unit marked infected"
    else -> "⚪ Waiting"
}

private fun nodeBStatus(session: DemoSshSession): String = when {
    session.state == "cleared" -> "🟢 Connection validated"
    session.state == "owner_clear_required" && session.nodeBObserved -> "🔴 Validation rejected"
    session.nodeBObserved -> "🟡 Report received"
    else -> "⚪ Waiting"
}

private fun stateLabel(state: String): String = when (state) {
    "awaiting_node_a" -> "Waiting for Node A, then Node B"
    "demo_infected" -> "Node A observed · unit marked infected"
    "awaiting_node_b" -> "Waiting for Node B"
    "cleared" -> "🟢 Complete · demo attribution cleared"
    "owner_clear_required" -> "🔴 Not cleared · owner review required"
    "expired" -> "Expired"
    else -> state.replace('_', ' ').ifBlank { "Unknown" }
}

private fun resultExplanation(state: String): String = when (state) {
    "cleared" -> "The DB correlated the complete connection tuple and cleared only the reversible evidence created by this active demo."
    "owner_clear_required" -> "The sequence did not qualify for automatic correction. Independent or ambiguous evidence must remain for the hotspot owner to review."
    "expired" -> "The short demonstration window expired. Close this session and start a new one."
    else -> "Status refreshes every two seconds. Use Node A first and Node B only after TaraSec has marked the unit infected."
}
