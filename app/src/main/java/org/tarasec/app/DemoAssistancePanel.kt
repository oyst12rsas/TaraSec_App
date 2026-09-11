package org.tarasec.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
    var gatewayControlBase by remember { mutableStateOf(LocalGateway.baseUrl(activity)) }
    var gatewayRouteMessage by remember { mutableStateOf("Identifying the current TaraSec gateway…") }
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
    var containmentAlertVisible by remember { mutableStateOf(false) }
    var containmentWarnedSessionId by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("Loading available Demo 3 sessions…") }
    var displayedRequestSeconds by remember { mutableStateOf(0) }
    var displayedReleaseSeconds by remember { mutableStateOf(0) }
    var requestSentLocally by remember { mutableStateOf(false) }

    suspend fun refreshAvailable() {
        runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.list(baseUrl) } }
            .onSuccess {
                available = it
                message = if (it.isEmpty()) "No joinable Demo 3 sessions. You can start one." else "${it.size} joinable Demo 3 session(s)."
            }
            .onFailure { message = "Demo 3 unavailable: ${it.message}" }
    }

    LaunchedEffect(baseUrl) {
        refreshAvailable()
        val observed = withContext(Dispatchers.IO) {
            DemoSshClient.observedGateway(baseUrl)
        }
        if (observed.recognized && observed.address.isNotBlank()) {
            gatewayControlBase = "http://${observed.address}"
            gatewayRouteMessage = "${observed.name} · ${observed.address}"
        } else {
            gatewayControlBase = null
            gatewayRouteMessage = observed.message.ifBlank {
                "Connect through a recognized TaraSec gateway."
            }
        }
    }

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

    // A contained participant cannot poll the protected server. Keep the visible
    // countdown moving locally until the automatic release restores connectivity;
    // every successful server response corrects the local clock.
    LaunchedEffect(
        session?.id,
        session?.state,
        session?.secondsRemaining,
        session?.releaseSecondsRemaining
    ) {
        val current = session ?: return@LaunchedEffect
        displayedRequestSeconds = current.secondsRemaining.coerceAtLeast(0)
        requestSentLocally = current.state != "active" || displayedRequestSeconds == 0
        displayedReleaseSeconds = when {
            current.state == "closed" -> 0
            current.state != "active" -> current.releaseSecondsRemaining.coerceAtLeast(0)
            requestSentLocally -> current.containmentSeconds
            else -> 0
        }

        while (current.state != "closed") {
            delay(1000)
            if (!requestSentLocally) {
                displayedRequestSeconds = (displayedRequestSeconds - 1).coerceAtLeast(0)
                if (displayedRequestSeconds == 0) {
                    requestSentLocally = true
                    displayedReleaseSeconds = current.containmentSeconds
                }
            } else {
                displayedReleaseSeconds = (displayedReleaseSeconds - 1).coerceAtLeast(0)
            }
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
            val ownParticipant = current.participants.firstOrNull { it.id == participantId }
            val containmentExpected = participantToken.isNotBlank() &&
                ownParticipant?.severity?.let { it > current.threshold } == true &&
                current.state == "active"

            LaunchedEffect(
                current.id,
                current.state,
                current.secondsRemaining,
                containmentExpected
            ) {
                if (
                    containmentExpected &&
                    current.secondsRemaining in 1..15 &&
                    containmentWarnedSessionId != current.id
                ) {
                    containmentWarnedSessionId = current.id
                    containmentAlertVisible = true
                }
            }

            if (containmentAlertVisible) {
                AlertDialog(
                    onDismissRequest = { containmentAlertVisible = false },
                    title = { Text("TaraSec communication will pause") },
                    text = {
                        Text(
                            "Your severity is above this demo's threshold. At zero, " +
                                "TaraSec will intentionally block this device's communication " +
                                "with the protected server. Status updates will appear frozen " +
                                "until the automatic release restores communication."
                        )
                    },
                    confirmButton = {
                        Button(onClick = { containmentAlertVisible = false }) {
                            Text("Understood")
                        }
                    }
                )
            }

            if (containmentExpected) {
                TaraSectionCard(
                    title = "Containment expected",
                    subtitle = "Your severity exceeds the Request for Assistance threshold"
                ) {
                    Text(
                        if (current.secondsRemaining > 15) {
                            "When the countdown reaches zero, communication with TaraSec's " +
                                "protected server will pause. The app may appear frozen until " +
                                "automatic release."
                        } else {
                            "Warning: communication will pause in ${current.secondsRemaining} " +
                                "seconds. No status updates are expected during containment."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (current.state == "closed") {
                TaraSectionCard(
                    title = "Demo 3 complete",
                    subtitle = "The Request for Assistance has been released"
                ) {
                    Text(
                        "${current.participants.size} joined · ${current.recovered} recovered · " +
                            "${current.connected} connected"
                    )
                    Text(
                        "Temporary demo containment has ended. You can now join an available " +
                            "Demo 3 or start a new one.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                TaraStatusRow("Exercise", current.name)
                TaraStatusRow("State", current.state.uppercase())
                TaraStatusRow("Threshold", "${current.threshold}/10")
                TaraStatusRow("Protected server", current.targetIp)
                TaraStatusRow(
                    "Request for Assistance",
                    if (requestSentLocally || current.state != "active") {
                        "Sent"
                    } else {
                        "in ${displayedRequestSeconds}s"
                    }
                )
                if (requestSentLocally || current.state == "contained" || current.state == "releasing") {
                    TaraStatusRow(
                        "Releasing in",
                        "${displayedReleaseSeconds.coerceAtLeast(0)} seconds"
                    )
                }
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

            if (participantToken.isNotBlank() && current.state != "closed") {
                TaraSectionCard(title = "Your infection severity", subtitle = "Applied to this device on its local TaraSec gateway") {
                    Text("Severity: ${severity.roundToInt()}/10")
                    TaraStatusRow("Gateway", gatewayRouteMessage)
                    Slider(enabled = current.state == "active" && !busy, value = severity, onValueChange = { severity = it }, valueRange = 0f..10f, steps = 9)
                    Button(
                        enabled = current.state == "active" && !busy && gatewayControlBase != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val gateway = gatewayControlBase ?: return@Button
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
                    ) {
                        Text(if (gatewayControlBase == null) "Recognized TaraSec gateway required" else "Apply severity")
                    }

                    if (current.state == "contained" || current.state == "releasing") {
                        OutlinedButton(
                            enabled = !busy && gatewayControlBase != null,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val gateway = gatewayControlBase ?: return@OutlinedButton
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

            if (current.state != "closed") {
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
            }

            if (current.state == "contained" || current.state == "releasing") {
                TaraSectionCard(title = "Observed containment", subtitle = "The server judges the result by actual polling loss and recovery") {
                    Text("${current.participants.size} joined · ${current.silent} currently silent · ${current.recovered} recovered · ${current.connected} polling")
                }
            }

            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                session = null
                participantToken = ""
                participantId = 0
                scope.launch { refreshAvailable() }
            }) { Text(if (current.state == "closed") "Join or start another Demo 3" else "Choose another demo") }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
