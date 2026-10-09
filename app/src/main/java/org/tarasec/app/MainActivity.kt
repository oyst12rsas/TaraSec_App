package org.tarasec.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TaraSecApp(intent.getStringExtra(CONSOLE_DESTINATION_EXTRA))
                }
            }
        }
    }
}

private enum class AppPage { UNITS, DEMO, MANAGER, ASSISTANCE, SSH, RESEARCH, SETUP }

@androidx.compose.runtime.Composable
private fun TaraSecApp(initialDestination: String?) {
    val activity = LocalContext.current as Activity
    var page by remember {
        mutableStateOf(
            when (initialDestination) {
                TaraMenuDestination.SECURITY_DEMO.name -> AppPage.DEMO
                TaraMenuDestination.AI_ASSISTANCE.name -> AppPage.MANAGER
                TaraMenuDestination.RESEARCH.name -> AppPage.RESEARCH
                TaraMenuDestination.SETUP_HOTSPOTS.name -> AppPage.SETUP
                else -> AppPage.UNITS
            }
        )
    }

    val linkedUnitKey = activity.intent.getStringExtra(UNIT_MANAGEMENT_KEY_EXTRA)
    val linkedUnitName = activity.intent.getStringExtra(UNIT_MANAGEMENT_NAME_EXTRA).orEmpty()
    val linkedUnit = remember(linkedUnitKey) {
        MyUnitsClient.accountId(activity)?.let { account ->
            MyUnitsClient.load(activity, account).firstOrNull { it.key == linkedUnitKey }
        }
    }
    val linkedGateway = linkedUnit?.takeIf {
        it.scope in listOf("gateway_read_only", "gateway_hosted_read_only")
    }
    val managementMappingKey = linkedUnitKey?.let {
        ServiceDiscovery.selected(activity).key("manager-installation:${MyUnitsClient.accountId(activity)}:$it")
    }
    val managedInstallationId = managementMappingKey?.let { SecureCredentialStore.get(activity, it) }
    val pendingKey = linkedUnitKey?.let { "pending-manager-registration:$it" } ?: "pending-manager-registration"
    val pending = remember {
        runCatching { SecureCredentialStore.get(activity, pendingKey)?.let { JSONObject(it) } }.getOrNull()
    }

    var installations by remember { mutableStateOf(InstallationStore.load(activity)) }
    var selectedInstallationId by remember {
        mutableStateOf(
            if (pending != null) null
            else if (linkedUnitKey != null) installations.firstOrNull { it.id == managedInstallationId }?.id
            else InstallationStore.load(activity).let { items ->
                items.firstOrNull { it.id == InstallationStore.selectedId(activity) }?.id
                    ?: items.firstOrNull()?.id
            }
        )
    }
    val selectedInstallation = installations.firstOrNull { it.id == selectedInstallationId }

    var registrationName by remember { mutableStateOf(pending?.optString("name", "") ?: linkedUnitName) }
    var registrationBaseUrl by remember { mutableStateOf(pending?.optString("base", "") ?: linkedGateway?.gateway.orEmpty()) }
    var registrationServiceIp by remember { mutableStateOf(pending?.optString("serviceIp", "") ?: linkedGateway?.let { InstallationStore.endpointHost(it.gateway) }.orEmpty()) }

    var managerEmail by remember { mutableStateOf(pending?.optString("email", "").orEmpty()) }
    var managerRequestId by remember { mutableStateOf<Int?>(pending?.optInt("requestId")?.takeIf { it > 0 }) }
    var managerRequestToken by remember { mutableStateOf(pending?.optString("token", "").orEmpty()) }
    var managerCredential by remember { mutableStateOf(pending?.optString("credential", "").orEmpty()) }
    var managerEmailVerified by remember { mutableStateOf(false) }
    var managerGatewayApproved by remember { mutableStateOf(false) }
    var managerCredentialReady by remember { mutableStateOf(false) }
    var managerRejected by remember { mutableStateOf(false) }
    var managerAuthenticated by remember { mutableStateOf(false) }
    var managerStatus by remember { mutableStateOf("No manager session loaded") }

    var assistancePort by remember { mutableStateOf("0") }
    var assistanceThreshold by remember { mutableStateOf("5") }
    var assistanceStatus by remember { mutableStateOf("No assistance request submitted") }
    var assistanceItems by remember { mutableStateOf<List<AssistanceRequestItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    var threatStates by remember { mutableStateOf<Map<String, InstallationThreatState>>(emptyMap()) }

    // Control-plane connectivity is deliberately background plumbing. Normal users
    // do not configure or select a DB server. Diagnostics only exposes failures.
    var controlPlaneStatus by remember { mutableStateOf("Checking in background...") }
    val controlPlaneBase = "http://100.68.126.0"

    fun resetManagerUi(message: String = "No manager session loaded") {
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

    fun persistManagerRequest(base: String) {
        SecureCredentialStore.put(activity, pendingKey, JSONObject().apply {
            put("requestId", managerRequestId)
            put("token", managerRequestToken)
            put("email", managerEmail)
            put("name", registrationName)
            put("base", base)
            put("serviceIp", registrationServiceIp)
            put("credential", managerCredential)
        }.toString())
    }

    fun managerRequest(action: String) {
        if (busy) return
        // Reopening or tapping again must resume the saved request, not create another.
        if (action == "request" && managerRequestId != null) {
            managerRequest("status")
            return
        }
        val base = selectedInstallation?.managementBaseUrl
            ?: InstallationStore.normaliseBaseUrl(registrationBaseUrl)
        if (base.isBlank()) {
            managerStatus = "Enter an installation management URL first."
            return
        }
        if (action == "request" && managerEmail.isBlank()) {
            managerStatus = "Enter your email address."
            return
        }
        if ((action == "status" || action == "resend") &&
            (managerRequestId == null || managerRequestToken.isBlank())) {
            managerStatus = "Create a manager request first."
            return
        }
        if (action == "login" && managerCredential.isBlank()) {
            managerStatus = "Manager credential has not been generated yet."
            return
        }

        busy = true
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val query = when (action) {
                    "status" -> "?action=status&requestId=$managerRequestId&requestToken=${URLEncoder.encode(managerRequestToken, Charsets.UTF_8.name())}"
                    "session" -> "?action=session"
                    else -> ""
                }
                val endpoint = if (action == "resend") "managerResend.php" else "managerAuth.php"
                connection = URL("$base/script/$endpoint$query").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.useCaches = false

                if (action in listOf("request", "resend", "login", "logout")) {
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
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = try {
                    if (body.isBlank()) JSONObject() else JSONObject(body)
                } catch (_: Exception) {
                    throw IllegalStateException("Installation returned non-JSON response (HTTP $code): ${body.take(120)}")
                }
                if (code !in 200..299 || !json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "HTTP $code"))
                }

                activity.runOnUiThread {
                    when (action) {
                        "request" -> {
                            managerRequestId = json.optInt("requestId")
                            managerRequestToken = json.optString("requestToken", "")
                            managerEmail = json.optString("email", managerEmail)
                            persistManagerRequest(base)
                            managerStatus = "Request created. Confirm the email and wait for installation-admin approval."
                        }
                        "status" -> {
                            managerEmailVerified = json.optBoolean("emailVerified", false)
                            managerGatewayApproved = json.optBoolean("gatewayApproved", false)
                            managerCredentialReady = json.optBoolean("credentialReady", false)
                            managerRejected = json.optBoolean("rejected", false)
                            val returnedCredential = json.optString("credential", "")
                            if (returnedCredential.isNotBlank()) managerCredential = returnedCredential
                            persistManagerRequest(base)
                            managerStatus = when {
                                managerRejected -> "Manager request was rejected."
                                json.optBoolean("active", false) -> "Manager access is ready to activate."
                                !managerEmailVerified -> "Confirm your email, then approve this request on the node’s web page."
                                !managerGatewayApproved -> "Waiting for administrator approval on the node’s web page."
                                else -> "Approvals confirmed. Preparing management access."
                            }
                        }
                        "resend" -> managerStatus = "A new verification email has been queued."
                        "login" -> {
                            managerAuthenticated = json.optBoolean("authenticated", false)
                            if (managerAuthenticated) {
                                val item = InstallationStore.register(
                                    activity,
                                    name = registrationName.ifBlank { selectedInstallation?.name ?: InstallationStore.endpointHost(base) },
                                    managementBaseUrl = base,
                                    serviceIp = registrationServiceIp.ifBlank { selectedInstallation?.serviceIp ?: InstallationStore.endpointHost(base) }
                                )
                                SecureCredentialStore.put(activity, item.id, managerCredential)
                                managementMappingKey?.let { SecureCredentialStore.put(activity, it, item.id) }
                                SecureCredentialStore.remove(activity, pendingKey)
                                managerRequestId = null
                                managerRequestToken = ""
                                installations = InstallationStore.load(activity)
                                selectedInstallationId = item.id
                                InstallationStore.setSelected(activity, item.id)
                                managerStatus = "MANAGER ACCESS ACTIVE on ${item.name}."
                                if (page == AppPage.SETUP) page = AppPage.UNITS
                            }
                        }
                        "logout" -> {
                            managerAuthenticated = false
                            managerStatus = "Signed out. Installation registration is retained."
                        }
                        "session" -> {
                            managerAuthenticated = json.optBoolean("authenticated", false)
                            managerStatus = if (managerAuthenticated) "Manager session is active." else "No active manager session."
                        }
                    }
                    busy = false
                    // Registration and credential storage still happen only after
                    // the gateway accepts login with both approvals complete.
                    if (action == "status" && json.optBoolean("active", false) &&
                        managerEmailVerified && managerGatewayApproved &&
                        managerCredential.isNotBlank()) {
                        managerRequest("login")
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    managerStatus = "Manager request failed: ${e.message ?: e.javaClass.simpleName}"
                    busy = false
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    var managementStartVersion by remember { mutableStateOf(0) }
    LaunchedEffect(linkedUnitKey, managementStartVersion) {
        if (linkedGateway != null && selectedInstallationId == null && managerRequestId == null &&
            activity.intent.getBooleanExtra(UNIT_MANAGEMENT_REQUEST_EXTRA, false)) {
            busy = true
            managerStatus = "Preparing management request for ${linkedGateway.name}…"
            try {
                val email = withContext(Dispatchers.IO) {
                    if (linkedGateway.scope == "gateway_hosted_read_only") {
                        val metadata = MyUnitsClient.gatewayMetadata(activity, linkedGateway.gateway)
                        require(metadata.getString("gateway_id") == linkedGateway.gatewayId &&
                            metadata.getString("service_node_id") == linkedGateway.serviceNodeId) {
                            "This address now reaches a different node. Reopen My units and check its address."
                        }
                    }
                    SubscriberAccountClient.account(activity).email
                        ?.takeIf { it.isNotBlank() } ?: error("Your signed-in account has no email address.")
                }
                registrationBaseUrl = linkedGateway.gateway
                registrationServiceIp = InstallationStore.endpointHost(linkedGateway.gateway)
                managerEmail = email
                busy = false
                managerRequest("request")
            } catch (e: Exception) {
                busy = false
                managerStatus = "Could not request management access: ${e.message}"
            }
        }
    }

    // Refresh pending approval on return from the browser/email app, and keep
    // checking only while this activity is visible. Latest callbacks avoid
    // retaining an old installation or request in the polling coroutine.
    var foreground by remember {
        mutableStateOf((activity as ComponentActivity).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(activity) {
        val lifecycle = (activity as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE) foreground = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val refreshApproval by rememberUpdatedState(newValue = {
        if (!busy && !managerAuthenticated && !managerRejected &&
            managerRequestId != null && managerRequestToken.isNotBlank()) {
            managerRequest("status")
        }
    })
    LaunchedEffect(foreground, managerRequestId, managerRequestToken,
        managerAuthenticated, managerRejected, managerEmailVerified,
        managerGatewayApproved, managerCredentialReady) {
        if (foreground && managerRequestId != null &&
            managerRequestToken.isNotBlank() && !managerAuthenticated &&
            !managerRejected) {
            while (true) {
                refreshApproval()
                delay(5_000L)
            }
        }
    }

    fun assistanceRequest(action: String) {
        val installation = selectedInstallation ?: run {
            assistanceStatus = "Select a registered installation first."
            return
        }
        if (!managerAuthenticated) {
            assistanceStatus = "Manager access is required on ${installation.name}."
            return
        }
        busy = true
        Thread {
            try {
                if (action == "create") {
                    val port = assistancePort.toIntOrNull()
                    val threshold = assistanceThreshold.toIntOrNull()
                    require(port != null && port in 0..65535) { "Port must be between 0 and 65535." }
                    require(threshold != null && threshold in 0..10) { "Threat threshold must be between 0 and 10." }
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
                        assistanceStatus = "Loaded ${list.size} request(s)."
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

    LaunchedEffect(Unit) {
        Thread {
            var c: HttpURLConnection? = null
            try {
                c = URL("$controlPlaneBase/script/appSetup.php").openConnection() as HttpURLConnection
                c.connectTimeout = 3500
                c.readTimeout = 3500
                c.useCaches = false
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val ok = code in 200..299 && runCatching { JSONObject(body).optBoolean("ok", false) }.getOrDefault(false)
                activity.runOnUiThread {
                    controlPlaneStatus = if (ok) "Control plane reachable" else "Control plane unavailable (HTTP $code)"
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    controlPlaneStatus = "Control plane unavailable: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                c?.disconnect()
            }
        }.start()
    }

    LaunchedEffect(selectedInstallationId) {
        if (selectedInstallationId != null || managerRequestId == null) resetManagerUi()
        val installation = installations.firstOrNull { it.id == selectedInstallationId }
        if (installation != null) {
            registrationName = installation.name
            registrationBaseUrl = installation.managementBaseUrl
            registrationServiceIp = installation.serviceIp
            val saved = SecureCredentialStore.get(activity, installation.id)
            managerCredential = saved.orEmpty()
            if (!saved.isNullOrBlank()) {
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
        }
    }

    DisposableEffect(installations) {
        val running = AtomicBoolean(true)
        val snapshot = installations
        val worker = if (snapshot.isNotEmpty()) Thread {
            while (running.get()) {
                snapshot.forEach { installation ->
                    if (!running.get()) return@forEach
                    val state = InstallationClient.pollThreat(activity, installation)
                    activity.runOnUiThread {
                        threatStates = threatStates.toMutableMap().apply { put(installation.id, state) }
                    }
                }
                try { Thread.sleep(30_000L) } catch (_: InterruptedException) { break }
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
            TaraHamburgerMenu { destination ->
                when (destination) {
                    TaraMenuDestination.MY_UNITS -> activity.startActivity(android.content.Intent(activity, MyUnitsActivity::class.java))
                    TaraMenuDestination.MY_ACCESS,
                    TaraMenuDestination.FIND_INTERNET -> {
                        activity.startActivity(
                            android.content.Intent(activity, SubscriberHomeActivity::class.java)
                                .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                        )
                    }
                    TaraMenuDestination.STATUS_UNITS -> page = AppPage.UNITS
                    TaraMenuDestination.SECURITY_DEMO -> page = AppPage.DEMO
                    TaraMenuDestination.AI_ASSISTANCE -> page = AppPage.MANAGER
                    TaraMenuDestination.RESEARCH -> page = AppPage.RESEARCH
                    TaraMenuDestination.SETUP_HOTSPOTS -> page = AppPage.SETUP
                    TaraMenuDestination.CONTRIBUTE -> Unit
                }
            }
        }

        if (activeThreats.isNotEmpty()) {
            Text("THREAT WARNING — ${activeThreats.size} registered installation(s) need attention", style = MaterialTheme.typography.titleMedium)
            activeThreats.forEach { (installation, state) ->
                Text("${installation.name}: severity ${state.severity} — ${state.summary}")
            }
        } else if (installations.isNotEmpty()) {
            Text("Threat watch: no active warning from ${installations.size} registered installation(s).", style = MaterialTheme.typography.bodySmall)
        }

        if (page in listOf(AppPage.UNITS, AppPage.MANAGER, AppPage.ASSISTANCE, AppPage.SSH, AppPage.SETUP)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selectedInstallation?.name ?: linkedUnitName.ifBlank { "Node management" },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = {
                    activity.startActivity(android.content.Intent(activity, MyUnitsActivity::class.java))
                }) { Text("My units") }
            }
            if (selectedInstallation != null || linkedUnitKey != null || managerRequestId != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AppPage.UNITS to "Status", AppPage.MANAGER to "AI", AppPage.ASSISTANCE to "Assistance", AppPage.SSH to "SSH").forEach { (target, label) ->
                        FilterChip(selected = page == target, onClick = { page = target }, label = { Text(label) })
                    }
                }
            }
        }

        HorizontalDivider()

        when (page) {
            AppPage.UNITS -> {
                val installation = selectedInstallation
                if (installation == null) {
                    if (managerRequestId != null) {
                        Text("${registrationName.ifBlank { "Installation" }} — approval pending")
                        Text(managerStatus)
                        Text("Email: ${if (managerEmailVerified) "Confirmed" else "Waiting"} · Admin: ${if (managerGatewayApproved) "Confirmed" else "Waiting"}")
                        Button(enabled = !busy, onClick = { managerRequest("status") }) { Text("Check approval") }
                        Button(onClick = { page = AppPage.SETUP }) { Text("View registration") }
                    } else {
                        Text("No registered installation selected.")
                    }
                    if (managerRequestId == null) {
                        Button(onClick = { page = AppPage.SETUP }) { Text(if (linkedUnitKey != null) "Management access" else "Register an installation") }
                    }
                } else {
                    if (managerAuthenticated) {
                        ServerStatusPanel(
                            gatewayBaseUrl = installation.managementBaseUrl,
                            managerAuthenticated = true
                        )
                    } else {
                        Text("Manager authentication is required to read this installation's status and units.")
                        Button(enabled = managerCredential.isNotBlank() && !busy, onClick = { managerRequest("login") }) {
                            Text("Unlock ${installation.name}")
                        }
                    }
                }
            }

            AppPage.DEMO -> {
                DemoPanel(
                    gatewayName = selectedInstallation?.name,
                    gatewayBaseUrl = selectedInstallation?.managementBaseUrl,
                    managerAuthenticated = managerAuthenticated,
                    onRemediationSignIn = {
                        activity.startActivity(
                            android.content.Intent(activity, SubscriberHomeActivity::class.java)
                                .putExtra(CONSOLE_DESTINATION_EXTRA, TaraMenuDestination.MY_ACCESS.name)
                        )
                    }
                )
            }

            AppPage.MANAGER -> {
                val installation = selectedInstallation
                if (installation == null) {
                    Text("Select your linked node in My units, or register an installation first.")
                } else if (!managerAuthenticated) {
                    Text("${installation.name} — Manager access", style = MaterialTheme.typography.titleLarge)
                    if (managerCredential.isNotBlank()) {
                        Button(enabled = !busy, onClick = { managerRequest("login") }) { Text("Reconnect with stored credential") }
                    } else {
                        Text("Manager access requires both email confirmation and approval by this installation's administrator.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("${installation.name} — AI", style = MaterialTheme.typography.titleLarge)
                    ManagerAiPanel(
                        gatewayBaseUrl = installation.managementBaseUrl,
                        managerAuthenticated = true
                    )
                }
            }

            AppPage.ASSISTANCE -> {
                val installation = selectedInstallation
                if (installation == null) {
                    Text("Choose a node from My units first.")
                } else if (!managerAuthenticated) {
                    Text("Management authentication is required to request assistance.")
                    Button(enabled = !busy && managerCredential.isNotBlank(), onClick = { managerRequest("login") }) { Text("Reconnect management") }
                } else {
                    Text("Assistance Request", style = MaterialTheme.typography.titleLarge)
                    Text("Ask the TaraSec network to help protect this installation against traffic matching the selected port and threat threshold. Creating a request does not prove partners have applied it.")
                    Text("Requesting installation: ${installation.name}")
                    Text("Requesting IP: ${installation.serviceIp}")
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
                        Button(enabled = !busy, onClick = { assistanceRequest("create") }) { Text("Request assistance") }
                        Button(enabled = !busy, onClick = { assistanceRequest("list") }) { Text("Refresh") }
                    }
                    Text(assistanceStatus)
                    AiDebugActions("Assistance request", buildString {
                        appendLine("Explain this TaraSec assistance request, its scope and delivery state. Distinguish created, sent, DB accepted and actually applied; do not claim protection without evidence.")
                        appendLine("node=${installation.name}")
                        appendLine("service_ip=${installation.serviceIp}")
                        appendLine("port=$assistancePort threshold=$assistanceThreshold")
                        appendLine("status=$assistanceStatus")
                        assistanceItems.forEach { item ->
                            appendLine("request_id=${item.id} ip=${item.ip} port=${item.port} category=${item.category} threshold=${item.threshold} handled=${item.handled} sent_partners=${item.sentPartners} delivery_state=${item.deliveryState}")
                        }
                    })
                    assistanceItems.take(10).forEach { item ->
                        Text("#${item.id} ${item.ip}:${item.port} threshold ${item.threshold} — ${item.deliveryState}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            AppPage.SSH -> {
                val installation = selectedInstallation
                if (installation == null) {
                    Text("Choose a node from My units first.")
                } else if (!managerAuthenticated) {
                    Text("Management authentication is required for SSH controls.")
                    Button(enabled = !busy && managerCredential.isNotBlank(), onClick = { managerRequest("login") }) { Text("Reconnect management") }
                } else {
                    ManagerSshPanel(installation.managementBaseUrl)
                }
            }

            AppPage.RESEARCH -> {
                ResearchPanel(paymentBaseUrl = selectedInstallation?.managementBaseUrl)
            }

            AppPage.SETUP -> {
                Text("Management access", style = MaterialTheme.typography.titleLarge)
                Text("The global DB/control plane is discovered and checked in the background. Users normally do not need to configure it.", style = MaterialTheme.typography.bodySmall)

                if (linkedUnitKey == null && managerRequestId == null) Button(enabled = !busy, onClick = {
                    selectedInstallationId = null
                    InstallationStore.setSelected(activity, null)
                    registrationName = ""
                    registrationBaseUrl = ""
                    registrationServiceIp = ""
                    managerCredential = ""
                    resetManagerUi("Enter installation details and request access.")
                }) { Text("Register another installation") }

                if (selectedInstallationId == null) {
                    if (managerRequestId == null) {
                        if (linkedGateway != null) {
                            Text("Management access for ${linkedGateway.name}")
                            Text(managerEmail.ifBlank { "Using your signed-in account" })
                            if (!busy) Button(onClick = { managementStartVersion++ }) { Text("Retry management request") }
                        } else if (linkedUnitKey != null && linkedUnit == null) {
                            Text("This linked node is unavailable for the current account. Return to My units and sign in again.")
                        } else {
                            OutlinedTextField(registrationName, { registrationName = it }, label = { Text("Installation name") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(registrationBaseUrl, { registrationBaseUrl = it }, label = { Text(if (linkedUnitKey != null) "This node’s management URL / IP" else "Management URL / IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(registrationServiceIp, { registrationServiceIp = it }, label = { Text("Service / Assistance IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(managerEmail, { managerEmail = it }, label = { Text("Email address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(enabled = !busy && registrationBaseUrl.isNotBlank(), onClick = { managerRequest("request") }) { Text("Request manager access") }
                        }
                    } else {
                        Text("${registrationName.ifBlank { linkedUnitName.ifBlank { "Installation" } }} — management approval")
                        Text("Request #$managerRequestId · $managerEmail")
                        Text("Email verification: ${if (managerEmailVerified) "Confirmed" else "Waiting"}")
                        Text("Installation admin approval: ${if (managerGatewayApproved) "Confirmed" else "Waiting"}")
                        Text("The app checks automatically and activates management access after both confirmations.")
                        Button(enabled = !busy && !managerRejected, onClick = { managerRequest("status") }) { Text("Check approval") }
                        if (!managerRejected) {
                            Button(onClick = {
                                val base = InstallationStore.normaliseBaseUrl(registrationBaseUrl)
                                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("$base/gatekeeper/index.php?f=main")))
                            }) { Text("Open node approval page") }
                        }
                        if (!managerEmailVerified && !managerRejected) {
                            Button(enabled = !busy, onClick = { managerRequest("resend") }) { Text("Resend verification email") }
                        }
                    }
                    Text(managerStatus)
                } else {
                    Text(managerStatus)
                    Button(enabled = !busy && managerCredential.isNotBlank(), onClick = { managerRequest("login") }) { Text("Reconnect management access") }
                }

                HorizontalDivider()
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                Text(controlPlaneStatus, style = MaterialTheme.typography.bodySmall)
                Text("Transport routing is intentionally hidden from normal App screens; it is a connectivity detail, not the selected installation.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
