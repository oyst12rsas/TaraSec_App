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
fun ManagerSshPanel(baseUrl: String, installationName: String, installationId: String) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    val identity = ServiceDiscovery.selected(activity).identity
    val accountToken = SubscriberAccountClient.storedToken(activity)
    val owner = SshSessionStore.owner(installationId, identity, accountToken)
    fun currentAccount() = SubscriberAccountClient.storedToken(activity) == accountToken &&
        ServiceDiscovery.selected(activity).identity == identity &&
        SshSessionStore.owner(installationId, identity, accountToken) == owner
    var target by remember(baseUrl, owner) { mutableStateOf<SshApprovalTarget?>(null) }
    var pairing by remember(baseUrl, owner) { mutableStateOf<SshAppPairing?>(null) }
    var grant by remember(baseUrl, owner) { mutableStateOf<SshAppGrant?>(null) }
    var status by remember(baseUrl, owner) { mutableStateOf<JSONObject?>(null) }
    var message by remember(baseUrl, owner) { mutableStateOf("Checking this installation's SSH identity…") }
    var loginAttempt by remember(baseUrl, owner) { mutableStateOf(0) }
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
        if (!currentAccount()) { grant = null; status = null; deadline = 0; return }
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
                grant = null; pairing = null; code = ""
            }
        }
    }
    LaunchedEffect(baseUrl, owner) {
        try {
            val discovered = withContext(Dispatchers.IO) { SshApprovalClient.discover(baseUrl) }
            if (!currentAccount()) return@LaunchedEffect
            val saved = SshSessionStore.read(SshSessionKey(owner, discovered))
            grant = saved?.grant; pairing = saved?.pairing; jobId = saved?.jobId.orEmpty()
            target = discovered
            message = if (grant != null) "Checking your existing SSH authorization…"
                else "Connect your Google account to authorize SSH for this installation."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "SSH identity is unavailable." }
    }
    LaunchedEffect(target, grant, owner, loginAttempt) {
        val t = target
        if (t != null && grant == null && pairing == null && accountToken != null &&
            SshSessionStore.trustsAccount(t, identity)) {
            busy = true
            message = "Verifying your signed-in account for this node…"
            try {
                val g = withContext(Dispatchers.IO) { SshApprovalClient.accountLogin(t, identity, accountToken) }
                if (currentAccount()) {
                    SshSessionStore.put(SshSessionKey(owner, t), SshSession(grant = g, jobId = jobId))
                    grant = g
                    message = "Account confirmed. Choose a duration and enter your Authenticator code."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Account verification is unavailable." }
            finally { busy = false }
        }
    }
    LaunchedEffect(pairing, foreground) {
        val p = pairing
        val t = target
        if (p != null && t != null && foreground) {
            while (grant == null) {
                try {
                    val g = withContext(Dispatchers.IO) { SshApprovalClient.exchange(t, p) }
                    if (g != null) {
                        if (!currentAccount()) break
                        SshSessionStore.put(SshSessionKey(owner, t), SshSession(grant = g, jobId = jobId))
                        grant = g; pairing = null
                        message = "Google sign-in confirmed. Choose a duration and enter your Authenticator code."
                        refresh()
                        break
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    message = e.message ?: "Sign-in failed."; pairing = null
                    SshSessionStore.remove(SshSessionKey(owner, t)); break
                }
                delay(3000)
            }
        }
    }
    LaunchedEffect(grant, foreground) {
        if (grant != null && foreground) {
            while (true) { if (!busy) refresh(); delay(5000) }
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
        if (grant == null && t != null) {
            if (accountToken != null && SshSessionStore.trustsAccount(t, identity)) {
                TextButton(enabled = !busy && pairing == null, onClick = { loginAttempt++ }) {
                    Text("Retry account connection")
                }
            }
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val p = withContext(Dispatchers.IO) { SshApprovalClient.start(t) }
                        if (!currentAccount()) return@launch
                        SshSessionStore.put(SshSessionKey(owner, t), SshSession(pairing = p,
                            pairingUntil = System.currentTimeMillis() / 1000 + 300, jobId = jobId))
                        pairing = p
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.browserUrl(p.id))))
                        message = "Complete Google sign-in and Connect app in the browser, then return here."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "Could not start sign-in." }
                    finally { busy = false }
                }
            }) { Text(if (pairing == null) "Connect Google account for SSH" else "Restart Google connection") }
        }
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
                        check(currentAccount()) { "Account changed. Reopen SSH." }
                        jobId = withContext(Dispatchers.IO) { SshApprovalClient.open(selected, g, duration, submittedCode) }
                        if (!currentAccount()) return@launch
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
            TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Refresh status") }
        }
        Text("Your signed-in account is reused when available. Opening SSH still requires your Authenticator code for this node.",
            style = MaterialTheme.typography.bodySmall)
    }
}
