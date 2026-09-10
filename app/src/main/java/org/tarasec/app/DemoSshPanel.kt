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
fun DemoSshPanel(baseUrl: String?) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var setups by remember(baseUrl) { mutableStateOf<List<DemoSshSetup>>(emptyList()) }
    var selectedId by remember(baseUrl) { mutableStateOf<Int?>(null) }
    var session by remember(baseUrl) { mutableStateOf<DemoSshSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(baseUrl) {
        mutableStateOf(if (baseUrl.isNullOrBlank()) "Select a reachable TaraSec gateway first." else "Loading SSH demo setups…")
    }

    LaunchedEffect(baseUrl) {
        if (baseUrl.isNullOrBlank()) return@LaunchedEffect
        val (loaded, error) = withContext(Dispatchers.IO) { DemoSshClient.setups(baseUrl) }
        setups = loaded
        selectedId = loaded.firstOrNull()?.id
        message = error.ifBlank { "Choose a setup, then start a short-lived demonstration." }
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
            "The DB server assigns Node A, Node B and a temporary shared classroom credential. The gateway remains unaware that a demo is running.",
            style = MaterialTheme.typography.bodySmall
        )

        if (session == null) {
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
                    enabled = !busy && selectedId != null && !baseUrl.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val base = baseUrl ?: return@Button
                        val setupId = selectedId ?: return@Button
                        busy = true
                        message = "Starting SSH demo…"
                        scope.launch {
                            val created = withContext(Dispatchers.IO) {
                                DemoSshClient.create(base, setupId)
                            }
                            session = if (created.sessionId > 0) created else null
                            message = created.message.ifBlank {
                                "Session started. Make the Node A connection first."
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(if (busy) "Starting…" else "Start SSH demo")
                }
            }
        } else {
            val current = session!!
            TaraSectionCard(title = "Live DB session", subtitle = "Session #${current.sessionId}") {
                TaraStatusRow("State", stateLabel(current.state))
                TaraStatusRow("Node A report", evidenceLabel(current.nodeAObserved))
                TaraStatusRow("Gateway/DB update", evidenceLabel(current.unitMarked))
                TaraStatusRow("Node B report", evidenceLabel(current.nodeBObserved))
                TaraStatusRow("Attempts at Node B", current.attempts.toString())
                if (current.progressMessage.isNotBlank()) {
                    Text(current.progressMessage, style = MaterialTheme.typography.bodySmall)
                }
                if (current.expires.isNotBlank()) TaraStatusRow("Expires", current.expires)
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

private fun evidenceLabel(received: Boolean): String =
    if (received) "🟢 Received" else "⚪ Waiting"

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
