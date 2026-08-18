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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                Surface(modifier = Modifier.fillMaxSize()) { TaraSecApp() }
            }
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

    var managerKey by remember { mutableStateOf("") }
    var managerStatus by remember { mutableStateOf("Not signed in") }
    var managerAuthenticated by remember { mutableStateOf(false) }
    var managerLabel by remember { mutableStateOf("") }

    fun selectedScheme() = if (dbServer.trim().startsWith("https://", true)) "https" else "http"

    fun gatewayBaseUrl(): String? {
        val host = gateway.trim()
        return if (host.isBlank()) null else "${selectedScheme()}://$host"
    }

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
        if (base == null) {
            infectionStatus = "Connect to the DB server first so TaraSec can learn the gateway IP."
            return
        }
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

    fun managerRequest(action: String) {
        val base = gatewayBaseUrl()
        if (base == null) {
            managerStatus = "Connect to the DB server first so TaraSec can learn the gateway IP."
            return
        }
        if (action == "login" && managerKey.isBlank()) {
            managerStatus = "Enter the manager/owner key."
            return
        }

        busy = true
        managerStatus = when (action) {
            "login" -> "Signing in to gateway..."
            "logout" -> "Signing out..."
            else -> "Checking manager session..."
        }

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$base/script/managerAuth.php").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.useCaches = false

                if (action == "login" || action == "logout") {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val data = if (action == "login") {
                        "action=login&key=${URLEncoder.encode(managerKey, Charsets.UTF_8.name())}"
                    } else {
                        "action=logout"
                    }
                    connection.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                } else {
                    connection.requestMethod = "GET"
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = if (body.isBlank()) JSONObject() else JSONObject(body)

                if (code !in 200..299 || !json.optBoolean("ok", false)) {
                    val error = json.optString("error", "HTTP $code")
                    throw IllegalStateException(
                        when (error) {
                            "invalid_manager_key" -> "Manager key was not accepted by this gateway."
                            "manager_auth_unavailable" -> "Manager authentication is not configured on this gateway."
                            else -> "Gateway rejected manager sign-in: $error"
                        }
                    )
                }

                val authenticated = json.optBoolean("authenticated", false)
                val manager = json.optJSONObject("manager")
                val label = manager?.optString("label", "").orEmpty()

                activity.runOnUiThread {
                    managerAuthenticated = authenticated
                    managerLabel = label
                    if (authenticated) {
                        managerKey = ""
                        managerStatus = if (label.isNotBlank()) "Signed in as $label." else "Manager session authenticated."
                    } else {
                        managerStatus = "Not signed in"
                        managerLabel = ""
                    }
                    busy = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (action != "status") managerAuthenticated = false
                    managerStatus = e.message ?: "Manager request failed"
                    busy = false
                }
            } finally {
                connection?.disconnect()
            }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoRefresh, { autoRefresh = it })
                    Text("Auto refresh")
                }
                OutlinedTextField(intervalText, { intervalText = it.filter(Char::isDigit) }, label = { Text("Polling interval (ms, minimum 50)") }, singleLine = true)
                Text("Polls: $polls   Changes: $changes   Last response: ${lastLatencyMs?.let { "$it ms" } ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && gateway.isNotBlank(), onClick = { gatewayRequest("status") }) { Text("Check infection status") }
                Button(enabled = !busy && gateway.isNotBlank() && infected, onClick = { gatewayRequest("clear") }) { Text("Declare this unit clear") }
                Button(enabled = false, onClick = {}) { Text("Get infected (A5 server test pending)") }
                Text("Controlled demo infection only. This will be enabled when the gateway test endpoint is available.", style = MaterialTheme.typography.bodySmall)
            }

            AppPage.MANAGER -> {
                Text("Node Owner / Manager", style = MaterialTheme.typography.titleMedium)
                Text("Management functions are intentionally separate from the unit-side infection demo.")
                Text("Authenticate with a key configured locally on this gateway. The key is sent only for sign-in; later manager calls use the authenticated session.", style = MaterialTheme.typography.bodySmall)

                if (!managerAuthenticated) {
                    OutlinedTextField(
                        value = managerKey,
                        onValueChange = { managerKey = it },
                        label = { Text("Manager / owner key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Button(enabled = !busy && gateway.isNotBlank(), onClick = { managerRequest("login") }) { Text("Manager sign-in") }
                    Button(enabled = !busy && gateway.isNotBlank(), onClick = { managerRequest("status") }) { Text("Check existing session") }
                } else {
                    Text(if (managerLabel.isNotBlank()) "Authenticated manager: $managerLabel" else "Manager authenticated")
                    Button(enabled = !busy, onClick = { managerRequest("logout") }) { Text("Sign out") }
                }

                Text(managerStatus)
                Text("A6: Assistance Requests will appear here after manager authentication.")
                Button(enabled = false, onClick = {}) { Text("Assistance Requests (A6 pending)") }
            }
        }
    }
}
