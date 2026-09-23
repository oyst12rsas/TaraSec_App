package org.tarasec.app

import android.content.ClipData
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatDemoDuration(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun demo3ParticipantName(nickname: String): String {
    val suffix = " (app)"
    val name = nickname.trim()
    return if (name.isBlank()) "App participant" else name.take(80 - suffix.length) + suffix
}

@Composable
fun DemoAssistancePanel(baseUrl: String, initialGatewayControlBase: String? = null) {
    val activity = LocalContext.current as ComponentActivity
    val clipboard = LocalClipboard.current
    // Only use a gateway already selected and verified by DemoPanel. The
    // ordinary Wi-Fi default route may be a home router such as
    // 192.168.1.1:8080 and must never receive TaraSec control requests.
    var gatewayControlBase by remember(initialGatewayControlBase) {
        mutableStateOf(initialGatewayControlBase)
    }
    var gatewayRouteMessage by remember { mutableStateOf("Identifying the current TaraSec gateway…") }
    val scope = rememberCoroutineScope()

    var available by remember { mutableStateOf<List<DemoAssistanceSession>>(emptyList()) }
    var session by remember { mutableStateOf<DemoAssistanceSession?>(null) }
    var participantToken by rememberSaveable(baseUrl) { mutableStateOf("") }
    var controllerToken by rememberSaveable(baseUrl) { mutableStateOf("") }
    var participantId by rememberSaveable(baseUrl) { mutableStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var newDemoName by remember { mutableStateOf("") }
    var groupLabel by remember { mutableStateOf("") }
    var groupCode by remember { mutableStateOf("") }
    var delaySeconds by remember { mutableStateOf(120) }
    var containmentSeconds by remember { mutableStateOf(120) }
    var busy by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var copyingDebug by remember { mutableStateOf(false) }
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
    var containmentPollingAttempts by remember { mutableStateOf(0) }
    var containmentPollingSuccesses by remember { mutableStateOf(0) }
    var containmentPollingFailures by remember { mutableStateOf(0) }
    var firstContainmentFailureEpochMs by remember { mutableStateOf<Long?>(null) }
    var firstPostReleaseSuccessEpochMs by remember { mutableStateOf<Long?>(null) }
    var appliedLocalSeverity by remember { mutableStateOf<Int?>(null) }

    fun resetLocalDemoState() {
        session = null
        participantToken = ""
        controllerToken = ""
        participantId = 0
        containmentAlertVisible = false
        containmentWarnedSessionId = null
        displayedRequestSeconds = 0
        displayedReleaseSeconds = 0
        requestSentLocally = false
        participantAgeTick = 0
        heartbeatAttempts = 0
        heartbeatSuccesses = 0
        heartbeatFailures = 0
        lastHeartbeatAttemptEpochMs = null
        lastHeartbeatSuccessEpochMs = null
        lastHeartbeatError = ""
        containmentPollingAttempts = 0
        containmentPollingSuccesses = 0
        containmentPollingFailures = 0
        firstContainmentFailureEpochMs = null
        firstPostReleaseSuccessEpochMs = null
        appliedLocalSeverity = null
    }

    suspend fun refreshAvailable() {
        runCatching { withContext(Dispatchers.IO) { DemoAssistanceClient.list(baseUrl, groupCode) } }
            .onSuccess {
                available = it
                message = if (it.isEmpty()) "No joinable Demo 3 sessions. You can start one." else "${it.size} joinable Demo 3 session(s)."
            }
            .onFailure { message = "Demo 3 unavailable: ${it.message}" }
    }

    LaunchedEffect(baseUrl) {
        // Do not clear local infection state to regain DB access here.  The DB
        // endpoint is intentionally unreachable while an active Request for
        // Assistance contains this unit.  Automatic cleanup would bypass the
        // containment that Demo 3 is meant to demonstrate.
        refreshAvailable()
        val observed = withContext(Dispatchers.IO) {
            DemoSshClient.observedGateway(baseUrl)
        }
        if (observed.recognized && observed.address.isNotBlank()) {
            gatewayControlBase = "http://${observed.address}"
            gatewayRouteMessage = "${observed.name} · ${observed.address}"
        } else {
            val selectedGateway = gatewayControlBase
            gatewayRouteMessage = if (selectedGateway != null) {
                "Selected gateway · ${selectedGateway.removePrefix("http://").removePrefix("https://")}; " +
                    "DB discovery unavailable while traffic may be contained"
            } else {
                observed.message.ifBlank { "Connect through a recognized TaraSec gateway." }
            }
        }
    }

    LaunchedEffect(session?.id, participantToken) {
        val id = session?.id ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            if (leaving) continue
            val token = participantToken
            val stateAtAttempt = session?.state
            heartbeatAttempts += 1
            if (stateAtAttempt == "contained") containmentPollingAttempts += 1
            lastHeartbeatAttemptEpochMs = System.currentTimeMillis()
            runCatching {
                withContext(Dispatchers.IO) {
                    if (token.isNotBlank()) DemoAssistanceClient.heartbeat(baseUrl, id, token)
                    else DemoAssistanceClient.status(baseUrl, id, groupCode)
                }
            }.onSuccess {
                val successEpochMs = System.currentTimeMillis()
                if (stateAtAttempt == "contained" && it.state == "contained") {
                    containmentPollingSuccesses += 1
                }
                if (
                    firstPostReleaseSuccessEpochMs == null &&
                    (it.releaseRequestId ?: 0) > 0 &&
                    (it.state == "releasing" || it.state == "closed")
                ) {
                    firstPostReleaseSuccessEpochMs = successEpochMs
                }
                session = it
                // The server response contains a fresh secondsSinceSeen snapshot for
                // every participant. Restart the local age ticker from that snapshot;
                // otherwise participantAgeTick keeps accumulating even while polling
                // succeeds and makes "last successful contact" appear stale.
                participantAgeTick = 0
                heartbeatSuccesses += 1
                lastHeartbeatSuccessEpochMs = successEpochMs
                lastHeartbeatError = ""
            }.onFailure {
                val failureEpochMs = System.currentTimeMillis()
                heartbeatFailures += 1
                if (stateAtAttempt == "contained") {
                    containmentPollingFailures += 1
                    if (firstContainmentFailureEpochMs == null) {
                        firstContainmentFailureEpochMs = failureEpochMs
                    }
                }
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
                            controllerToken = ""
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
                                    gatewayControlBase
                                        ?: error("Connect through a recognized TaraSec gateway first")
                                    val created = DemoAssistanceClient.create(
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
                                    val joined = DemoAssistanceClient.join(
                                        baseUrl,
                                        created.session.id,
                                        demo3ParticipantName(nickname),
                                        groupCode.trim()
                                    )
                                    created to joined
                                }
                            }.onSuccess { (created, joined) ->
                                session = joined.session
                                controllerToken = created.controllerToken
                                participantToken = joined.participantToken
                                participantId = joined.participantId
                                message = "${created.session.name} started and this unit joined demo #${created.session.id}. Choose whether this unit is CLEAN or INFECTED."
                            }.onFailure { message = "Could not start and join Demo 3: ${it.message}" }
                            busy = false
                        }
                    }
                ) { Text("Start new Demo 3") }
            }
        } else {
            val ownParticipant = current.participants.firstOrNull { it.id == participantId }
            val ownInfected = ownParticipant?.severity?.let { it > current.threshold } == true
            val containmentExpected = participantToken.isNotBlank() &&
                ownInfected &&
                current.state == "active"
            val assistanceRequestActive =
                (current.assistanceRequestId ?: 0) > 0 && current.state == "contained"
            val demoFinished = current.state == "closed"
            val localBlockedSeconds = if (
                ownInfected &&
                requestSentLocally &&
                lastHeartbeatSuccessEpochMs != null
            ) {
                ((System.currentTimeMillis() - lastHeartbeatSuccessEpochMs!!) / 1000L)
                    .coerceAtLeast(0L).toInt()
            } else 0

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

            // Infection is classification only. Keep the local gateway clean until
            // the server confirms that a Request for Assistance is active; that
            // request, not choosing INFECTED, is the containment boundary.
            val desiredLocalSeverity = if (ownInfected && assistanceRequestActive) 10 else 0
            LaunchedEffect(current.id, desiredLocalSeverity, gatewayControlBase) {
                val gateway = gatewayControlBase ?: return@LaunchedEffect
                if (appliedLocalSeverity == desiredLocalSeverity) return@LaunchedEffect
                runCatching {
                    withContext(Dispatchers.IO) {
                        DemoAssistanceClient.setLocalSeverity(gateway, desiredLocalSeverity)
                    }
                }.onSuccess {
                    appliedLocalSeverity = desiredLocalSeverity
                    message = when {
                        desiredLocalSeverity > 0 ->
                            "Request for Assistance is active. This INFECTED unit is now contained."
                        ownInfected && current.state == "releasing" ->
                            "Release sent by the DB server. Polling should resume; post-release observation is in progress."
                        ownInfected && current.state == "closed" ->
                            "Demo complete. The DB server sent the release and polling should be restored."
                        else -> message
                    }
                }.onFailure {
                    message = "Could not synchronize local demo status: ${it.message}"
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
                if (ownInfected && requestSentLocally) {
                    TaraStatusRow("Network", if (heartbeatFailures > 0) "🔴 INFECTED · blocked for ${localBlockedSeconds}s" else "🔴 INFECTED · waiting for network block")
                } else if (ownParticipant?.severity != null && ownParticipant.severity <= current.threshold) {
                    TaraStatusRow("Network", "🟢 CLEAN · polling should continue")
                }
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
                            "Timer complete · waiting for DB release confirmation"
                        }
                    )
                } else if (current.state == "releasing") {
                    TaraStatusRow(
                        "Containment completed",
                        "Connection restored as expected · review remains open for " +
                            formatDemoDuration(current.observationSecondsRemaining)
                    )
                    Text(
                        "TaraSec blocked infected traffic while assistance was active. " +
                            "Assistance is now cancelled, so successful polling is expected again. " +
                            "The demo closes after the review period or when all participants leave.",
                        style = MaterialTheme.typography.bodySmall
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
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        gatewayControlBase
                                            ?: error("Connect through a recognized TaraSec gateway first")
                                        DemoAssistanceClient.join(
                                            baseUrl,
                                            current.id,
                                            demo3ParticipantName(nickname),
                                            groupCode.trim()
                                        )
                                    }
                                }
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
                    subtitle = "Classification now · containment only after assistance is requested"
                ) {
                    val ownInfectedForStatus = ownParticipant?.severity?.let { it > 0 }
                    TaraStatusRow(
                        "Status",
                        when (ownInfectedForStatus) {
                            true -> "🔴 INFECTED"
                            false -> "🟢 CLEAN"
                            null -> "Not selected"
                        }
                    )
                    TaraStatusRow("Gateway", gatewayRouteMessage)
                    Text(
                        "CLEAN or INFECTED records this unit's demo classification. It does not " +
                            "block polling by itself. When a Request for Assistance becomes active, " +
                            "the local gateway contains an INFECTED unit. There are currently no other " +
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
                                    // Always clear any stale local demo tag first. The
                                    // reconciliation effect applies INFECTED only after
                                    // an assistance request is confirmed active.
                                    DemoAssistanceClient.setLocalSeverity(gateway, 0)
                                    DemoAssistanceClient.setSeverity(
                                        baseUrl,
                                        current.id,
                                        participantToken,
                                        selectedSeverity
                                    )
                                }
                            }.onSuccess {
                                session = it
                                appliedLocalSeverity = 0
                                message = if (infected) {
                                    "This unit is marked INFECTED. Polling continues until a Request for Assistance is active."
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
                    val infected = p.severity?.let { it > 0 }
                    val expected = p.severity?.let { it > current.threshold } == true
                    val lastContactAge = p.secondsSinceSeen?.plus(participantAgeTick)
                    val lastContact = lastContactAge?.let {
                        "$it second" + if (it == 1) " ago" else "s ago"
                    }
                    val infectionState = when (infected) {
                        true -> "🔴 INFECTED"
                        false -> "🟢 CLEAN"
                        null -> "⚪ NOT SELECTED"
                    }
                    val connectionState = when {
                        p.decision == "pending" || lastContact == null ->
                            "WAITING · no poll received yet"
                        p.decision == "left" ->
                            "LEFT DEMO · last contact $lastContact"
                        p.decision == "silent" && current.state == "releasing" ->
                            "WAITING FOR CONNECTION RESTORE · last successful contact $lastContact"
                        p.decision == "silent" ->
                            "NO RESPONSE · expected containment · last successful contact $lastContact"
                        lastContactAge > 6 ->
                            if (expected && current.state == "contained") {
                                "NO RESPONSE · expected containment · last successful contact $lastContact"
                            } else {
                                "NO RESPONSE · unexpected · last successful contact $lastContact"
                            }
                        p.decision == "recovered" ->
                            "CONNECTION RESTORED · expected after release · last contact $lastContact"
                        p.decision == "connected" && expected && current.state == "releasing" ->
                            "CONNECTION RESTORED · expected after release · last contact $lastContact"
                        p.decision == "connected" && expected && current.state == "contained" ->
                            "STILL REACHABLE · unexpected during containment · last contact $lastContact"
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
                        it.decision != "left" &&
                            it.secondsSinceSeen?.plus(participantAgeTick)?.let { age -> age <= 6 } == true
                    }
                    val unresponsive = current.participants.count {
                        it.decision != "left" &&
                            it.secondsSinceSeen?.plus(participantAgeTick)?.let { age -> age > 6 } == true
                    }
                    val left = current.participants.count { it.decision == "left" }
                    Text("${current.participants.size} joined · $responsive responding · $unresponsive without recent contact · $left left · ${current.recovered} recovered")
                }
            }



            OutlinedButton(
                enabled = !busy && !leaving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val token = participantToken
                    if (!demoFinished && token.isNotBlank()) {
                        leaving = true
                        scope.launch {
                            gatewayControlBase?.let { gateway ->
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        DemoAssistanceClient.setLocalSeverity(gateway, 0)
                                    }
                                }
                            }
                            var leaveError = "No response from the demo server"
                            var leftSuccessfully = false
                            repeat(6) {
                                if (!leftSuccessfully) {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            DemoAssistanceClient.leave(baseUrl, current.id, token)
                                        }
                                    }.onSuccess {
                                        leftSuccessfully = true
                                    }.onFailure {
                                        leaveError = it.message ?: it.javaClass.simpleName
                                    }
                                    if (!leftSuccessfully) delay(2000)
                                }
                            }
                            if (leftSuccessfully) {
                                resetLocalDemoState()
                                message = "You left Demo 3. You can join or start another demo now."
                                refreshAvailable()
                            } else {
                                message = "Could not record that you left: $leaveError. The shared demo was not closed."
                            }
                            leaving = false
                        }
                    } else {
                        resetLocalDemoState()
                        message = if (demoFinished) {
                            "Returned to the Demo 3 list."
                        } else {
                            "You left this screen. The shared demo remains open."
                        }
                        scope.launch { refreshAvailable() }
                    }
                }
            ) {
                Text(
                    when {
                        leaving -> "Leaving…"
                        demoFinished -> "Return to demo list"
                        participantToken.isNotBlank() -> "Leave this demo · others continue"
                        else -> "Return to demo list · demo stays open"
                    }
                )
            }
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !copyingDebug,
            onClick = {
                copyingDebug = true
                message = "Checking the DB server and gateway for the debug report…"
                scope.launch {
                    try {
                        val id = session?.id
                        val gateway = gatewayControlBase
                        // These are read-only status requests. A contained phone
                        // may fail to reach the DB, but its gateway can still reply.
                        val dbResult = runCatching {
                            require(id != null) { "No Demo 3 session selected" }
                            withContext(Dispatchers.IO) {
                                DemoAssistanceClient.status(baseUrl, id, groupCode)
                            }
                        }
                        val gatewayResult = runCatching {
                            require(gateway != null) { "No TaraSec gateway selected" }
                            withContext(Dispatchers.IO) {
                                DemoClient.localThreatStatusBase(gateway)
                            }
                        }
                        val dbNow = dbResult.getOrNull()
                        val gatewayNow = gatewayResult.getOrNull()
                        val report = buildDemoAssistanceDebugReport(
                            baseUrl = baseUrl,
                            gatewayControlBase = gateway,
                            gatewayRouteMessage = gatewayRouteMessage,
                            session = dbNow ?: session,
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
                            containmentPollingAttempts = containmentPollingAttempts,
                            containmentPollingSuccesses = containmentPollingSuccesses,
                            containmentPollingFailures = containmentPollingFailures,
                            firstContainmentFailureEpochMs = firstContainmentFailureEpochMs,
                            firstPostReleaseSuccessEpochMs = firstPostReleaseSuccessEpochMs,
                            message = message,
                            liveCheckedAtEpochMs = System.currentTimeMillis(),
                            liveDbSession = dbNow,
                            liveDbError = dbResult.exceptionOrNull()?.message,
                            liveGatewayStatus = gatewayNow,
                            liveGatewayError = gatewayResult.exceptionOrNull()?.message
                        )
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("Demo 3 debug report", report))
                        )
                        message = "Demo 3 debug report copied with fresh DB and gateway checks."
                    } catch (e: Exception) {
                        message = "Could not copy Demo 3 debug report: ${e.message}"
                    } finally {
                        copyingDebug = false
                    }
                }
            }
        ) { Text(if (copyingDebug) "Checking DB and gateway…" else "Copy debug info for AI") }
        Text(
            "Checks both servers before copying. Unreachable responses are reported separately; tokens and group codes are omitted.",
            style = MaterialTheme.typography.bodySmall
        )

        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}


