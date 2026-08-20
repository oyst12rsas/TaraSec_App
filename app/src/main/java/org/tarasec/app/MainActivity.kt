package org.tarasec.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
        setContent {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { TaraSecApp() } }
        }
    }
}

private enum class AppPage { SETUP, UNIT_DEMO, MANAGER }

@androidx.compose.runtime.Composable
private fun TaraSecApp() {
    val activity = LocalContext.current as Activity
    var page by remember { mutableStateOf(AppPage.SETUP) }

    // Transport path: for now the App still discovers the VPN-facing gateway via
    // the global DB server. This is deliberately separate from the installation
    // selected for management.
    var dbServer by remember { mutableStateOf("http://100.68.126.0") }
    var discoveredGateway by remember { mutableStateOf("") }
    var setupStatus by remember { mutableStateOf("Not connected") }

    // Persistent managed-installation registry. A candidate is only promoted to
    // this list after manager authentication succeeds on that installation.
    var installations by remember { mutableStateOf(InstallationStore.load(activity)) }
    var selectedInstallationId by remember {
        mutableStateOf(
            InstallationStore.selectedId(activity)
                ?: InstallationStore.load(activity).firstOrNull()?.id
        )
    }
    var registrationName by remember { mutableStateOf("") }
    var registrationBaseUrl by remember { mutableStateOf("") }
    var registrationServiceIp by remember { mutableStateOf("") }

    val selectedInstallation = installations.firstOrNull { it.id == selectedInstallationId }
    val activeManagementBase = selectedInstallation?.managementBaseUrl
        ?: InstallationStore.normaliseBaseUrl(registrationBaseUrl)
    val activeServiceIp = selectedInstallation?.serviceIp
        ?: registrationServiceIp.trim().ifBlank { InstallationStore.endpointHost(activeManagementBase) }
    val activeName = selectedInstallation?.name
        ?: registrationName.trim().ifBlank {
            InstallationStore.endpointHost(activeManagementBase).ifBlank { "Unregistered installation" }
        }

    // Global threat-watch state is intentionally independent of selectedInstallationId.
    var threatStates by remember { mutableStateOf<Map<String, InstallationThreatState>>(emptyMap()) }

    // Legacy phone/unit demo status still follows the transport gateway. This is
    // separate from the managed installation selected above.
    var infectionStatus by remember { mutableStateOf("Infection status not checked") }
    var severity by remember { mutableStateOf(0) }
    var infected by remember { mutableStateOf(false) }
    var assessmentSource by remember { mutableStateOf("") }
    var unitId by remember { mutableStateOf<Int?>(null) }
    var referenceId by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(false) }
    var intervalText by remember { mutableStateOf("1000") }
    var polls by remember { mutableStateOf(0L) }
    var changes by remember { mutableStateOf(0L) }
    var lastLatencyMs by remember { mutableStateOf<Long?>(null) }
    var lastMeaningfulJson by remember { mutableStateOf<String?>(null) }
    val pollInFlight = remember { AtomicBoolean(false) }

    // Manager state applies to the currently selected/candidate installation only.
    var managerEmail by remember { mutableStateOf("") }
    var managerRequestId by remember { mutableStateOf<Int?>(null) }
    var managerRequestToken by remember { mutableStateOf("") }
    var managerCredential by remember { mutableStateOf("") }
    var managerEmailVerified by remember { mutableStateOf(false) }
    var managerGatewayApproved by remember { mutableStateOf(false) }
    var managerCredentialReady by remember { mutableStateOf(false) }
    var managerRejected by remember { mutableStateOf(false) }
    var managerAuthenticated by remember { mutableStateOf(false) }
    var managerStatus by remember { mutableStateOf("No manager access request") }

    var assistancePort by remember { mutableStateOf("0") }
    var assistanceThreshold by remember { mutableStateOf("5") }
    var assistanceStatus by remember { mutableStateOf("No assistance request submitted") }
    var assistanceItems by remember { mutableStateOf<List<AssistanceRequestItem>>(emptyList()) }

    fun selectedScheme() = if (dbServer.trim().startsWith("https://", true)) "https" else "http"
    fun transportGatewayBaseUrl(): String? = discoveredGateway.trim().takeIf { it.isNotBlank() }
        ?.let { "${selectedScheme()}://$it" }

    fun meaningful(json: JSONObject): String {
        val copy = JSONObject(json.toString())
        copy.remove("server_time")
        return copy.toString()
    }

    fun resetManagerUi(message: String = "No manager session loaded for this installation") {
        managerRequestId = null
        managerRequestToken = ""
        managerEmailVerified = false
        managerGatewayApproved = false
        managerCredentialReady = false
        managerRejected = false
        managerAuthenticated = false
        assistanceItems = emptyList()
        managerStatus = message
    }

    fun applyGatewayJson(json: JSONObject, action: String, latency: Long, polling: Boolean) {
        val signature = meaningful(json)
        val changed = lastMeaningfulJson != signature
        if (polling) polls++
        lastLatencyMs = latency
        if (changed) {
            if (lastMeaningfulJson != null) changes++
            lastMeaningfulJson = signature
            infected = json.optBoolean("infected", false)
            severity = json.optInt("severity", 0)
            assessmentSource = json.optString("source", "")
            unitId = if (json.isNull("unitId")) null else json.optInt("unitId")
            referenceId = if (json.isNull("referenceId")) null else json.optInt("referenceId")
            val cleared = json.optInt("cleared", 0)
            val sourceText = if (assessmentSource.isNotBlank()) " from $assessmentSource" else ""
            infectionStatus = when {
                action == "clear" && cleared > 0 -> "Gateway deactivated $cleared local infection record(s). Current assessment: severity $severity$sourceText."
                action == "clear" -> "No active local infection record needed clearing. Current assessment: severity $severity$sourceText."
                infected -> "Gateway assessment: infected, severity $severity$sourceText."
                else -> "Gateway assessment: clear, severity $severity$sourceText."
            }
        }
    }

    fun gatewayRequest(action: String, polling: Boolean = false) {
        val base = transportGatewayBaseUrl()
        if (base == null) {
            infectionStatus = "Connect to the DB server first so TaraSec can learn the transport gateway IP."
            return
        }
        if (polling && !pollInFlight.compareAndSet(false, true)) return
        if (!polling) busy = true
        Thread {
            var connection: HttpURLConnection? = null
            val started = SystemClock.elapsedRealtime()
            try {
                val suffix = if (polling && action == "status") "?action=status&poll=1" else ""
                connection = URL("$base/script/appInfection.php$suffix").openConnection() as HttpURLConnection
                connection.requestMethod = if (action == "clear") "POST" else "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.useCaches = false
                if (action == "clear") {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write("action=clear".toByteArray()) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("Gateway returned HTTP $code: $body")
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Gateway rejected request"))
                val latency = SystemClock.elapsedRealtime() - started
                activity.runOnUiThread {
                    applyGatewayJson(json, action, latency, polling)
                    busy = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    infectionStatus = "Gateway request failed: ${e.message ?: e.javaClass.simpleName}"
                    busy = false
                }
            } finally {
                connection?.disconnect()
                if (polling) pollInFlight.set(false)
            }
        }.start()
    }

    fun assistanceRequest(action: String) {
        val installation = selectedInstallation
        if (installation == null) {
            assistanceStatus = "Select a registered installation first."
            return
        }
        if (!managerAuthenticated) {
            assistanceStatus = "Manager access must be active on the selected installation first."
            return
        }
        busy = true
        assistanceStatus = if (action == "create") "Submitting assistance request..." else "Loading assistance requests..."
        Thread {
            try {
                if (action == "create") {
                    val port = assistancePort.toIntOrNull()
                    val threshold = assistanceThreshold.toIntOrNull()
                    if (port == null || port !in 0..65535) throw IllegalArgumentException("Port must be between 0 and 65535.")
                    if (threshold == null || threshold !in 0..10) throw IllegalArgumentException("Threat threshold must be between 0 and 10.")
                    val created = AssistanceClient.create(
                        installation.managementBaseUrl,
                        installation.serviceIp,
                        port,
                        threshold
                    )
                    val list = AssistanceClient.list(installation.managementBaseUrl)
                    activity.runOnUiThread {
                        assistanceItems = list
                        assistanceStatus = "Assistance request #${created.id} created by ${installation.name}."
                        busy = false
                    }
                } else {
                    val list = AssistanceClient.list(installation.managementBaseUrl)
                    activity.runOnUiThread {
                        assistanceItems = list
                        assistanceStatus = "Loaded ${list.size} assistance request(s) from ${installation.name}."
                        busy = false
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    assistanceStatus = e.message ?: "Assistance request failed"
                    busy = false
                }
            }
        }.start()
    }

    fun managerRequest(action: String) {
        val base = activeManagementBase
        if (base.isBlank()) {
            managerStatus = "Choose or enter an installation management URL first."
            return
        }
        if (action == "request" && managerEmail.isBlank()) {
            managerStatus = "Enter your email address."
            return
        }
        if ((action == "status" || action == "resend") && (managerRequestId == null || managerRequestToken.isBlank())) {
            managerStatus = "Create a manager request first."
            return
        }
        if (action == "login" && managerCredential.isBlank()) {
            managerStatus = "Manager credential has not been generated yet."
            return
        }

        busy = true
        managerStatus = when (action) {
            "request" -> "Creating manager access request on $activeName..."
            "status" -> "Checking manager approval status..."
            "resend" -> "Queuing a new verification email..."
            "login" -> "Activating manager access on $activeName..."
            "logout" -> "Signing out from $activeName..."
            else -> "Checking manager session..."
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val query = if (action == "status") {
                    "?action=status&requestId=$managerRequestId&requestToken=${URLEncoder.encode(managerRequestToken, Charsets.UTF_8.name())}"
                } else if (action == "session") "?action=session" else ""
                val endpoint = if (action == "resend") "managerResend.php" else "managerAuth.php"
                connection = URL("$base/script/$endpoint$query").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 7000
                connection.useCaches = false

                if (action == "request" || action == "resend" || action == "login" || action == "logout") {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val data = when (action) {
                        "request" -> "action=request&email=${URLEncoder.encode(managerEmail.trim(), Charsets.UTF_8.name())}"
                        "resend" -> "requestId=$managerRequestId&requestToken=${URLEncoder.encode(managerRequestToken, Charsets.UTF_8.name())}"
                        "login" -> "action=login&key=${URLEncoder.encode(managerCredential, Charsets.UTF_8.name())}"
                        else -> "action=logout"
                    }
                    connection.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = try {
                    if (body.isBlank()) JSONObject() else JSONObject(body)
                } catch (_: Exception) {
                    throw IllegalStateException("Gateway returned non-JSON response (HTTP $code): ${body.take(120)}")
                }
                if (code !in 200..299 || !json.optBoolean("ok", false)) {
                    val error = json.optString("error", "HTTP $code")
                    throw IllegalStateException(when (error) {
                        "invalid_email" -> "Enter a valid email address."
                        "manager_not_approved" -> "Manager access is not fully approved yet."
                        "request_not_found" -> "This manager request is no longer available."
                        "email_already_verified" -> "This email address is already verified. Refresh approval status."
                        "request_rejected" -> "This manager request was rejected by the installation."
                        "resend_not_available" -> "Verification email cannot be resent for this request."
                        "manager_auth_unavailable" -> "Manager authentication is not available on this installation."
                        else -> "Installation manager request failed: $error"
                    })
                }

                activity.runOnUiThread {
                    when (action) {
                        "request" -> {
                            managerRequestId = json.optInt("requestId")
                            managerRequestToken = json.optString("requestToken", "")
                            managerEmail = json.optString("email", managerEmail)
                            managerEmailVerified = false
                            managerGatewayApproved = false
                            managerCredentialReady = false
                            managerRejected = false
                            managerStatus = "Request created. Waiting for email and installation-admin confirmation."
                        }
                        "status" -> {
                            managerEmailVerified = json.optBoolean("emailVerified", false)
                            managerGatewayApproved = json.optBoolean("gatewayApproved", false)
                            managerCredentialReady = json.optBoolean("credentialReady", false)
                            managerRejected = json.optBoolean("rejected", false)
                            val returnedCredential = json.optString("credential", "")
                            if (returnedCredential.isNotBlank()) managerCredential = returnedCredential
                            managerStatus = when {
                                managerRejected -> "Manager request was rejected by the installation."
                                json.optBoolean("active", false) -> "Both confirmations received. Manager access is ready to activate."
                                else -> "Waiting for required confirmations."
                            }
                        }
                        "resend" -> {
                            managerStatus = "A new verification email has been queued. The previous verification link is no longer valid."
                        }
                        "login" -> {
                            managerAuthenticated = json.optBoolean("authenticated", false)
                            if (managerAuthenticated) {
                                val item = InstallationStore.register(
                                    activity,
                                    name = registrationName.ifBlank { selectedInstallation?.name ?: activeName },
                                    managementBaseUrl = base,
                                    serviceIp = registrationServiceIp.ifBlank { selectedInstallation?.serviceIp ?: activeServiceIp }
                                )
                                SecureCredentialStore.put(activity, item.id, managerCredential)
                                installations = InstallationStore.load(activity)
                                selectedInstallationId = item.id
                                InstallationStore.setSelected(activity, item.id)
                                managerStatus = "MANAGER ACCESS ACTIVE. ${item.name} is registered in this App."
                            } else {
                                managerStatus = "Manager access not active."
                            }
                        }
                        "logout" -> {
                            managerAuthenticated = false
                            assistanceItems = emptyList()
                            managerStatus = "Signed out from this installation. Registration is retained."
                        }
                        "session" -> {
                            managerAuthenticated = json.optBoolean("authenticated", false)
                            managerStatus = if (managerAuthenticated) "Existing manager session is active on $activeName." else "No active manager session on $activeName."
                        }
                    }
                    busy = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    managerStatus = e.message ?: "Manager request failed"
                    busy = false
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    // Selected installation changes only the active management context. It does
    // NOT change the global threat-watch list below.
    LaunchedEffect(selectedInstallationId, registrationBaseUrl) {
        resetManagerUi()
        val installation = installations.firstOrNull { it.id == selectedInstallationId }
        if (installation != null) {
            registrationName = installation.name
            registrationBaseUrl = installation.managementBaseUrl
            registrationServiceIp = installation.serviceIp
            val savedCredential = SecureCredentialStore.get(activity, installation.id)
            managerCredential = savedCredential.orEmpty()
            if (!savedCredential.isNullOrBlank()) {
                Thread {
                    val ok = InstallationClient.ensureManagerSession(activity, installation)
                    activity.runOnUiThread {
                        managerAuthenticated = ok
                        managerStatus = if (ok) {
                            "MANAGER ACCESS ACTIVE on ${installation.name}."
                        } else {
                            "Stored manager credential could not activate ${installation.name}."
                        }
                    }
                }.start()
            }
        } else {
            managerCredential = ""
        }
    }

    // Legacy live polling of the phone/unit behind the current transport gateway.
    DisposableEffect(autoRefresh, intervalText, discoveredGateway, page) {
        val running = AtomicBoolean(true)
        val worker = if (autoRefresh && discoveredGateway.isNotBlank() && page == AppPage.UNIT_DEMO) Thread {
            while (running.get()) {
                gatewayRequest("status", polling = true)
                val interval = intervalText.toLongOrNull()?.coerceAtLeast(50L) ?: 1000L
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.start() } else null
        onDispose {
            running.set(false)
            worker?.interrupt()
        }
    }

    // Global installation watch. This runs regardless of which page/current
    // installation the user has selected. It is intentionally a separate loop.
    DisposableEffect(installations) {
        val running = AtomicBoolean(true)
        val snapshot = installations
        val worker = if (snapshot.isNotEmpty()) Thread {
            while (running.get()) {
                snapshot.forEach { installation ->
                    if (!running.get()) return@forEach
                    val state = InstallationClient.pollThreat(activity, installation)
                    activity.runOnUiThread {
                        threatStates = threatStates.toMutableMap().apply {
                            put(installation.id, state)
                        }
                    }
                }
                try {
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.start() } else null

        onDispose {
            running.set(false)
            worker?.interrupt()
        }
    }

    val activeThreats = installations.mapNotNull { installation ->
        val state = threatStates[installation.id] ?: return@mapNotNull null
        if (state.infected || state.severity > 1) installation to state else null
    }
    val watchProblems = installations.mapNotNull { installation ->
        val state = threatStates[installation.id] ?: return@mapNotNull null
        if (!state.reachable || !state.authenticated) installation to state else null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("TaraSec", style = MaterialTheme.typography.headlineLarge)

        if (activeThreats.isNotEmpty()) {
            Text("THREAT WARNING — ${activeThreats.size} registered installation(s) need attention", style = MaterialTheme.typography.titleMedium)
            activeThreats.forEach { (installation, state) ->
                Text("${installation.name}: severity ${state.severity} — ${state.summary}")
            }
        } else if (installations.isNotEmpty()) {
            Text("Threat watch: no active warning from ${installations.size} registered installation(s).", style = MaterialTheme.typography.bodySmall)
        }
        if (watchProblems.isNotEmpty()) {
            Text("Threat watch unavailable for ${watchProblems.size} installation(s). Check manager credentials/connectivity.", style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { page = AppPage.SETUP }) { Text("Setup") }
            Button(onClick = { page = AppPage.UNIT_DEMO }) { Text("Unit / Demo") }
            Button(onClick = { page = AppPage.MANAGER }) { Text("Owner / Manager") }
        }

        if (installations.isNotEmpty()) {
            HorizontalDivider()
            Text("Current installation", style = MaterialTheme.typography.titleMedium)
            installations.forEach { installation ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedInstallationId = installation.id
                        InstallationStore.setSelected(activity, installation.id)
                    }
                ) {
                    Text((if (installation.id == selectedInstallationId) "✓ " else "") + "${installation.name} — ${installation.serviceIp}")
                }
            }
            Text("Changing the current installation changes management/AI/assistance only. Threat watch continues polling every registered installation.", style = MaterialTheme.typography.bodySmall)
        }

        when (page) {
            AppPage.SETUP -> {
                Text("Connection setup", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    dbServer,
                    { dbServer = it },
                    label = { Text("Global DB server URL (http:// or https://)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("For now the App is expected to reach TaraSec through the management VPN. The discovered transport gateway is not automatically the installation being managed.", style = MaterialTheme.typography.bodySmall)
                if (dbServer.trim().startsWith("http://", true)) {
                    Text("HTTP should only be used when the connection is protected by the management VPN.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    discoveredGateway,
                    {},
                    label = { Text("Transport gateway seen by DB server") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true
                )
                Button(enabled = !busy, onClick = {
                    val base = dbServer.trim().trimEnd('/')
                    if (!(base.startsWith("http://", true) || base.startsWith("https://", true))) {
                        setupStatus = "Include http:// or https://."
                        return@Button
                    }
                    busy = true
                    setupStatus = "Connecting..."
                    Thread {
                        var c: HttpURLConnection? = null
                        try {
                            c = URL("$base/script/appSetup.php").openConnection() as HttpURLConnection
                            c.connectTimeout = 5000
                            c.readTimeout = 5000
                            c.useCaches = false
                            val code = c.responseCode
                            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                                ?.bufferedReader()?.use { it.readText() }.orEmpty()
                            if (code !in 200..299) throw IllegalStateException("DB server returned HTTP $code: $body")
                            val json = JSONObject(body)
                            if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "DB server rejected request"))
                            val seen = json.optString("gateway_ip", "")
                            activity.runOnUiThread {
                                discoveredGateway = seen
                                setupStatus = "Connected through management path. DB server sees: $seen"
                                if (selectedInstallation == null && registrationBaseUrl.isBlank() && seen.isNotBlank()) {
                                    registrationBaseUrl = "${selectedScheme()}://$seen"
                                    registrationServiceIp = seen
                                    registrationName = "Gateway $seen"
                                }
                                busy = false
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread {
                                setupStatus = "Connection failed: ${e.message}"
                                busy = false
                            }
                        } finally {
                            c?.disconnect()
                        }
                    }.start()
                }) { Text(if (busy) "Testing..." else "Save / test setup") }
                Text(setupStatus)

                HorizontalDivider()
                Text("Register / prepare another TaraSec installation", style = MaterialTheme.typography.titleMedium)
                Text("An installation is added to the registered list only after manager authentication succeeds. Management address and service/request address are kept separate so public servers can still use a private management VPN.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    selectedInstallationId = null
                    InstallationStore.setSelected(activity, null)
                    registrationName = ""
                    registrationBaseUrl = ""
                    registrationServiceIp = ""
                    resetManagerUi("Enter the installation details, then request/activate manager access.")
                    page = AppPage.MANAGER
                }) { Text("Prepare new installation") }

                if (selectedInstallation == null) {
                    OutlinedTextField(registrationName, { registrationName = it }, label = { Text("Installation name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(registrationBaseUrl, { registrationBaseUrl = it }, label = { Text("Management URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(registrationServiceIp, { registrationServiceIp = it }, label = { Text("Service / Assistance-request IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }

            AppPage.UNIT_DEMO -> {
                Text("Unit / Security Demo", style = MaterialTheme.typography.titleMedium)
                Text("This legacy demo checks the phone/unit behind the currently discovered transport gateway. Managed-server monitoring is shown by the global threat watch above.", style = MaterialTheme.typography.bodySmall)
                Text(infectionStatus)
                Text("Severity: $severity" + if (assessmentSource.isNotBlank()) " ($assessmentSource)" else "")
                unitId?.let { Text("unitId: $it") }
                referenceId?.let { Text("referenceId: $it") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoRefresh, { autoRefresh = it })
                    Text("Auto refresh this connection")
                }
                OutlinedTextField(
                    intervalText,
                    { intervalText = it.filter(Char::isDigit) },
                    label = { Text("Polling interval (ms, minimum 50)") },
                    singleLine = true
                )
                Text("Polls: $polls   Changes: $changes   Last response: ${lastLatencyMs?.let { "$it ms" } ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && discoveredGateway.isNotBlank(), onClick = { gatewayRequest("status") }) { Text("Check this connection") }
                Button(enabled = !busy && discoveredGateway.isNotBlank() && infected, onClick = { gatewayRequest("clear") }) { Text("Declare this connection/unit clear") }
            }

            AppPage.MANAGER -> {
                Text("Node Owner / Manager", style = MaterialTheme.typography.titleMedium)
                Text("Current management target: $activeName" + if (activeManagementBase.isNotBlank()) " ($activeManagementBase)" else "")

                if (selectedInstallation == null) {
                    OutlinedTextField(registrationName, { registrationName = it }, label = { Text("Installation name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(registrationBaseUrl, { registrationBaseUrl = it }, label = { Text("Management URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(registrationServiceIp, { registrationServiceIp = it }, label = { Text("Service / Assistance-request IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                } else {
                    Text("Service / Assistance-request IP: ${selectedInstallation.serviceIp}")
                }

                Text("Manager access needs two independent confirmations: control of the email address and approval by an administrator of this installation.", style = MaterialTheme.typography.bodySmall)

                if (managerRequestId == null && !managerAuthenticated) {
                    OutlinedTextField(managerEmail, { managerEmail = it }, label = { Text("Email address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(enabled = !busy && activeManagementBase.isNotBlank(), onClick = { managerRequest("request") }) { Text("Request manager access") }
                    if (selectedInstallation != null && managerCredential.isNotBlank()) {
                        Button(enabled = !busy, onClick = { managerRequest("login") }) { Text("Reconnect with stored credential") }
                    }
                    Button(enabled = !busy && activeManagementBase.isNotBlank(), onClick = { managerRequest("session") }) { Text("Check existing session") }
                } else if (!managerAuthenticated) {
                    Text("Email: $managerEmail")
                    Text("Email verification: ${if (managerEmailVerified) "Confirmed" else "Waiting"}")
                    Text("Installation admin confirmation: ${if (managerGatewayApproved) "Confirmed" else "Waiting"}")
                    Text("Manager credential: ${if (managerCredentialReady) "Ready" else "Generating"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && !managerRejected, onClick = { managerRequest("status") }) { Text("Refresh approval status") }
                        if (!managerEmailVerified && !managerRejected) {
                            Button(enabled = !busy, onClick = { managerRequest("resend") }) { Text("Resend verification email") }
                        }
                    }
                    Button(
                        enabled = !busy && managerEmailVerified && managerGatewayApproved && managerCredential.isNotBlank(),
                        onClick = { managerRequest("login") }
                    ) { Text("Activate manager access") }
                } else {
                    Text("MANAGER ACCESS ACTIVE", style = MaterialTheme.typography.titleMedium)
                    Text("Selected installation: $activeName")

                    ManagerAiPanel(
                        gatewayBaseUrl = activeManagementBase.takeIf { it.isNotBlank() },
                        managerAuthenticated = managerAuthenticated
                    )

                    HorizontalDivider()
                    Text("Assistance Request", style = MaterialTheme.typography.titleMedium)
                    if (selectedInstallation != null) {
                        Text("Requesting installation: ${selectedInstallation.name}")
                        Text("Requesting IP: ${selectedInstallation.serviceIp}")
                        Text("The requester is selected from registered installations; it is no longer a free-form IP field.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        assistancePort,
                        { assistancePort = it.filter(Char::isDigit) },
                        label = { Text("Port (0 = all ports)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        assistanceThreshold,
                        { assistanceThreshold = it.filter(Char::isDigit) },
                        label = { Text("Threat threshold (0-10)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && selectedInstallation != null, onClick = { assistanceRequest("create") }) { Text("Request assistance") }
                        Button(enabled = !busy && selectedInstallation != null, onClick = { assistanceRequest("list") }) { Text("Refresh requests") }
                    }
                    Text(assistanceStatus)
                    assistanceItems.take(10).forEach { item ->
                        Text(
                            "#${item.id} ${item.ip}:${item.port} threshold ${item.threshold} — ${if (item.sentPartners) "queued/sent outward" else if (item.handled) "handled" else "local pending"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(enabled = !busy, onClick = { managerRequest("logout") }) { Text("Sign out manager session") }
                }

                Text(managerStatus)
            }
        }
    }
}
