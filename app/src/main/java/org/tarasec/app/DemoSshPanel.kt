package org.tarasec.app

import android.content.ClipData
import android.os.Build
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
    val clipboard = LocalClipboard.current
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
    var nodeAVerdict by rememberSaveable(baseUrl, session?.sessionId) { mutableStateOf<String?>(null) }
    var nodeAVerdictDetails by rememberSaveable(baseUrl, session?.sessionId) { mutableStateOf("") }
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
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
            message = "$label copied."
        }
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
                    if (eligibility?.demoResetAvailable == true) {
                        OutlinedButton(
                            enabled = !busy && observedGateway?.recognized == true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val gateway = observedGateway
                                    ?.takeIf { it.recognized && it.address.isNotBlank() }
                                    ?: return@OutlinedButton
                                val control = "http://${gateway.address}"
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
            val demoComplete = current.state == "cleared"
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

            if (!current.nodeAObserved) TaraSectionCard(title = "1 · Connect to Node A", subtitle = "Create ordinary rejection evidence") {
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

            if (!demoComplete) TaraSectionCard(title = "2 · Connect to Node B", subtitle = "Prove the tagged connection is legitimate") {
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
                // Password copying is intentionally hidden while the classroom
                // demo uses the fixed password "1". Restore this button when
                // per-session or generated passwords are enabled again.
                /*
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { copy(current.password, "Password") }
                ) { Text("Copy password") }
                */
                Text(
                    "Password copying is temporarily removed while the demo password is fixed to 1.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            TaraSectionCard(title = "3 · Ask Node A", subtitle = "Verify the gateway is authoritative") {
                Text(
                    "After Node B clears the unit, ask Node A again. This is a new ordinary request: Node A reports the classification carried by the gateway, not its earlier SSH rejection.",
                    style = MaterialTheme.typography.bodySmall
                )
                TaraStatusRow(
                    "Node A now sees this unit as",
                    nodeAVerdict ?: if (current.state == "cleared") "Not checked" else "Waiting for the gateway to clear the unit"
                )
                if (nodeAVerdictDetails.isNotBlank()) {
                    Text(nodeAVerdictDetails, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = !busy && current.state == "cleared",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        nodeAVerdict = "Checking…"
                        nodeAVerdictDetails = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DemoClient.threatStatus(DemoTarget("Node A", current.nodeA))
                            }
                            if (!result.reachable) {
                                nodeAVerdict = "⚪ Could not verify"
                                nodeAVerdictDetails = result.message.ifBlank { "Node A did not return a classification." }
                            } else if (result.infected) {
                                nodeAVerdict = "🔴 INFECTED"
                                nodeAVerdictDetails = "Node A still received tagged traffic (severity ${result.severity}); the gateway clean state has not yet reached this request."
                            } else {
                                nodeAVerdict = "🟢 CLEAN"
                                nodeAVerdictDetails = "Node A received this request as clean. The gateway's current verdict overruled the earlier Node A rejection."
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy && nodeAVerdict == "Checking…") "Asking Node A…" else "Ask Node A if I am clean") }
            }

            Text(resultExplanation(current.state), style = MaterialTheme.typography.bodySmall)
            if (demoComplete) {
                TaraSectionCard(
                    title = "Why this matters",
                    subtitle = "The gateway remained authoritative while evidence was corrected"
                ) {
                    Text(
                        "Node A reported a rejected SSH connection, so later traffic from the same unit arrived with a warning. Node B then supplied legitimate session evidence, and the DB cleared only this demonstration's reversible classification. A sender cannot simply declare itself clean; independent receiver reports and the authoritative gateway decide what other networks see."
                    )
                }
            }
            if (!current.terminal()) Button(
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
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val base = baseUrl ?: return@OutlinedButton
                    val gateway = observedGateway
                    busy = true
                    message = "Closing session on the DB server…"
                    scope.launch {
                        val (closed, closeMessage) = withContext(Dispatchers.IO) {
                            DemoSshClient.cancel(base, current)
                        }
                        if (!closed) {
                            message = closeMessage
                            busy = false
                            return@launch
                        }

                        session = null
                        if (gateway?.recognized == true && gateway.address.isNotBlank()) {
                            message = "Session closed; clearing its gateway demo state…"
                            val control = "http://${gateway.address}"
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
                                "Session closed and demo state cleared. Ready to start another SSH demo."
                            } else {
                                result + " Session closed; waiting for the clean state to propagate."
                            }
                        } else {
                            message = "Session closed on the DB server. Reconnect through a recognized TaraSec gateway to verify demo cleanup."
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Closing…" else "Close session") }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val report = buildDemoSshDebugReport(
                    baseUrl,
                    observedGateway,
                    eligibility,
                    setups.firstOrNull { it.id == selectedId },
                    session,
                    displayedSecondsRemaining,
                    nodeAVerdict,
                    nodeAVerdictDetails,
                    message
                )
                copy(report, "Debug report")
            }
        ) { Text("Copy debug report for AI") }
        Text(
            "Copy the report, paste it into an AI assistant such as ChatGPT, and ask it to explain what happened in the demo. The report omits passwords and session tokens.",
            style = MaterialTheme.typography.bodySmall
        )

        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

private fun buildDemoSshDebugReport(
    baseUrl: String?,
    gateway: DemoGateway?,
    eligibility: DemoEligibility?,
    setup: DemoSshSetup?,
    session: DemoSshSession?,
    displayedSecondsRemaining: Int,
    nodeAVerdict: String?,
    nodeAVerdictDetails: String,
    message: String
): String = buildString {
    appendLine("TaraSec Demo 2 debug report")
    appendLine("ai_background=https://tarasec.org/ai/demo-guide/")
    appendLine("For an AI session unfamiliar with TaraSec: read the ai_background page before interpreting this report.")
    appendLine("generated_at_epoch_ms=" + System.currentTimeMillis())
    appendLine("app_version=" + BuildConfig.VERSION_NAME)
    appendLine("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT)
    appendLine("secrets=omitted (password and session token)")
    appendLine()
    appendLine("[connection]")
    appendLine("db_endpoint=" + baseUrl.orEmpty().ifBlank { "not selected" })
    appendLine("gateway_name=" + gateway?.name.orEmpty().ifBlank { "unknown" })
    appendLine("gateway_address=" + gateway?.address.orEmpty().ifBlank { "unknown" })
    appendLine("gateway_recognized=" + (gateway?.recognized ?: false))
    appendLine("gateway_message=" + gateway?.message.orEmpty().ifBlank { "none" })
    appendLine()
    appendLine("[eligibility]")
    appendLine("eligible=" + (eligibility?.eligible ?: false))
    appendLine("remediation_required=" + (eligibility?.remediationRequired ?: false))
    appendLine("demo_reset_available=" + (eligibility?.demoResetAvailable ?: false))
    appendLine("eligibility_message=" + eligibility?.message.orEmpty().ifBlank { "none" })
    appendLine()
    appendLine("[setup]")
    appendLine("setup_id=" + (setup?.id ?: 0))
    appendLine("setup_name=" + setup?.name.orEmpty().ifBlank { "none" })
    appendLine("node_a=" + (setup?.let { it.nodeA + ":" + it.nodeAPort } ?: "unknown"))
    appendLine("node_b=" + (setup?.let { it.nodeB + ":" + it.nodeBPort } ?: "unknown"))
    appendLine()
    appendLine("[session]")
    appendLine("session_id=" + (session?.sessionId ?: 0))
    appendLine("state=" + session?.state.orEmpty().ifBlank { "none" })
    appendLine("attempts=" + (session?.attempts ?: 0))
    appendLine("seconds_remaining=" + displayedSecondsRemaining)
    appendLine("expires=" + session?.expires.orEmpty().ifBlank { "unknown" })
    appendLine("node_a_observed=" + (session?.nodeAObserved ?: false))
    appendLine("unit_marked=" + (session?.unitMarked ?: false))
    appendLine("node_b_observed=" + (session?.nodeBObserved ?: false))
    appendLine("node_b_login_accepted=" + (session?.nodeBLoginAccepted?.toString() ?: "unknown"))
    appendLine("node_a_status=" + (session?.let(::nodeAStatus) ?: "no session"))
    appendLine("gateway_db_status=" + (session?.let(::gatewayStatus) ?: "no session"))
    appendLine("node_b_status=" + (session?.let(::nodeBStatus) ?: "no session"))
    appendLine("node_a_final_verdict=" + nodeAVerdict.orEmpty().ifBlank { "not checked" })
    appendLine("node_a_final_verdict_details=" + nodeAVerdictDetails.ifBlank { "none" })
    appendLine("progress_message=" + session?.progressMessage.orEmpty().ifBlank { "none" })
    appendLine("client_message=" + message.ifBlank { "none" })
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
    session.nodeBLoginAccepted == true && session.state == "cleared" ->
        "🟢 Login accepted · evidence validated"
    session.nodeBLoginAccepted == true ->
        "🟢 Login accepted · evidence validation incomplete"
    session.nodeBLoginAccepted == false ->
        "🔴 Login rejected"
    session.nodeBObserved -> "🟡 Report received · checking login"
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
