package org.tarasec.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun DemoAssistancePanel(baseUrl: String) {
    var session by remember { mutableStateOf<DemoAssistanceSession?>(null) }
    var participantToken by remember { mutableStateOf("") }
    var participantId by remember { mutableStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf(0f) }
    var threshold by remember { mutableStateOf(7f) }
    var delaySeconds by remember { mutableStateOf(120) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Looking for an active assistance demo…") }

    suspend fun loadCurrent() {
        runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.current(baseUrl) } }
            .onSuccess {
                session = it
                message = if (it == null) "No active Demo 3 session. Start one when the group is ready." else "Demo 3 is live."
            }
            .onFailure { message = "Demo 3 unavailable: ${it.message}" }
    }

    LaunchedEffect(Unit) { loadCurrent() }
    LaunchedEffect(session?.id) {
        val id = session?.id ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.status(baseUrl, id) } }
                .onSuccess { session = it }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Anyone may join this demo. Participants are listed for everyone by nickname, or by the address observed by the demo server if no nickname is entered.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Each participant chooses a simulated infection severity from 0–10. When the countdown reaches zero, participants at or above the threshold are marked BLOCKED for the exercise.",
            style = MaterialTheme.typography.bodySmall
        )

        val current = session
        if (current == null) {
            TaraSectionCard(title = "Start assistance exercise", subtitle = "Create the shared Demo 3 session") {
                Text("Blocking threshold: ${threshold.roundToInt()}")
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 0f..10f,
                    steps = 9
                )
                Text("Containment countdown: $delaySeconds seconds")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(30, 60, 120).forEach { seconds ->
                        OutlinedButton(onClick = { delaySeconds = seconds }, modifier = Modifier.weight(1f)) {
                            Text(if (delaySeconds == seconds) "✓ ${seconds}s" else "${seconds}s")
                        }
                    }
                }
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        message = "Starting Demo 3…"
                        Thread {
                            runCatching {
                                DemoAssistanceClient.create(
                                    baseUrl,
                                    "Community infection exercise",
                                    threshold.roundToInt(),
                                    delaySeconds
                                )
                            }.onSuccess {
                                session = it.session
                                message = "Demo 3 started. Anyone can join now."
                            }.onFailure { message = "Could not start Demo 3: ${it.message}" }
                            busy = false
                        }.start()
                    }
                ) { Text("Start Demo 3") }
            }
        } else {
            TaraStatusRow("Exercise", current.name)
            TaraStatusRow("State", current.state.uppercase())
            TaraStatusRow("Threshold", "${current.threshold}/10")
            TaraStatusRow(
                "Containment",
                if (current.state == "active") "in ${current.secondsRemaining}s" else "completed"
            )

            if (participantToken.isBlank() && current.state == "active") {
                TaraSectionCard(title = "Join the exercise", subtitle = "Nickname is optional") {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(80) },
                        label = { Text("Nickname (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            message = "Joining Demo 3…"
                            Thread {
                                runCatching { DemoAssistanceClient.join(baseUrl, current.id, nickname.trim()) }
                                    .onSuccess {
                                        participantToken = it.participantToken
                                        participantId = it.participantId
                                        session = it.session
                                        message = "Joined. Choose your infection severity."
                                    }
                                    .onFailure { message = "Could not join: ${it.message}" }
                                busy = false
                            }.start()
                        }
                    ) { Text("Join Demo 3") }
                }
            }

            if (participantToken.isNotBlank()) {
                TaraSectionCard(title = "Your reported infection severity", subtitle = "Self-reported for this demonstration") {
                    Text("Severity: ${severity.roundToInt()}/10")
                    Slider(
                        enabled = current.state == "active" && !busy,
                        value = severity,
                        onValueChange = { severity = it },
                        valueRange = 0f..10f,
                        steps = 9
                    )
                    Button(
                        enabled = current.state == "active" && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            val selected = severity.roundToInt()
                            Thread {
                                runCatching { DemoAssistanceClient.setSeverity(baseUrl, current.id, participantToken, selected) }
                                    .onSuccess {
                                        session = it
                                        message = "Severity $selected reported."
                                    }
                                    .onFailure { message = "Could not update severity: ${it.message}" }
                                busy = false
                            }.start()
                        }
                    ) { Text("Report severity") }
                }
            }

            TaraSectionCard(title = "Participants", subtitle = "Visible on every app watching this demo") {
                if (current.participants.isEmpty()) {
                    Text("Waiting for participants…")
                } else {
                    current.participants.forEach { p ->
                        val label = p.nickname.ifBlank { p.observedIp.ifBlank { "Participant ${p.id}" } }
                        val mine = if (p.id == participantId) " · you" else ""
                        val state = when (p.decision) {
                            "blocked" -> "🔴 BLOCKED"
                            "allowed" -> "🟢 ALLOWED"
                            else -> if (p.severity >= current.threshold) "🟡 exceeds threshold" else "🟢 below threshold"
                        }
                        TaraStatusRow("$label$mine", "${p.severity}/10 · $state")
                    }
                }
            }

            if (current.state == "contained") {
                TaraSectionCard(title = "Request assistance resolved", subtitle = "Cooperative containment result") {
                    Text("${current.participants.size} participants joined · ${current.blocked} blocked · ${current.allowed} remained connected")
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    busy = true
                    Thread {
                        runCatching { DemoAssistanceClient.status(baseUrl, current.id) }
                            .onSuccess { session = it }
                            .onFailure { message = "Refresh failed: ${it.message}" }
                        busy = false
                    }.start()
                }
            ) { Text("Refresh participants") }
        }

        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