private fun buildDemoAssistanceDebugReport(
    baseUrl: String,
    gatewayControlBase: String?,
    gatewayRouteMessage: String,
    session: DemoAssistanceSession?,
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
    containmentPollingAttempts: Int,
    containmentPollingSuccesses: Int,
    containmentPollingFailures: Int,
    firstContainmentFailureEpochMs: Long?,
    firstPostReleaseSuccessEpochMs: Long?,
    message: String,
    liveCheckedAtEpochMs: Long,
    liveDbSession: DemoAssistanceSession?,
    liveDbError: String?,
    liveGatewayStatus: DemoThreatStatus?,
    liveGatewayError: String?
): String = buildString {
    appendLine("TaraSec Demo 3 debug report")
    appendLine("ai_background=https://tarasec.org/ai/demo-guide/")
    appendLine("For an AI session unfamiliar with TaraSec: read the ai_background page before interpreting this report.")
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
    appendLine("[live_status]")
    appendLine("checked_at_epoch_ms=" + liveCheckedAtEpochMs)
    appendLine("db_reachable=" + (liveDbSession != null))
    if (liveDbSession != null) {
        appendLine("db_session_state=" + liveDbSession.state)
        appendLine("db_assistance_request_id=" + (liveDbSession.assistanceRequestId ?: 0))
        appendLine("db_release_request_id=" + (liveDbSession.releaseRequestId ?: 0))
        appendLine("db_release_seconds_remaining=" + liveDbSession.releaseSecondsRemaining)
    } else {
        appendLine("db_error=" + (liveDbError ?: "No live DB response"))
        appendLine("session_below=cached")
    }
    appendLine("gateway_reachable=" + (liveGatewayStatus?.reachable == true))
    if (liveGatewayStatus?.reachable == true) {
        appendLine("gateway_infected=" + liveGatewayStatus.infected)
        appendLine("gateway_severity=" + liveGatewayStatus.severity)
        appendLine("gateway_source=" + liveGatewayStatus.source)
        appendLine("gateway_http_code=" + liveGatewayStatus.httpCode)
        appendLine("gateway_note=local phone state; does not prove ASSIST release delivery")
    } else {
        appendLine("gateway_error=" +
            (liveGatewayError ?: liveGatewayStatus?.message ?: "No live gateway response"))
    }
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
    val ownParticipant = session?.participants?.firstOrNull { it.id == participantId }
    val participantInfected = ownParticipant?.severity?.let { severity ->
        session?.let { severity > it.threshold }
    } == true
    val assistanceRequestActive = session?.let {
        (it.assistanceRequestId ?: 0) > 0 && it.state == "contained"
    } == true
    val expectedToBeBlocked = participantTokenPresent && participantInfected && assistanceRequestActive
    val recentSuccessfulPoll = lastHeartbeatSuccessEpochMs?.let {
        System.currentTimeMillis() - it <= 6_000L
    } == true
    val unexpectedPollingSuccess = expectedToBeBlocked && recentSuccessfulPoll
    val localReleaseTimerElapsed = session?.let {
        it.state == "contained" &&
            (it.assistanceRequestId ?: 0) > 0 &&
            (it.releaseRequestId ?: 0) == 0 &&
            displayedReleaseSeconds == 0
    } == true
    // A blocked participant cannot learn whether the DB server released the
    // session. Its last successful response may predate the release deadline.
    val releaseStatusUnverified = localReleaseTimerElapsed && !recentSuccessfulPoll
    val automaticReleaseOverdue = localReleaseTimerElapsed && recentSuccessfulPoll
    val releaseOverdueSecondsApprox = if (automaticReleaseOverdue) {
        lastHeartbeatSuccessEpochMs?.let {
            ((System.currentTimeMillis() - it) / 1000L - (session?.containmentSeconds ?: 0))
                .coerceAtLeast(0L)
        } ?: 0L
    } else 0L

    appendLine("participant_infected=" + participantInfected)
    appendLine("assistance_request_active=" + assistanceRequestActive)
    appendLine("expected_to_be_blocked=" + expectedToBeBlocked)
    appendLine("unexpected_success_while_infected=" + unexpectedPollingSuccess)
    appendLine()
    appendLine("[containment_evidence]")
    appendLine("polling_attempts_during_containment=" + containmentPollingAttempts)
    appendLine("polling_successes_during_containment=" + containmentPollingSuccesses)
    appendLine("polling_failures_during_containment=" + containmentPollingFailures)
    appendLine("first_containment_failure_epoch_ms=" + (firstContainmentFailureEpochMs ?: 0))
    appendLine("first_post_release_success_epoch_ms=" + (firstPostReleaseSuccessEpochMs ?: 0))
    appendLine(
        "containment_observed=" +
            (containmentPollingFailures > 0 && firstContainmentFailureEpochMs != null)
    )
    appendLine(
        "recovery_observed=" +
            (firstPostReleaseSuccessEpochMs != null)
    )
    appendLine()
    appendLine("[expected_behavior]")
    appendLine("After Request for Assistance marks this participant INFECTED, the app MUST keep attempting normal polling.")
    appendLine("Those polling attempts are expected to FAIL because infected traffic to the protected demo server should be rejected.")
    appendLine("After the configured containment period, the DB server MUST issue the release; polling should then succeed again.")
    appendLine("The Android app must never clear its own infection or send the release.")
    appendLine("Do NOT fix containment by stopping polling in the Android app; continued attempts are required to prove network/server blocking.")
    appendLine()
    appendLine("[diagnostic_flags]")
    if (releaseStatusUnverified) {
        appendLine("RELEASE STATUS UNVERIFIED: the local timer ended while DB polling was blocked.")
        appendLine("The cached session cannot show whether the DB server created a release request.")
        appendLine("Check the DB server and gateway delivery before concluding release failed.")
        appendLine("The app must keep polling and must not release itself.")
    } else if (automaticReleaseOverdue) {
        appendLine("ERROR: a recent DB response still reports no release after the local timer elapsed.")
        appendLine("Check DB server release scheduling and gateway delivery.")
        appendLine("The app must keep polling and must not release itself.")
    } else if (unexpectedPollingSuccess) {
        appendLine("BUG: INFECTED PARTICIPANT CAN STILL POLL SUCCESSFULLY")
        appendLine("Expected: polling attempts continue but fail while the assistance request contains this infected participant.")
        appendLine("Observed: a recent poll succeeded although the participant is INFECTED and should be blocked.")
    } else if (expectedToBeBlocked) {
        appendLine("OK: infected participant is expected to be blocked; inspect failures and last successful contact to verify containment.")
    } else {
        appendLine("No active infected-participant containment contradiction detected at report generation time.")
    }
    appendLine()
    appendLine("[session]")
    if (session == null) {
        appendLine("session_id=none")
        appendLine("state=not_started_or_joined")
        appendLine("participants=none")
    } else {
        appendLine("session_id=" + session.id)
        appendLine("name=" + session.name)
        appendLine("state=" + session.state)
        appendLine("target_ip=" + session.targetIp)
        appendLine("visibility=" + session.visibility)
        appendLine("group_label=" + session.groupLabel.ifBlank { "none" })
        appendLine("threshold=" + session.threshold)
        appendLine("containment_seconds=" + session.containmentSeconds)
        appendLine("request_sent_locally=" + requestSentLocally)
        appendLine("request_seconds_remaining=" + displayedRequestSeconds)
        appendLine("release_seconds_remaining=" + displayedReleaseSeconds)
        appendLine("release_overdue=" + automaticReleaseOverdue)
        appendLine("release_status_unverified=" + releaseStatusUnverified)
        appendLine("release_overdue_seconds_approx=" + releaseOverdueSecondsApprox)
        appendLine("observation_seconds_remaining=" + session.observationSecondsRemaining)
        appendLine("observation_window_note=post-release verification only; it must not delay automatic release")
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
    }
    appendLine()
    appendLine("client_message=" + message.ifBlank { "none" })
}
