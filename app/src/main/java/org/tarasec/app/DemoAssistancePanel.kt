package org.tarasec.app

import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DemoAssistancePanel(baseUrl: String) {
    val activity = LocalContext.current as ComponentActivity
    val clipboard = LocalClipboardManager.current
    var gatewayControlBase by remember { mutableStateOf(LocalGateway.baseUrl(activity)) }
    var gatewayRouteMessage by remember { mutableStateOf("Identifying the current TaraSec gateway…") }
    val scope = rememberCoroutineScope()

    var available by remember { mutableStateOf<List<DemoAssistanceSession>>(emptyList()) }
    var session by remember { mutableStateOf<DemoAssistanceSession?>(null) }
    var participantToken by remember { mutableStateOf("") }
    var controllerToken by remember { mutableStateOf("") }
    var participantId by remember { mutableStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var newDemoName by remember { mutableStateOf("") }
    var groupLabel by remember { mutableStateOf("") }
    var groupCode by remember { mutableStateOf("") }
    var delaySeconds by remember { mutableStateOf(120) }
    var containmentSeconds by remember { mutableStateOf(120) }
    var busy by remember { mutableStateOf(false) }
    var containmentAlertVisible by remember { mutableStateOf(false) }
    var containmentWarnedSessionId by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("Loading available Demo 3 sessions…") }
    var displayedRequestSeconds by remember { mutableStateOf(0) }
    var displayedReleaseSeconds by remember { mutableStateOf(0) }
    var requestSentLocally by remember { mutableStateOf(false) }
    var participantAgeTick by remember { mutableStateOf(0) }
    var heartbeatAttempts by remember { mutableStateOf(0) }
    var heartbeatSuccesses by remember { mutableStateOf(0) }
    var heartbeatFailures by remember { mutableStateOf(0) }
    var lastHeartbeatAttemptEpochMs by remember { mutableStateOf<Long?>(null) }
    var lastHeartbeatSuccessEpochMs by remember { mutableStateOf<Long?>(null) }
    var lastHeartbeatError by remember { mutableStateOf("") }

    suspend fun refreshAvailable() {
        runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.list(baseUrl, groupCode) } }
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
            heartbeatAttempts += 1
            lastHeartbeatAttemptEpochMs = System.currentTimeMillis()
            runCatching {
                withContext(Dispatchers.IO) {
                    if (token.isNotBlank()) DemoAssistanceClient.heartbeat(baseUrl, id, token)
                    else DemoAssistanceClient.status(baseUrl, id, groupCode)
                }
            }.onSuccess {
                session = it
                heartbeatSuccesses += 1
                lastHeartbeatSuccessEpochMs = System.currentTimeMillis()
                lastHeartbeatError = ""
            }.onFailure {
                heartbeatFailures += 1
                lastHeartbeatError = it.message ?: it.javaClass.simpleName
            }
            // A failed heartbeat after containment is expected for a contained
            // participant: its traffic to the requesting server is really blocked.
        }
    }

    // A contained participant cannot poll the protected server. Keep the visible
    // observation timer moving locally until the controller chooses to release;
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

    LaunchedEffect(
        session?.id,
        session?.participants?.map {
            Triple(it.id, it.decision, it.secondsSinceSeen)
        }
    ) {
        participantAgeTick = 0
        while (session?.state != "closed") {
            delay(1000)
            participantAgeTick += 1
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Anyone may join. Choose a demo with more than 15 seconds remaining, or start a new exercise lasting up to 5 minutes.")
        Text(
            "At zero the demo server issues a real TaraSec Request for Assistance. Units marked INFECTED should lose connectivity to the demo server, so their polling stops.",
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
                        Text("${demo.name}${if (demo.groupLabel.isBlank()) "" else " · ${demo.groupLabel}"} · #${demo.id} · ${demo.secondsRemaining}s left")
                    }
                }
                OutlinedTextField(
                    value = groupCode,
                    onValueChange = { groupCode = it.take(64) },
                    label = { Text("University/group code (optional)") },
                    supportingText = { Text("Enter the shared code, then refresh to reveal that group's demos.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { refreshAvailable() } }
                ) { Text("Refresh available demos") }
            }

            TaraSectionCard(title = "Start a new Demo 3", subtitle = "Give it a name others can recognize") {
                Text("Participants choose CLEAN or INFECTED. Infected units are contained when the request is sent.")
                OutlinedTextField(
                    value = newDemoName,
                    onValueChange = { newDemoName = it.take(120) },
                    label = { Text("Demo name (optional)") },
                    placeholder = { Text("For example: UiA Grimstad – Table 2") },
                    supportingText = {
                        Text("Include a location, class or group if several demos may be running.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = groupLabel,
                    onValueChange = { groupLabel = it.take(120) },
                    label = { Text("University or group (optional)") },
                    placeholder = { Text("For example: UiA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = groupCode,
                    onValueChange = { groupCode = it.take(64) },
                    label = { Text("Private group code (optional)") },
                    supportingText = { Text("With a code, only participants using the same code can discover or join this demo.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Request countdown: $delaySeconds seconds")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(30, 120, 300).forEach { seconds ->
                        OutlinedButton(onClick = { delaySeconds = seconds }, modifier = Modifier.weight(1f)) {
                            Text(if (delaySeconds == seconds) "✓ ${seconds}s" else "${seconds}s")
                        }
                    }
                }

                Text("Suggested observation before release: $containmentSeconds seconds")
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
                                        newDemoName.trim().ifBlank {
                                            "Community infection exercise"
                                        },
                                        5,
                                        delaySeconds,
                                        containmentSeconds,
                                        groupLabel.trim(),
                                        groupCode.trim()
                                    )
                                }
                            }.onSuccess {
                                session = it.session
                                controllerToken = it.controllerToken
                                message = "${it.session.name} started. Others can now find and join demo #${it.session.id}."
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
            val demoFinished = current.state == "closed"

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

            if (containmentAlertVisible && !demoFinished) {
                AlertDialog(
                    onDismissRequest = { containmentAlertVisible = false },
                    title = { Text("TaraSec communication will pause") },
                    text = {
                        Text(
                            "This unit is marked INFECTED. At zero, " +
                                "TaraSec will intentionally block this device's communication " +
                                "with the protected server. Status updates will appear frozen " +
                                "until the controller requests release and communication returns."
                        )
                    },
                    confirmButton = {
                        Button(onClick = { containmentAlertVisible = false }) {
                            Text("Understood")
                        }
                    }
                )
            }

            if (containmentExpected && !demoFinished) {
                TaraSectionCard(
                    title = "Containment expected",
                    subtitle = "This unit is marked INFECTED"
                ) {
                    Text(
                        if (current.secondsRemaining > 15) {
                            "When the countdown reaches zero, communication with TaraSec's " +
                                "protected server will pause. The app may appear frozen until " +
                                "the controller explicitly requests release."
                        } else {
                            "Warning: communication will pause in ${current.secondsRemaining} " +
                                "seconds. No status updates are expected during containment."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (demoFinished) {
                TaraSectionCard(
                    title = "Demo 3 is over",
                    subtitle = "Release was explicitly requested"
                ) {
                    Text(
                        "The session is closed, but no clean result is assumed. Review the " +
                            "observed participant contact history below or copy the debug report."
                    )
                }
            } else {
                TaraStatusRow("Exercise", current.name)
                TaraStatusRow("State", current.state.uppercase())
                TaraStatusRow("Protected server", current.targetIp)
                TaraStatusRow(
                    "Request for Assistance",
                    if (requestSentLocally || current.state != "active") {
                        "Sent"
                    } else {
                        "in ${displayedRequestSeconds}s"
                    }
                )
                if (current.state == "contained") {
                    TaraStatusRow(
                        "Observation",
                        if (displayedReleaseSeconds > 0) {
                            "${displayedReleaseSeconds.coerceAtLeast(0)} seconds before suggested release"
                        } else {
                            "Timer complete · waiting for explicit release"
                        }
                    )
                } else if (current.state == "releasing") {
                    TaraStatusRow(
                        "Release",
                        "Requested · waiting for observed contact to return"
                    )
                }
            }

            if (!demoFinished && current.state == "contained" && controllerToken.isNotBlank()) {
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    DemoAssistanceClient.release(baseUrl, current.id, controllerToken)
                                }
                            }.onSuccess {
                                session = it
                                message = "Release requested. Demo 3 will remain observable until contact recovery is recorded."
                            }.onFailure {
                                message = "Could not release assistance: ${it.message}"
                            }
                            busy = false
                        }
                    }
                ) { Text("Release assistance and observe recovery") }
            }

            if (!demoFinished && participantToken.isBlank() && current.state == "active") {
                TaraSectionCard(title = "Join the exercise", subtitle = "Nickname is optional") {
                    OutlinedTextField(nickname, { nickname = it.take(80) }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = !busy && current.secondsRemaining > 15,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.join(baseUrl, current.id, nickname.trim(), groupCode.trim()) } }
                                    .onSuccess {
                                        participantToken = it.participantToken
                                        participantId = it.participantId
                                        session = it.session
                                        message = "Joined. Choose whether this unit is CLEAN or INFECTED."
                                    }
                                    .onFailure { message = "Could not join: ${it.message}" }
                                busy = false
                            }
                        }
                    ) { Text(if (current.secondsRemaining > 15) "Join Demo 3" else "Too late to join") }
                }
            }

            if (!demoFinished && participantToken.isNotBlank()) {
                TaraSectionCard(
                    title = "This unit's demo status",
                    subtitle = "Stored on the local TaraSec gateway"
                ) {
                    val ownInfected = ownParticipant?.severity?.let { it > 0 }
                    TaraStatusRow(
                        "Status",
                        when (ownInfected) {
                            true -> "🔴 INFECTED"
                            false -> "🟢 CLEAN"
                            null -> "Not selected"
                        }
                    )
                    TaraStatusRow("Gateway", gatewayRouteMessage)
                    Text(
                        "Unless a technical error occurs, this status tags traffic from this " +
                            "unit to other TaraSec participants. There are currently no other " +
                            "TaraSec participants in this demonstration. The Request for " +
                            "Assistance contains only traffic to the protected demo server. " +
                            "Regular Internet traffic may use the hotspot, mobile data, or be " +
                            "unavailable, depending on the phone and hotspot routing.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    fun applyStatus(infected: Boolean) {
                        val gateway = gatewayControlBase ?: return
                        val selectedSeverity = if (infected) 10 else 0
                        busy = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    DemoAssistanceClient.setLocalSeverity(gateway, selectedSeverity)
                                    DemoAssistanceClient.setSeverity(
                                        baseUrl,
                                        current.id,
                                        participantToken,
                                        selectedSeverity
                                    )
                                }
                            }.onSuccess {
                                session = it
                                message = if (infected) {
                                    "This unit is marked INFECTED for Demo 3."
                                } else {
                                    "This unit is marked CLEAN for Demo 3."
                                }
                            }.onFailure {
                                message = "Could not update demo status: ${it.message}"
                            }
                            busy = false
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            enabled = current.state == "active" && !busy &&
                                gatewayControlBase != null,
                            modifier = Modifier.weight(1f),
                            onClick = { applyStatus(false) }
                        ) { Text("Set CLEAN") }
                        Button(
                            enabled = current.state == "active" && !busy &&
                                gatewayControlBase != null,
                            modifier = Modifier.weight(1f),
                            onClick = { applyStatus(true) }
                        ) { Text("Set INFECTED") }
                    }
                    if (gatewayControlBase == null) {
                        Text(
                            "Connect through a recognized TaraSec gateway to set the status.",
                            style = MaterialTheme.typography.bodySmall
                        )
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

            if (!demoFinished || current.participants.isNotEmpty()) {
                TaraSectionCard(title = "Participants", subtitle = "Observed contact evidence remains visible after release") {
                if (current.participants.isEmpty()) Text("Waiting for participants…")
                current.participants.forEach { p ->
                    val label = p.nickname.ifBlank { p.observedIp.ifBlank { "Participant ${p.id}" } }
                    val mine = if (p.id == participantId) " · you" else ""
                    val infected = p.severity > 0
                    val expected = p.severity > current.threshold
                    val lastContactAge = p.secondsSinceSeen?.plus(participantAgeTick)
                    val lastContact = lastContactAge?.let {
                        "$it second" + if (it == 1) " ago" else "s ago"
                    }
                    val infectionState = if (infected) "🔴 INFECTED" else "🟢 CLEAN"
                    val connectionState = when {
                        p.decision == "pending" || lastContact == null ->
                            "WAITING · no poll received yet"
                        p.decision == "silent" ->
                            "NO RESPONSE · last successful contact $lastContact"
                        lastContactAge != null && lastContactAge > 6 ->
                            if (expected && current.state != "active") {
                                "NO RESPONSE · expected containment · last successful contact $lastContact"
                            } else {
                                "NO RESPONSE · unexpected · last successful contact $lastContact"
                            }
                        p.decision == "recovered" ->
                            "CONNECTION RESTORED · last contact $lastContact"
                        p.decision == "connected" && expected && current.state != "active" ->
                            "STILL REACHABLE · last contact $lastContact"
                        p.decision == "connected" ->
                            "POLLING · response received · last contact $lastContact"
                        expected -> "WAITING · will be contained"
                        else -> "WAITING · will remain connected"
                    }
                    TaraStatusRow("$label$mine", "$infectionState · $connectionState")
                    }
                }
            }

            if (current.state == "contained" || current.state == "releasing" || current.state == "closed") {
                TaraSectionCard(title = "Observed containment", subtitle = "The server judges the result by actual polling loss and recovery") {
                    val responsive = current.participants.count {
                        it.secondsSinceSeen?.plus(participantAgeTick)?.let { age -> age <= 6 } == true
                    }
                    val unresponsive = current.participants.count {
                        it.secondsSinceSeen?.plus(participantAgeTick)?.let { age -> age > 6 } == true
                    }
                    Text("${current.participants.size} joined · $responsive responding · $unresponsive without recent contact · ${current.recovered} recovered")
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val report = buildDemoAssistanceDebugReport(
                        baseUrl = baseUrl,
                        gatewayControlBase = gatewayControlBase,
                        gatewayRouteMessage = gatewayRouteMessage,
                        session = current,
                        participantId = participantId,
                        participantTokenPresent = participantToken.isNotBlank(),
                        controllerTokenPresent = controllerToken.isNotBlank(),
                        participantAgeTick = participantAgeTick,
                        displayedRequestSeconds = displayedRequestSeconds,
                        displayedReleaseSeconds = displayedReleaseSeconds,
                        requestSentLocally = requestSentLocally,
                        heartbeatAttempts = heartbeatAttempts,
                        heartbeatSuccesses = heartbeatSuccesses,
                        heartbeatFailures = heartbeatFailures,
                        lastHeartbeatAttemptEpochMs = lastHeartbeatAttemptEpochMs,
                        lastHeartbeatSuccessEpochMs = lastHeartbeatSuccessEpochMs,
                        lastHeartbeatError = lastHeartbeatError,
                        message = message
                    )
                    clipboard.setText(AnnotatedString(report))
                    message = "Demo 3 debug report copied."
                }
            ) { Text("Copy debug info for AI") }
            Text(
                "Copies connection and heartbeat state without participant tokens or group codes.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                session = null
                participantToken = ""
                controllerToken = ""
                participantId = 0
                containmentAlertVisible = false
                containmentWarnedSessionId = null
                scope.launch { refreshAvailable() }
            }) { Text(if (demoFinished) "Reset" else "Choose another demo") }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}


