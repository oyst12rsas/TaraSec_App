package org.tarasec.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun PartnerNetworkStatus(baseUrl: String, gatewayControlBase: String?, onStatus: (String) -> Unit) {
    val callback by rememberUpdatedState(onStatus)
    val selectedIp = gatewayControlBase?.let { runCatching { URL(it).host }.getOrNull() }?.takeIf { it.matches(Regex("[0-9.]+")) }
    var status by remember(baseUrl, selectedIp) { mutableStateOf<JSONObject?>(null) }
    var failure by remember(baseUrl, selectedIp) { mutableStateOf("") }
    LaunchedEffect(baseUrl, selectedIp) {
        callback(JSONObject().put("status", "checking").put("selected_gateway", selectedIp ?: "DB path").toString())
        while (true) {
            try {
                val fresh = withContext(Dispatchers.IO) { DemoPartnerClient.request(baseUrl, "appPartnerStatus.php" + (selectedIp?.let { "?gateway_ip=$it" } ?: "")) }
                status = fresh
                callback(fresh.toString())
                failure = ""
            } catch (e: Exception) { failure = e.message ?: "DB status unavailable"; callback(JSONObject().put("ok", false).put("error", failure).put("stale", true).toString()) }
            delay(5000)
        }
    }
    val partner = status?.optJSONObject("partner")
    val blacklisted = (partner?.optInt("restriction_seconds_remaining") ?: 0) > 0
    val age = if (partner == null || partner.isNull("age_seconds")) null else partner.optInt("age_seconds")
    val tagging = partner?.optString("taggingState") ?: "unknown"
    val warning = failure.isNotBlank() || blacklisted || tagging == "failed" || age == null || age > 300
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Partner network status · ${if (selectedIp != null) "selected gateway" else "DB connection"}", style = MaterialTheme.typography.titleSmall)
        Text(when {
            failure.isNotBlank() -> "DB check failed: $failure. Previous status is stale."
            status == null -> "Checking DB…"
            partner == null -> "DB could not identify a partner for this connection."
            blacklisted -> {
                val d = status?.optJSONObject("distribution")
                "Gateway ${partner.optString("gateway_ip")}: BLACKLIST ISSUED · ${d?.optInt("applied") ?: 0}/${d?.optInt("expected") ?: 0} receivers applied it."
            }
            tagging == "failed" -> "Gateway ${partner.optString("gateway_ip")}: tagging failure recorded. Restriction is inactive; recovery is not verified."
            age == null || age > 300 -> "Gateway ${partner.optString("gateway_ip")}: tagging status unknown or stale."
            else -> "Gateway ${partner.optString("gateway_ip")}: $tagging · updated ${age}s ago."
        }, color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        status?.let { Text("DB observed source: ${it.optString("observed_source_ip")} · DB checked: ${it.optString("checked_at")}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun DemoPartnerPanel(baseUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("Start an exercise on an enabled test gateway.") }
    var busy by remember { mutableStateOf(false) }
    var attempts by rememberSaveable { mutableIntStateOf(0) }
    var checkedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(baseUrl, id, token) {
        if (id.isBlank()) return@LaunchedEffect
        while (true) {
            try {
                status = withContext(Dispatchers.IO) { DemoPartnerClient.request(baseUrl, "appDemo5.php?session_id=$id", token) }
                checkedAt = System.currentTimeMillis()
                message = "DB state refreshed."
            } catch (e: Exception) { message = "DB poll failed: ${e.message}. Previous status is stale." }
            delay(5000)
        }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { block() } catch (e: Exception) { message = e.message ?: "Demo 5 failed" }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Demo 5 · Partner loses tagging", style = MaterialTheme.typography.titleMedium)
        Text("A controlled connection hits a configured honeypot or reporting firewall rule. The receiver reports normally to the DB. Repeated untagged rejections after partner notification can produce a temporary restriction distributed to enabled receivers.")
        Text("Use an operator-enabled test gateway. A NAT gateway's address may represent every connected device. The receiver cannot determine whether the router or a subnode originated the attack. No malware is installed.")
        Text("With healthy tagging, the DB should observe tagged traffic. To exercise failure, the operator must arrange missing tagging on the dedicated test gateway. This app never stops tarakernel.")
        Button(enabled = !busy && (id.isBlank() || (status?.optInt("seconds_remaining") ?: 1) == 0), onClick = {
            action {
                val started = withContext(Dispatchers.IO) { DemoPartnerClient.request(baseUrl, "appDemo5.php?action=create", body = JSONObject()) }
                id = started.getString("session_id")
                token = started.getString("token")
                status = null
                attempts = 0
                message = "Exercise created. Wait for DB status, then send a test connection."
            }
        }) { Text("Start Demo 5") }
        val s = status
        if (s != null) {
            val stale = checkedAt == 0L || System.currentTimeMillis() - checkedAt > 15000
            Text("Gateway: ${s.optString("gateway_ip")} · observed source: ${s.optString("source_ip")}")
            Text("Receiver: ${s.optString("receiver_ip")}:${s.optInt("receiver_port")}")
            Text("State: ${s.optString("state")} · tagging: ${s.optString("tagging_state")}", color = if (s.optBoolean("blacklist_active") || stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("Partner notified: ${if (s.isNull("partner_notified_at")) "pending" else s.optString("partner_notified_at")} · grace: ${s.optInt("grace_seconds")}s")
            Text("Blacklist ${s.optString("scope")}: ${s.optString("distribution_state")} · ${s.optInt("applied_receivers")}/${s.optInt("expected_receivers")} applied · ${s.optInt("restriction_seconds_remaining")}s left")
            Text("DB checked: ${s.optString("checked_at")}${if (stale) " · STALE" else ""} · attempts: $attempts")
            val evidence = s.optJSONArray("evidence")
            if (evidence == null || evidence.length() == 0) Text("No receiver evidence yet.")
            else Text("Latest observed tag: ${evidence.getJSONObject(0).let { if (it.isNull("observedTag")) "unknown" else it.optString("observedTag") }}")
            val deliveries = s.optJSONArray("deliveries")
            if (deliveries != null) for (i in 0 until deliveries.length()) {
                val d = deliveries.getJSONObject(i)
                Text("${d.optString("receiver_ip")}: ${d.optString("state")} · ${d.optString("message")}", style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = !busy && !stale && s.optInt("seconds_remaining") > 0 && s.optString("state") != "released", onClick = {
                action {
                    val path = withContext(Dispatchers.IO) { DemoPartnerClient.request(baseUrl, "appPartnerStatus.php") }
                    check(path.optString("observed_source_ip") == s.optString("source_ip")) { "Network path changed. Release this exercise and reconnect to its gateway." }
                    attempts++
                    message = withContext(Dispatchers.IO) { DemoPartnerClient.probe(s.getString("receiver_ip"), s.getInt("receiver_port")) }
                }
            }) { Text("Send one test connection") }
            Text("After notification, wait for the grace period and send another connection. A timeout alone proves neither tagging loss nor enforcement.")
        }
        if (id.isNotBlank()) OutlinedButton(enabled = !busy, onClick = {
            action {
                val reply = withContext(Dispatchers.IO) { DemoPartnerClient.request(baseUrl, "appDemo5.php?action=release", token, JSONObject().put("session_id", id)) }
                message = reply.optString("message")
            }
        }) { Text("Release this exercise") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            val report = "TaraSec Demo 5 debug report\nai_background=https://tarasec.org/ai/demo-guide/\napp_version=${BuildConfig.VERSION_NAME}\ngenerated_at_epoch_ms=${System.currentTimeMillis()}\nsecrets=omitted (session token)\nattempts=$attempts\nlast_successful_poll_epoch_ms=$checkedAt\nmessage=$message\n${status?.toString(2) ?: "status=not_checked"}\nInstructions: distinguish rejection evidence, observed tags, notification, DB restriction, receiver application and release. A source IP does not prove the router itself is infected. Unknown tags are not zero. Name the device for any diagnostics."
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TaraSec Demo 5", report))
            message = "Debug report copied."
        }) { Text("Copy debug info for AI") }
        Text(message)
    }
}
