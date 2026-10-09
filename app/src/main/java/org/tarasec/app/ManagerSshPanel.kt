package org.tarasec.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun sshRequest(base: String, action: String? = null, seconds: Int = 0, csrf: String = ""): JSONObject {
    val connection = URL("${base.trimEnd('/')}/script/managerSsh.php").openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (action != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = "action=$action&seconds=$seconds&csrf=${URLEncoder.encode(csrf, "UTF-8")}".toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        if (code == 404) throw IllegalStateException("Temporary SSH controls are not installed on this gateway.")
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = JSONObject(body)
        if (code !in 200..299 || !json.optBoolean("ok")) {
            throw IllegalStateException(when (json.optString("error")) {
                "manager_login_required", "manager_access_revoked" -> "Reconnect management access before using SSH controls."
                "csrf_required" -> "Refresh SSH status, then try again."
                                "ssh_request_rate_limit" -> "Too many SSH requests. Wait a minute and try again."
                else -> "SSH controls are unavailable (HTTP $code)."
            })
        }
        return json
    } finally {
        connection.disconnect()
    }
}

@Composable
fun ManagerSshPanel(baseUrl: String) {
    var state by remember(baseUrl) { mutableStateOf<JSONObject?>(null) }
    var message by remember(baseUrl) { mutableStateOf("Loading SSH status…") }
    var busy by remember(baseUrl) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            state = withContext(Dispatchers.IO) { sshRequest(baseUrl) }
            message = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state = null; message = e.message ?: "SSH status is unknown." }
    }
    fun submit(action: String, seconds: Int = 0) {
        val csrf = state?.optString("csrf").orEmpty()
        if (busy || csrf.isBlank()) return
        busy = true
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) { sshRequest(baseUrl, action, seconds, csrf) }
                message = "Request queued. Waiting for gateway confirmation."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "SSH request failed." }
            finally { busy = false }
        }
    }
    LaunchedEffect(baseUrl) {
        while (true) { if (!busy) refresh(); delay(5000) }
    }
    TaraSectionCard(title = "SSH", subtitle = "Temporarily open this gateway’s SSH port, then connect with your SSH client.") {
        val current = state
        val enabled = current?.optBoolean("enabled") == true
        val remaining = current?.optInt("remainingSeconds", 0) ?: 0
        val latest = current?.optJSONObject("latestRequest")
        val requestState = latest?.optString("state").orEmpty()
        TaraStatusRow("Temporary access", when {
            current == null || !enabled -> "Unknown / unavailable"
            remaining > 0 -> "Open — ${remaining / 60}m ${remaining % 60}s remaining"
            else -> "Closed"
        })
        if (current != null && !current.isNull("port")) {
            val port = current.optInt("port")
            TaraStatusRow("SSH port", port.toString())
            Text("Connect to ${URL(baseUrl).host} on port $port from your computer or SSH client.", style = MaterialTheme.typography.bodySmall)
        }
        if (requestState == "pending") Text("Request queued; access has not yet been confirmed.")
        if (requestState == "rejected") Text("The gateway rejected the last SSH request. Refresh before retrying.")
        if (current != null && !enabled) Text(when (current.optString("error")) {
                        "ssh_disabled_by_owner" -> "Temporary SSH controls are disabled by the gateway owner."
            else -> "Temporary SSH controls need to be installed or the gateway worker is unavailable."
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15).forEach { minutes ->
                Button(enabled = enabled && !busy && requestState != "pending", onClick = { submit("open", minutes * 60) }) { Text("$minutes min") }
            }
        }
        OutlinedButton(enabled = enabled && !busy && remaining > 0, onClick = { submit("close") }) { Text("End temporary opening") }
        OutlinedButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Refresh") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        Text("Only this temporary allowance expires. Existing owner and recovery access still follows the gateway’s firewall policy. SSH login credentials are still required.", style = MaterialTheme.typography.bodySmall)
    }
}