private fun buildDemoAssistanceDebugReport(
    baseUrl: String,
    gatewayControlBase: String?,
    gatewayRouteMessage: String,
    session: DemoAssistanceSession,
    participantId: Int,
    participantTokenPresent: Boolean,
    controllerTokenPresent: Boolean,
    participantAgeTick: Int,
    displayedRequestSeconds: Int,
    displayedReleaseSeconds: Int,
    requestSentLocally: Boolean,
    heartbeatAttempts: Int,
    heartbeatSuccesses: Int,
    heartbeatFailures: Int,
    lastHeartbeatAttemptEpochMs: Long?,
    lastHeartbeatSuccessEpochMs: Long?,
    lastHeartbeatError: String,
    message: String
): String = buildString {
    appendLine("TaraSec Demo 3 debug report")
    appendLine("generated_at_epoch_ms=" + System.currentTimeMillis())
    appendLine("app_version=" + BuildConfig.VERSION_NAME)
    appendLine("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT)
    appendLine("secrets=omitted (participant token and group code)")
    appendLine()
    appendLine("[connection]")
    appendLine("db_endpoint=" + baseUrl.ifBlank { "not selected" })
    appendLine("gateway_control_endpoint=" + gatewayControlBase.orEmpty().ifBlank { "unavailable" })
    appendLine("gateway=" + gatewayRouteMessage.ifBlank { "unknown" })
    appendLine()
    appendLine("[polling]")
    appendLine("participant_id=" + participantId)
    appendLine("participant_token_present=" + participantTokenPresent)
    appendLine("controller_token_present=" + controllerTokenPresent)
    appendLine("attempts=" + heartbeatAttempts)
    appendLine("successes=" + heartbeatSuccesses)
    appendLine("failures=" + heartbeatFailures)
    appendLine("last_attempt_epoch_ms=" + (lastHeartbeatAttemptEpochMs ?: 0))
    appendLine("last_success_epoch_ms=" + (lastHeartbeatSuccessEpochMs ?: 0))
    appendLine("last_error=" + lastHeartbeatError.ifBlank { "none" })
    appendLine()
    appendLine("[session]")
    appendLine("session_id=" + session.id)
    appendLine("name=" + session.name)
    appendLine("state=" + session.state)
    appendLine("target_ip=" + session.targetIp)
    appendLine("visibility=" + session.visibility)
    appendLine("group_label=" + session.groupLabel.ifBlank { "none" })
    appendLine("threshold=" + session.threshold)
    appendLine("request_sent_locally=" + requestSentLocally)
    appendLine("request_seconds_remaining=" + displayedRequestSeconds)
    appendLine("release_seconds_remaining=" + displayedReleaseSeconds)
    appendLine("assistance_request_id=" + (session.assistanceRequestId ?: 0))
    appendLine("release_request_id=" + (session.releaseRequestId ?: 0))
    appendLine()
    appendLine("[participants]")
    session.participants.forEach { participant ->
        val age = participant.secondsSinceSeen?.plus(participantAgeTick)
        appendLine(
            "id=" + participant.id +
                ",nickname=" + participant.nickname.ifBlank { "none" } +
                ",observed_ip=" + participant.observedIp.ifBlank { "unknown" } +
                ",severity=" + participant.severity +
                ",decision=" + participant.decision +
                ",seconds_since_last_contact=" + (age?.toString() ?: "never")
        )
    }
    appendLine()
    appendLine("client_message=" + message.ifBlank { "none" })
}
