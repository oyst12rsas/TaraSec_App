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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun DemoAssistancePanel(baseUrl: String) {
    val activity = LocalContext.current as ComponentActivity
    val localGatewayBase = remember { LocalGateway.baseUrl(activity) }
    val scope = rememberCoroutineScope()

    var available by remember { mutableStateOf<List<DemoAssistanceSession>>(emptyList()) }
    var session by remember { mutableStateOf<DemoAssistanceSession?>(null) }
    var participantToken by remember { mutableStateOf("") }
    var participantId by remember { mutableStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf(0f) }
    var threshold by remember { mutableStateOf(7f) }
    var delaySeconds by remember { mutableStateOf(120) }
    var containmentSeconds by remember { mutableStateOf(120) }
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

    LaunchedEffect(session?.id, participantToken) {
        val id = session?.id ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            val token = participantToken
            runCatching {
                withContext(Dispatchers.IO) {
                    if (token.isNotBlank()) DemoAssistanceClient.heartbeat(baseUrl, id, token)
                    else DemoAssistanceClient.status(baseUrl, id)
                }
            }.onSuccess { session = it }
            // A failed heartbeat after containment is expected for a contained
            // participant: its traffic to the requesting server is really blocked.
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Anyone may join. Choose a demo with more than 15 seconds remaining, or start a new exercise lasting up to 5 minutes.")
        Text(
            "At zero the demo server issues a real TaraSec Request for Assistance. Units whose local infection severity exceeds the request threshold should lose connectivity to the demo server, so their polling stops.",
            style = MaterialTheme.typography.bodySmall
        )

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
                    onClick = { scope.launch { refreshAvailable() } }
                ) { Text("Refresh available demos") }
            }

            TaraSectionCard(title = "Start a new Demo 3", subtitle = "Request for Assistance is triggered by the countdown") {
                Text("Blocking threshold: ${threshold.roundToInt()}")
                Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0f..10f, steps = 9)

                Text("Request countdown: $delaySeconds seconds")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(30, 120, 300).forEach { seconds ->
                        OutlinedButton(onClick = { delaySeconds = seconds }, modifier = Modifier.weight(1f)) {
                            Text(if (delaySeconds == seconds) "✓ ${seconds}s" else "${seconds}s")
                        }
                    }
                }

                Text("Automatic release after: $containmentSeconds seconds")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(30, 120, 300).forEach { seconds ->
                        OutlinedButton(onClick = { containmentSeconds = seconds }, modifier = Modifier.weight(1f)) {
                            Text(if (containmentSeconds == seconds) "✓ ${seconds}s" else "${seconds}s")
                        }
                    }
                }

                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    DemoAssistanceClient.create(
                                        baseUrl,
                                        "Community infection exercise",
                                        threshold.roundToInt(),
                                        delaySeconds,
                                        containmentSeconds
                                    )
                                }
                            }.onSuccess {
                                session = it.session
                                message = "Demo 3 started. Anyone can join now."
                            }.onFailure { message = "Could not start Demo 3: ${it.message}" }
                            busy = false
                        }
                    }
                ) { Text("Start new Demo 3") }
            }
        } else {
            TaraStatusRow("Exercise", current.name)
            TaraStatusRow("State", current.state.uppercase())
            TaraStatusRow("Threshold", "${current.threshold}/10")
            TaraStatusRow("Protected server", current.targetIp)
            TaraStatusRow(
                "Request for Assistance",
                when (current.state) {
                    "active" -> "in ${current.secondsRemaining}s"
                    else -> current.assistanceRequestId?.let { "issued · request #$it" } ?: "issued"
                }
            )
            if (current.state == "contained" || current.state == "releasing") {
                TaraStatusRow("Automatic release", "in ${current.releaseSecondsRemaining}s")
            } else if (current.state == "closed") {
                TaraStatusRow("Automatic release", "completed")
            }

            if (participantToken.isBlank() && current.state == "active") {
                TaraSectionCard(title = "Join the exercise", subtitle = "Nickname is optional") {
                    OutlinedTextField(nickname, { nickname = it.take(80) }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = !busy && current.secondsRemaining > 15,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.join(baseUrl, current.id, nickname.trim()) } }
                                    .onSuccess {
                                        participantToken = it.participantToken
                                        participantId = it.participantId
                                        session = it.session
                                        message = "Joined. Choose your infection severity."
                                    }
                                    .onFailure { message = "Could not join: ${it.message}" }
                                busy = false
                            }
                        }
                    ) { Text(if (current.secondsRemaining > 15) "Join Demo 3" else "Too late to join") }
                }
            }

            if (participantToken.isNotBlank()) {
                TaraSectionCard(title = "Your infection severity", subtitle = "Applied to this device on its local TaraSec gateway") {
                    Text("Severity: ${severity.roundToInt()}/10")
                    Slider(enabled = current.state == "active" && !busy, value = severity, onValueChange = { severity = it }, valueRange = 0f..10f, steps = 9)
                    Button(
                        enabled = current.state == "active" && !busy && localGatewayBase != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val gateway = localGatewayBase ?: return@Button
                            val selected = severity.roundToInt()
                            busy = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        DemoAssistanceClient.setLocalSeverity(gateway, selected)
                                        DemoAssistanceClient.setSeverity(baseUrl, current.id, participantToken, selected)
                                    }
                                }.onSuccess {
                                    session = it
                                    message = "Severity $selected is now active on the local gateway and registered for Demo 3."
                                }.onFailure { message = "Could not update severity: ${it.message}" }
                                busy = false
                            }
                        }
                    ) { Text(if (localGatewayBase == null) "Local TaraSec gateway required" else "Apply severity") }

                    if (current.state == "contained" || current.state == "releasing") {
                        OutlinedButton(
                            enabled = !busy && localGatewayBase != null,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val gateway = localGatewayBase ?: return@OutlinedButton
                                busy = true
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.setLocalSeverity(gateway, 0) } }
                                        .onSuccess { message = "Self-clearing requested locally. If successful, polling to the demo server will resume automatically." }
                                        .onFailure { message = "Self-clearing failed: ${it.message}" }
                                    busy = false
                                }
                            }
                        ) { Text("Test self-clearing now") }
                    }
                }
            }

            TaraSectionCard(title = "Participants", subtitle = "Watch polling stop for units above the threshold") {
                if (current.participants.isEmpty()) Text("Waiting for participants…")
                current.participants.forEach { p ->
                    val label = p.nickname.ifBlank { p.observedIp.ifBlank { "Participant ${p.id}" } }
                    val mine = if (p.id == participantId) " · you" else ""
                    val expected = p.severity > current.threshold
                    val seen = p.secondsSinceSeen?.let { " · last seen ${it}s ago" } ?: ""
                    val state = when (p.decision) {
                        "silent" -> "🔴 POLLING STOPPED"
                        "recovered" -> "🟢 RECOVERED"
                        "connected" -> if (expected && current.state != "active") "🟡 still polling" else "🟢 polling"
                        else -> if (expected) "🟡 will exceed threshold" else "🟢 below threshold"
                    }
                    TaraStatusRow("$label$mine", "${p.severity}/10 · $state$seen")
                }
            }

            if (current.state == "contained" || current.state == "closed") {
                TaraSectionCard(title = "Observed containment", subtitle = "The server judges the result by actual polling loss and recovery") {
                    Text("${current.participants.size} joined · ${current.silent} currently silent · ${current.recovered} recovered · ${current.connected} polling")
                }
            }

            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                session = null
                participantToken = ""
                participantId = 0
                scope.launch { refreshAvailable() }
            }) { Text("Choose another demo") }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
