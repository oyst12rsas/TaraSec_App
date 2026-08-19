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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
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
    var dbServer by remember { mutableStateOf("http://100.68.126.0") }
    var gateway by remember { mutableStateOf("") }
    var setupStatus by remember { mutableStateOf("Not connected") }
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

    var assistanceIp by remember { mutableStateOf("") }
    var assistancePort by remember { mutableStateOf("0") }
    var assistanceThreshold by remember { mutableStateOf("5") }
    var assistanceStatus by remember { mutableStateOf("No assistance request submitted") }
    var assistanceItems by remember { mutableStateOf<List<AssistanceRequestItem>>(emptyList()) }

    fun selectedScheme() = if (dbServer.trim().startsWith("https://", true)) "https" else "http"
    fun gatewayBaseUrl(): String? = gateway.trim().takeIf { it.isNotBlank() }?.let { "${selectedScheme()}://$it" }

    fun meaningful(json: JSONObject): String {
        val copy = JSONObject(json.toString())
        copy.remove("server_time")
        return copy.toString()
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
        val base = gatewayBaseUrl()
        if (base == null) { infectionStatus = "Connect to the DB server first so TaraSec can learn the gateway IP."; return }
        if (polling && !pollInFlight.compareAndSet(false, true)) return
        if (!polling) busy = true
        Thread {
            var connection: HttpURLConnection? = null
            val started = SystemClock.elapsedRealtime()
            try {
                connection = URL("$base/script/appInfection.php").openConnection() as HttpURLConnection
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
                activity.runOnUiThread { applyGatewayJson(json, action, latency, polling); busy = false }
            } catch (e: Exception) {
                activity.runOnUiThread { infectionStatus = "Gateway request failed: ${e.message ?: e.javaClass.simpleName}"; busy = false }
            } finally {
                connection?.disconnect()
                if (polling) pollInFlight.set(false)
            }
        }.start()
    }

    fun assistanceRequest(action: String) {
        val base = gatewayBaseUrl()
        if (base == null) { assistanceStatus = "Gateway is not configured."; return }
        if (!managerAuthenticated) { assistanceStatus = "Manager access must be active first."; return }
        busy = true
        assistanceStatus = if (action == "create") "Submitting assistance request..." else "Loading assistance requests..."
        Thread {
            try {
                if (action == "create") {
                    val port = assistancePort.toIntOrNull()
                    val threshold = assistanceThreshold.toIntOrNull()
                    if (port == null || port !in 0..65535) throw IllegalArgumentException("Port must be between 0 and 65535.")
                    if (threshold == null || threshold !in 0..10) throw IllegalArgumentException("Threat threshold must be between 0 and 10.")
                    val created = AssistanceClient.create(base, assistanceIp.trim(), port, threshold)
                    val list = AssistanceClient.list(base)
                    activity.runOnUiThread {
                        assistanceItems = list
                        assistanceStatus = "Assistance request #${created.id} submitted to this gateway."
                        busy = false
                    }
                } else {
                    val list = AssistanceClient.list(base)
                    activity.runOnUiThread {
                        assistanceItems = list
                        assistanceStatus = "Loaded ${list.size} assistance request(s)."
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
        val base = gatewayBaseUrl()
        if (base == null) { managerStatus = "Connect to the DB server first so TaraSec can learn the gateway IP."; return }
        if (action == "request" && managerEmail.isBlank()) { managerStatus = "Enter your email address."; return }
        if ((action == "status" || action == "resend") && (managerRequestId == null || managerRequestToken.isBlank())) { managerStatus = "Create a manager request first."; return }
        if (action == "login" && managerCredential.isBlank()) { managerStatus = "Manager credential has not been generated yet."; return }

        busy = true
        managerStatus = when (action) {
            "request" -> "Creating manager access request..."
            "status" -> "Checking manager approval status..."
            "resend" -> "Queuing a new verification email..."
            "login" -> "Activating approved manager session..."
            "logout" -> "Signing out..."
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
                connection.readTimeout = 5000
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
                val json = if (body.isBlank()) JSONObject() else JSONObject(body)
                if (code !in 200..299 || !json.optBoolean("ok", false)) {
                    val error = json.optString("error", "HTTP $code")
                    throw IllegalStateException(when (error) {
                        "invalid_email" -> "Enter a valid email address."
                        "manager_not_approved" -> "Manager access is not fully approved yet."
                        "request_not_found" -> "This manager request is no longer available."
                        "email_already_verified" -> "This email address is already verified. Refresh approval status."
                        "request_rejected" -> "This manager request was rejected by the gateway."
                        "resend_not_available" -> "Verification email cannot be resent for this request."
                        "manager_auth_unavailable" -> "Manager authentication is not available on this gateway."
                        else -> "Gateway manager request failed: $error"
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
                            managerStatus = "Request created. Waiting for email and gateway admin confirmation."
                        }
                        "status" -> {
                            managerEmailVerified = json.optBoolean("emailVerified", false)
                            managerGatewayApproved = json.optBoolean("gatewayApproved", false)
                            managerCredentialReady = json.optBoolean("credentialReady", false)
                            managerRejected = json.optBoolean("rejected", false)
                            val returnedCredential = json.optString("credential", "")
                            if (returnedCredential.isNotBlank()) managerCredential = returnedCredential
                            managerStatus = when {
                                managerRejected -> "Manager request was rejected by the gateway."
                                json.optBoolean("active", false) -> "Both confirmations received. Manager access is ready to activate."
                                else -> "Waiting for required confirmations."
                            }
                        }
                        "resend" -> {
                            managerStatus = "A new verification email has been queued. The previous verification link is no longer valid."
                        }
                        "login" -> {
                            managerAuthenticated = json.optBoolean("authenticated", false)
                            managerStatus = if (managerAuthenticated) "MANAGER ACCESS ACTIVE on this gateway." else "Manager access not active."
                        }
                        "logout" -> {
                            managerAuthenticated = false
                            assistanceItems = emptyList()
                            managerStatus = "Signed out."
                        }
                        "session" -> {
                            managerAuthenticated = json.optBoolean("authenticated", false)
                            managerStatus = if (managerAuthenticated) "Existing manager session is active." else "No active manager session."
                        }
                    }
                    busy = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread { managerStatus = e.message ?: "Manager request failed"; busy = false }
            } finally { connection?.disconnect() }
        }.start()
    }

    DisposableEffect(autoRefresh, intervalText, gateway, page) {
        val running = AtomicBoolean(true)
        val worker = if (autoRefresh && gateway.isNotBlank() && page == AppPage.UNIT_DEMO) Thread {
            while (running.get()) {
                gatewayRequest("status", polling = true)
                val interval = intervalText.toLongOrNull()?.coerceAtLeast(50L) ?: 1000L
                try { Thread.sleep(interval) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() } else null
        onDispose { running.set(false); worker?.interrupt() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { page = AppPage.SETUP }) { Text("Setup") }
            Button(onClick = { page = AppPage.UNIT_DEMO }) { Text("Unit / Demo") }
            Button(onClick = { page = AppPage.MANAGER }) { Text("Owner / Manager") }
        }

        when (page) {
            AppPage.SETUP -> {
                Text("Connection setup", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(dbServer, { dbServer = it }, label = { Text("DB server URL (http:// or https://)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Demo network: Traffic through the VPN is already encrypted. Demo servers may therefore use HTTP and may not listen on HTTPS port 443. Use http:// for these servers.", style = MaterialTheme.typography.bodySmall)
                if (dbServer.trim().startsWith("http://", true)) Text("HTTP should only be used when the connection is protected by the VPN.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(gateway, {}, label = { Text("Gateway IP seen by DB server") }, modifier = Modifier.fillMaxWidth(), readOnly = true)
                Button(enabled = !busy, onClick = {
                    val base = dbServer.trim().trimEnd('/')
                    if (!(base.startsWith("http://", true) || base.startsWith("https://", true))) { setupStatus = "Include http:// or https://."; return@Button }
                    busy = true; setupStatus = "Connecting..."
                    Thread {
                        var c: HttpURLConnection? = null
                        try {
                            c = URL("$base/script/appSetup.php").openConnection() as HttpURLConnection
                            c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
                            val code = c.responseCode
                            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                            if (code !in 200..299) throw IllegalStateException("DB server returned HTTP $code: $body")
                            val json = JSONObject(body)
                            if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "DB server rejected request"))
                            activity.runOnUiThread { gateway = json.optString("gateway_ip", ""); setupStatus = "Connected. Gateway/client: $gateway"; busy = false }
                        } catch (e: Exception) { activity.runOnUiThread { setupStatus = "Connection failed: ${e.message}"; busy = false } } finally { c?.disconnect() }
                    }.start()
                }) { Text(if (busy) "Testing..." else "Save / test setup") }
                Text(setupStatus)
            }

            AppPage.UNIT_DEMO -> {
                Text("Unit / Security Demo", style = MaterialTheme.typography.titleMedium)
                Text(infectionStatus)
                Text("Severity: $severity" + if (assessmentSource.isNotBlank()) " ($assessmentSource)" else "")
                unitId?.let { Text("unitId: $it") }
                referenceId?.let { Text("referenceId: $it") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(autoRefresh, { autoRefresh = it }); Text("Auto refresh") }
                OutlinedTextField(intervalText, { intervalText = it.filter(Char::isDigit) }, label = { Text("Polling interval (ms, minimum 50)") }, singleLine = true)
                Text("Polls: $polls   Changes: $changes   Last response: ${lastLatencyMs?.let { "$it ms" } ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && gateway.isNotBlank(), onClick = { gatewayRequest("status") }) { Text("Check infection status") }
                Button(enabled = !busy && gateway.isNotBlank() && infected, onClick = { gatewayRequest("clear") }) { Text("Declare this unit clear") }
                Button(enabled = false, onClick = {}) { Text("Get infected (A5 server test pending)") }
                Text("Controlled demo infection only. This will be enabled when the gateway test endpoint is available.", style = MaterialTheme.typography.bodySmall)
            }

            AppPage.MANAGER -> {
                Text("Node Owner / Manager", style = MaterialTheme.typography.titleMedium)
                Text("Manager access needs two independent confirmations: control of the email address and approval by a logged-in administrator of this gateway.", style = MaterialTheme.typography.bodySmall)

                if (managerRequestId == null && !managerAuthenticated) {
                    OutlinedTextField(managerEmail, { managerEmail = it }, label = { Text("Email address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(enabled = !busy && gateway.isNotBlank(), onClick = { managerRequest("request") }) { Text("Request manager access") }
                    Button(enabled = !busy && gateway.isNotBlank(), onClick = { managerRequest("session") }) { Text("Check existing session") }
                } else if (!managerAuthenticated) {
                    Text("Email: $managerEmail")
                    Text("Email verification: ${if (managerEmailVerified) "Confirmed" else "Waiting"}")
                    Text("Gateway admin confirmation: ${if (managerGatewayApproved) "Confirmed" else "Waiting"}")
                    Text("Manager credential: ${if (managerCredentialReady) "Ready" else "Generating"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && !managerRejected, onClick = { managerRequest("status") }) { Text("Refresh approval status") }
                        if (!managerEmailVerified && !managerRejected) {
                            Button(enabled = !busy, onClick = { managerRequest("resend") }) { Text("Resend verification email") }
                        }
                    }
                    Button(enabled = !busy && managerEmailVerified && managerGatewayApproved && managerCredential.isNotBlank(), onClick = { managerRequest("login") }) { Text("Activate manager access") }
                } else {
                    Text("MANAGER ACCESS ACTIVE", style = MaterialTheme.typography.titleMedium)
                    Text("Authenticated manager: $managerEmail")
                    Text("Assistance requests created here are posted to this gateway and remain under the gateway's local management authority.", style = MaterialTheme.typography.bodySmall)

                    OutlinedTextField(assistanceIp, { assistanceIp = it }, label = { Text("IP requiring assistance") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(assistancePort, { assistancePort = it.filter(Char::isDigit) }, label = { Text("Port (0 = all ports)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(assistanceThreshold, { assistanceThreshold = it.filter(Char::isDigit) }, label = { Text("Threat threshold (0-10)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { assistanceRequest("create") }) { Text("Request assistance") }
                        Button(enabled = !busy, onClick = { assistanceRequest("list") }) { Text("Refresh requests") }
                    }
                    Text(assistanceStatus)
                    assistanceItems.take(10).forEach { item ->
                        Text("#${item.id} ${item.ip}:${item.port} threshold ${item.threshold} — ${if (item.sentPartners) "sent to partners" else if (item.handled) "handled" else "pending"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = !busy, onClick = { managerRequest("logout") }) { Text("Sign out manager") }
                }

                Text(managerStatus)
            }
        }
    }
}