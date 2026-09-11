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
    var available by remember { mutableStateOf<List<DemoAssistanceSession>>(emptyList()) }
    var session by remember { mutableStateOf<DemoAssistanceSession?>(null) }
    var participantToken by remember { mutableStateOf("") }
    var participantId by remember { mutableStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf(0f) }
    var threshold by remember { mutableStateOf(7f) }
    var delaySeconds by remember { mutableStateOf(120) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Loading available Demo 3 sessions…") }

    suspend fun refreshAvailable() {
        runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.list(baseUrl) } }
            .onSuccess {
                available = it
                message = if (it.isEmpty()) "No joinable Demo 3 sessions. You can start one." else "${it.size} joinable Demo 3 session(s)."
            }
            .onFailure { message = "Demo 3 unavailable: ${it.message}" }
    }

    LaunchedEffect(Unit) { refreshAvailable() }
    LaunchedEffect(session?.id) {
        val id = session?.id ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.status(baseUrl, id) } }
                .onSuccess { session = it }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Anyone may join. Choose a server demo with more than 15 seconds remaining, or start a new exercise lasting up to 5 minutes.")

        val current = session
        if (current == null) {
            TaraSectionCard(title = "Available assistance demos", subtitle = "Only demos with more than 15 seconds left are joinable") {
                if (available.isEmpty()) Text("No joinable demos right now.")
                available.forEach { demo ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            session = demo
                            participantToken = ""
                            participantId = 0
                            message = "Selected ${demo.name}."
                        }
                    ) {
                        Text("${demo.name} · threshold ${demo.threshold} · ${demo.secondsRemaining}s left")
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Thread { kotlinx.coroutines.runBlocking { refreshAvailable() } }.start() }
                ) { Text("Refresh available demos") }
            }

            TaraSectionCard(title = "Start a new Demo 3", subtitle = "Containment may be scheduled 15–300 seconds from now") {
                Text("Blocking threshold: ${threshold.roundToInt()}")
                Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0f..10f, steps = 9)
                Text("Containment countdown: $delaySeconds seconds")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(30, 120, 300).forEach { seconds ->
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
                        Thread {
                            runCatching { DemoAssistanceClient.create(baseUrl, "Community infection exercise", threshold.roundToInt(), delaySeconds) }
                                .onSuccess { session = it.session; message = "Demo 3 started. Anyone can join now." }
                                .onFailure { message = "Could not start Demo 3: ${it.message}" }
                            busy = false
                        }.start()
                    }
                ) { Text("Start new Demo 3") }
            }
        } else {
            TaraStatusRow("Exercise", current.name)
            TaraStatusRow("State", current.state.uppercase())
            TaraStatusRow("Threshold", "${current.threshold}/10")
            TaraStatusRow("Containment", if (current.state == "active") "in ${current.secondsRemaining}s" else "completed")

            if (participantToken.isBlank() && current.state == "active") {
                TaraSectionCard(title = "Join the exercise", subtitle = "Nickname is optional") {
                    OutlinedTextField(nickname, { nickname = it.take(80) }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = !busy && current.secondsRemaining > 15,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            Thread {
                                runCatching { DemoAssistanceClient.join(baseUrl, current.id, nickname.trim()) }
                                    .onSuccess { participantToken = it.participantToken; participantId = it.participantId; session = it.session; message = "Joined. Choose your infection severity." }
                                    .onFailure { message = "Could not join: ${it.message}" }
                                busy = false
                            }.start()
                        }
                    ) { Text(if (current.secondsRemaining > 15) "Join Demo 3" else "Too late to join") }
                }
            }

            if (participantToken.isNotBlank()) {
                TaraSectionCard(title = "Your infection severity", subtitle = "Self-reported for this demonstration") {
                    Text("Severity: ${severity.roundToInt()}/10")
                    Slider(enabled = current.state == "active" && !busy, value = severity, onValueChange = { severity = it }, valueRange = 0f..10f, steps = 9)
                    Button(
                        enabled = current.state == "active" && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            val selected = severity.roundToInt()
                            Thread {
                                runCatching { DemoAssistanceClient.setSeverity(baseUrl, current.id, participantToken, selected) }
                                    .onSuccess { session = it; message = "Severity $selected reported." }
                                    .onFailure { message = "Could not update severity: ${it.message}" }
                                busy = false
                            }.start()
                        }
                    ) { Text("Report severity") }
                }
            }

            TaraSectionCard(title = "Participants", subtitle = "Visible to every app watching this demo") {
                if (current.participants.isEmpty()) Text("Waiting for participants…")
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

            if (current.state == "contained") {
                TaraSectionCard(title = "Request assistance resolved", subtitle = "Cooperative containment result") {
                    Text("${current.participants.size} participants joined · ${current.blocked} blocked · ${current.allowed} remained connected")
                }
            }

            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                session = null; participantToken = ""; participantId = 0
                Thread { kotlinx.coroutines.runBlocking { refreshAvailable() } }.start()
            }) { Text("Choose another demo") }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
