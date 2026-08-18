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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    fun selectedScheme() = if (dbServer.trim().startsWith("https://", true)) "https" else "http"

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
        val gatewayHost = gateway.trim()
        if (gatewayHost.isBlank()) {
            infectionStatus = "Connect to the DB server first so TaraSec can learn the gateway IP."
            return
        }
        if (polling && !pollInFlight.compareAndSet(false, true)) return
        if (!polling) busy = true
        Thread {
            var connection: HttpURLConnection? = null
            val started = SystemClock.elapsedRealtime()
            try {
                connection = URL("${selectedScheme()}://$gatewayHost/script/appInfection.php").openConnection() as HttpURLConnection
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
                Text("A8: authenticate as the owner/manager of a gateway or node.")
                Text("A6: receive and handle Assistance Requests for units/nodes under that manager.")
                Button(enabled = false, onClick = {}) { Text("Manager sign-in (A8 pending)") }
                Button(enabled = false, onClick = {}) { Text("Assistance Requests (A6 pending)") }
            }
        }
    }
}
