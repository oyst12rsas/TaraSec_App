package org.tarasec.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock

private fun sshRequest(base: String, minutes: Int? = null, csrf: String = ""): JSONObject {
    val action = if (minutes == null) "status" else "open"
    val c = URL("${base.trimEnd('/')}/script/managerSsh.php?action=$action").openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 5000
        c.readTimeout = 15000
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("Accept", "application/json")
        if (minutes != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("X-TaraSec-Csrf", csrf)
            c.outputStream.use { it.write("minutes=$minutes".toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        check(code in 200..299 && json.optBoolean("ok")) {
            if (code == 401) "Manager access has expired. Sign in again."
            else "SSH status unavailable. The node may need the SSH control update or owner permission."
        }
        return json
    } finally { c.disconnect() }
}

@Composable
fun ManagerSshPanel(baseUrl: String, authenticated: Boolean) {
    val lifecycle = (LocalContext.current as ComponentActivity).lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var report by remember(baseUrl, authenticated) { mutableStateOf<JSONObject?>(null) }
    var csrf by remember(baseUrl, authenticated) { mutableStateOf("") }
    var message by remember(baseUrl, authenticated) { mutableStateOf("") }
    var busy by remember(baseUrl, authenticated) { mutableStateOf(false) }
    var requestedMinutes by remember(baseUrl, authenticated) { mutableStateOf<Int?>(null) }
    var refreshVersion by remember(baseUrl, authenticated) { mutableStateOf(0) }
    var remaining by remember(baseUrl, authenticated) { mutableStateOf<Int?>(null) }

    LaunchedEffect(baseUrl, authenticated, refreshVersion, foreground) {
        if (!authenticated || !foreground) {
            report = null
            remaining = null
            requestedMinutes = null
            return@LaunchedEffect
        }
        while (true) {
            busy = true
            try {
                val duration = requestedMinutes
                // Consume once: a failed response must never trigger another opening.
                requestedMinutes = null
                val result = withContext(Dispatchers.IO) { sshRequest(baseUrl, duration, csrf) }
                report = result.getJSONObject("ssh")
                csrf = result.getString("csrfToken")
                message = if (duration == null) "" else "Opening confirmed by the node."
                remaining = report?.let { if (it.isNull("remainingSeconds")) null else it.optInt("remainingSeconds") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report = null
                remaining = null
                csrf = ""
                message = e.message ?: "SSH status unavailable"
            } finally { busy = false }
            val seconds = remaining
            val started = SystemClock.elapsedRealtime()
            repeat(5) {
                delay(1000)
                if (seconds != null) remaining = (seconds - ((SystemClock.elapsedRealtime() - started) / 1000).toInt()).coerceAtLeast(0)
            }
        }
    }

    TaraSectionCard(title = "Administrative SSH", subtitle = "Access from your current IP to this installation") {
        val ssh = report
        val state = ssh?.optString("state") ?: "unknown"
        val expired = state == "open" && remaining == 0
        Text(when {
            expired -> "SSH: checking closure…"
            state == "open" -> "SSH: Open to your IP"
            state == "closed" -> "SSH: Closed to your IP"
            else -> "SSH: Status unknown"
        })
        ssh?.let {
            Text("Port ${it.optInt("port")} · Source ${it.optString("source")}")
            remaining?.takeIf { state == "open" }?.let { seconds -> Text("Closes in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}") }
        }
        if (ssh != null && state == "closed" && !ssh.optBoolean("canOpen")) {
            Text(if (!ssh.optBoolean("listening")) "The administrative SSH service is not listening."
                else "Timed opening is disabled by this node’s owner or policy.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open SSH for:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { minutes ->
                    Button(enabled = authenticated && !busy && ssh?.optBoolean("canOpen") == true && csrf.isNotBlank(), onClick = {
                        requestedMinutes = minutes
                        refreshVersion++
                    }) { Text("$minutes min") }
                }
            }
            Button(enabled = authenticated && !busy, onClick = { refreshVersion++ }) { Text("Refresh SSH status") }
        }
        if (message.isNotBlank()) Text(message)
        Text("The node enforces the expiry even when this app is closed. SSH authentication remains required.")
    }
}
