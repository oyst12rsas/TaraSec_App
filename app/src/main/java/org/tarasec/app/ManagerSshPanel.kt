package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun ManagerSshPanel(baseUrl: String, installationName: String, managementOwner: String, onSessionExpired: () -> Unit) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    val owner = managementOwner
    var enrollmentUser by remember(baseUrl, owner) { mutableStateOf("") }
    var enrollmentDevice by remember(baseUrl, owner) { mutableStateOf("") }
    var pairingCode by remember(baseUrl, owner) { mutableStateOf("") }
    var pairingUntil by remember(baseUrl, owner) { mutableStateOf(0L) }
    var target by remember(baseUrl, owner) { mutableStateOf<SshApprovalTarget?>(null) }
    var grant by remember(baseUrl, owner) { mutableStateOf<SshAppGrant?>(null) }
    var status by remember(baseUrl, owner) { mutableStateOf<JSONObject?>(null) }
    var message by remember(baseUrl, owner) { mutableStateOf("Checking this installation's SSH identity…") }
    var busy by remember(baseUrl, owner) { mutableStateOf(false) }
    var duration by remember(baseUrl, owner) { mutableStateOf(300) }
    var code by remember(baseUrl, owner) { mutableStateOf("") }
    var jobId by remember(baseUrl, owner) { mutableStateOf("") }
    var foreground by remember { mutableStateOf(true) }
    var remaining by remember(baseUrl, owner) { mutableStateOf(0L) }
    var deadline by remember(baseUrl, owner) { mutableStateOf(0L) }
    var offset by remember(baseUrl, owner) { mutableStateOf(0L) }

    DisposableEffect(activity) {
        val wasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val owner = activity as androidx.activity.ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE) foreground = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (!wasSecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    suspend fun refresh() {
        val t = target ?: return
        val g = grant ?: return
        try {
            val value = withContext(Dispatchers.IO) { SshApprovalClient.status(t, g) }
            status = value
            val now = value.optLong("server_time")
            offset = now - System.currentTimeMillis() / 1000
            val node = value.getJSONArray("checks").getJSONObject(0)
            val access = node.optJSONObject("ssh_access")
            val at = maxOf(node.optLong("at"), node.optLong("ssh_access_at"))
            val expires = access?.optLong("expires_at") ?: 0
            val proposals = value.optJSONArray("proposals")
            val job = (0 until (proposals?.length() ?: 0)).mapNotNull { proposals?.optJSONObject(it) }
                .firstOrNull { it.optString("id") == jobId }
            val confirmed = access?.optString("state") == "open" && now - at in 0..180 &&
                expires > now && (jobId.isBlank() ||
                    (job?.optString("state") == "done" && job.optLong("open_until") == expires))
            deadline = if (confirmed) expires else 0
            if (confirmed) message = "SSH opening confirmed by this node."
            else if (jobId.isNotBlank()) message = when (job?.optString("state")) {
                "failed", "rejected", "cancelled", "expired" -> "The node did not open SSH. Refresh before trying again."
                "done" -> "The opening has ended or is not currently confirmed."
                else -> "Approved request is waiting for confirmation from this node."
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            status = null; deadline = 0
            message = e.message ?: "SSH status is unavailable."
            if (e is SshSignInRequiredException) {
                SshSessionStore.remove(SshSessionKey(owner, t))
                grant = null; code = ""; onSessionExpired()
            }
        }
    }
    LaunchedEffect(baseUrl, owner) {
        try {
            val discovered = withContext(Dispatchers.IO) { SshApprovalClient.discover(baseUrl) }
            val saved = SshSessionStore.read(SshSessionKey(owner, discovered))
            grant = saved?.grant; jobId = saved?.jobId.orEmpty()
            target = discovered
            message = if (grant != null) "Checking your existing SSH authorization…"
                else "Management authorization expired. Sign in again from Manage."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "SSH identity is unavailable." }
    }
    LaunchedEffect(grant, foreground) {
        if (grant != null && foreground) {
            while (true) { if (!busy) refresh(); delay(5000) }
        }
    }
    LaunchedEffect(pairingUntil) {
        if (pairingUntil > 0) {
            delay(maxOf(0L, pairingUntil * 1000 - System.currentTimeMillis()))
            pairingCode = ""; pairingUntil = 0
        }
    }
    LaunchedEffect(deadline, offset) {
        while (true) {
            remaining = maxOf(0, deadline - (System.currentTimeMillis() / 1000 + offset))
            delay(1000)
        }
    }
    TaraSectionCard(title = "SSH access", subtitle = "Temporary opening for this installation only") {
        Text("Installation: $installationName")
        val node = status?.optJSONArray("checks")?.optJSONObject(0)
        if (node != null) Text("Enrolled node: " + node.optString("nickname"))
        Text(message)
        if (remaining > 0) Text("Open — ${remaining / 60}m ${remaining % 60}s remaining")
        val t = target
        if (grant != null) {
            val available = status?.optBoolean("totp_configured") == true &&
                node?.optJSONObject("ssh_access")?.optBoolean("enabled") == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { minutes ->
                    FilterChip(selected = duration == minutes * 60, enabled = !busy && remaining == 0L,
                        onClick = { duration = minutes * 60 }, label = { Text("$minutes min") })
                }
            }
            OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Google Authenticator code") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = !busy && remaining == 0L)
            Button(enabled = available && !busy && code.length == 6 && remaining == 0L, onClick = {
                val g = grant ?: return@Button
                val selected = target ?: return@Button
                val submittedCode = code; code = ""; busy = true
                scope.launch {
                    try {
                        jobId = withContext(Dispatchers.IO) { SshApprovalClient.open(selected, g, duration, submittedCode) }
                        SshSessionStore.put(SshSessionKey(owner, selected), SshSession(grant = g, jobId = jobId))
                        message = "Approved. Waiting for this node to confirm the opening."
                        refresh()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "SSH request failed." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Requesting…" else "Open SSH for ${duration / 60} minutes") }
            if (status?.optBoolean("totp_configured") == false && t != null) {
                Text("Set up your Authenticator once on the secure account page.")
                TextButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.browserUrl()))) }) {
                    Text("Set up Authenticator")
                }
            }
            HorizontalDivider()
            Text("Add an SSH device", style = MaterialTheme.typography.titleMedium)
            Text("Pair Acer or Termux, then approve its exact public key on the secure website. Existing SSH settings stay unchanged.")
            OutlinedTextField(value = enrollmentUser, onValueChange = { enrollmentUser = it.take(32) },
                label = { Text("SSH account on this server") }, singleLine = true, enabled = !busy)
            OutlinedTextField(value = enrollmentDevice, onValueChange = { enrollmentDevice = it.take(48) },
                label = { Text("Device name, for example Acer or Termux") }, singleLine = true, enabled = !busy)
            Button(enabled = !busy && enrollmentUser.isNotBlank() && enrollmentDevice.isNotBlank(), onClick = {
                val selected = target ?: return@Button
                val g = grant ?: return@Button
                busy = true; pairingCode = ""
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { SshApprovalClient.pairDevice(selected, g, enrollmentUser, enrollmentDevice) }
                        pairingUntil = result.getLong("expires_at")
                        pairingCode = result.getString("pairing_token")
                        message = "Pairing ready for this server. Submit the device key, then approve on the website."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "SSH pairing unavailable. Update the approval service." }
                    finally { busy = false }
                }
            }) { Text("Start device pairing") }
            if (pairingCode.isNotBlank()) {
                Text("On Acer or Termux, run the pairing helper from the updated taransvar checkout:")
                Text("python3 ~/taransvar/misc/pair_ssh_device.py --service " + target!!.base)
                Text("Paste this short-lived pairing code when prompted. It expires after 10 minutes:")
                Text(pairingCode)
                TextButton(onClick = {
                    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("TaraSec SSH pairing", pairingCode)
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    clipboard.setPrimaryClip(clip)
                }) { Text("Copy pairing code") }
                TextButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target!!.browserUrl()))) }) {
                    Text("Approve device on website")
                }
                Text("Only the public key is sent. After approval, test a new SSH login before requiring key + password.")
            }
            TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Refresh status") }
        }
        Text("Your fresh management sign-in covers SSH for this server. Opening SSH still requires your Authenticator code.",
            style = MaterialTheme.typography.bodySmall)
    }
}
