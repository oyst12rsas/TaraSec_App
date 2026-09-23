package org.tarasec.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.URL

private fun demo4DebugReport(baseUrl: String, status: Demo4RouteStatus?, runLog: List<String>): String = buildString {
    appendLine("TaraSec Demo 4 debug report")
    appendLine("ai_background=https://tarasec.org/ai/demo-guide/")
    appendLine("For an AI session unfamiliar with TaraSec: read the ai_background page before interpreting this report.")
    appendLine("generated_at_epoch_ms=${System.currentTimeMillis()}")
    appendLine()
    appendLine("[instructions_for_ai]")
    appendLine("Explain this Demo 4 result in plain language to the person testing TaraSec.")
    appendLine("First explain the expected behavior: CLEAN traffic uses its normal public route; tagged INFECTED traffic is selectively routed through the configured TaraSec/partner NetBird route; unrelated untagged traffic remains unchanged.")
    appendLine("Identify every non-green, missing, stale, pending, or error state and explain what it means.")
    appendLine("Distinguish configuration from observed gateway state. Never claim a configured route was actually used unless apply_state=applied.")
    appendLine("For each issue, name the most likely component involved (app, DB/API, gateway, NetBird route, or partner route) only when supported by this report. Do not invent a cause.")
    appendLine("Give the safest next diagnostic step and explicitly say which computer/device it should be run on. Do not suggest destructive changes before diagnostics.")
    appendLine("If everything shown is green/applied, explain what is verified and what this report alone does NOT prove.")
    appendLine()
    appendLine("[phone_test]")
    if (runLog.isEmpty()) appendLine("not_run") else runLog.forEach { appendLine(it) }
    appendLine("The DB session joins gateway-confirmed phone state with website-observed source IP. A relay observation is still needed for full route proof.")
    appendLine()
    appendLine("[connection]")
    appendLine("db_endpoint=${baseUrl.trimEnd('/')}")
    if (status == null) {
        appendLine("status=not_checked")
        return@buildString
    }
    appendLine("reachable=${status.reachable}")
    appendLine("message=${status.message}")
    appendLine("source_ip=${status.sourceIp.ifBlank { "unknown" }}")
    appendLine("mode=${status.mode.ifBlank { "unknown" }}")
    appendLine()
    appendLine("[routes]")
    if (status.routes.isEmpty()) {
        appendLine("none")
    } else {
        status.routes.forEachIndexed { index, route ->
            appendLine("route_${index + 1}:")
            appendLine("  selected=${route.selected}")
            appendLine("  partner=${route.partnerName.ifBlank { "TaraSec partner" }}")
            appendLine("  destination=${route.destinationIp}/${route.netmask}")
            appendLine("  tagged_traffic_route=${route.taggedTrafficRoute}")
            appendLine("  apply_state=${route.applyState}")
            appendLine("  apply_message=${route.applyMessage.ifBlank { "none" }}")
            appendLine("  configured_updated_at=${route.updatedAt ?: "unknown"}")
            appendLine("  gateway_reported_at=${route.reportedAt ?: "unknown"}")
        }
    }
}

@Composable
fun DemoRoutingPanel(baseUrl: String, gatewayControlBase: String?) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    var status by remember { mutableStateOf<Demo4RouteStatus?>(null) }
    var websiteIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerId by remember(baseUrl, gatewayControlBase) { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var runStage by remember { mutableStateOf("") }
    var runLog by remember(baseUrl, gatewayControlBase) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(baseUrl) {
        Thread {
            val result = DemoRoutingClient.routes(baseUrl)
            val resolvedIps = DemoRoutingClient.websiteAddresses()
            activity.runOnUiThread {
                status = result
                websiteIps = resolvedIps
            }
        }.start()
    }

    fun runDemo(route: Demo4Route, control: String) {
        if (running) return
        running = true
        runStage = "Creating DB session…"
        runLog = emptyList()
        viewerId = ""
        Thread {
            val lines = mutableListOf<String>()
            var touchedState = false
            var currentStep = "create_session"
            fun stage(value: String) { activity.runOnUiThread { runStage = value } }
            fun confirmed(infected: Boolean): Boolean {
                repeat(6) {
                    val state = DemoClient.localThreatStatusBase(control)
                    if (state.reachable && state.infected == infected) return true
                    Thread.sleep(800)
                }
                return false
            }
            try {
                val session = DemoRoutingClient.createSession(baseUrl)
                check(session.sessionId.isNotBlank()) { session.error }
                activity.runOnUiThread { viewerId = session.sessionId }
                lines += "db_session_created=true"
                currentStep = "set_clean"
                stage("Setting CLEAN…")
                touchedState = true
                DemoClient.setGatewayInfected(control, false)
                check(confirmed(false)) { "Gateway did not confirm CLEAN locally" }
                currentStep = "confirm_clean_gateway"
                val cleanGateway = DemoRoutingClient.confirmGateway(
                    control, session.sessionId, session.token, "clean")
                check(cleanGateway.reachable) { cleanGateway.detail }
                lines += "clean_gateway_confirmed=true"
                stage("Website observing CLEAN request…")
                currentStep = "observe_clean_website"
                val clean = DemoRoutingClient.recordObservation(session.sessionId, session.token, "clean")
                lines += "clean_website_observation=${clean.detail}"
                check(clean.reachable) { "CLEAN website request failed" }

                currentStep = "set_infected"
                stage("Setting INFECTED…")
                DemoClient.setGatewayInfected(control, true)
                check(confirmed(true)) { "Gateway did not confirm INFECTED locally" }
                currentStep = "confirm_infected_gateway"
                val infectedGateway = DemoRoutingClient.confirmGateway(
                    control, session.sessionId, session.token, "infected")
                check(infectedGateway.reachable) { infectedGateway.detail }
                lines += "infected_gateway_confirmed=true"
                stage("Waiting for gateway tag update…")
                Thread.sleep(3000)
                stage("Website observing a new tagged connection…")
                currentStep = "observe_infected_website"
                val infected = DemoRoutingClient.recordObservation(
                    session.sessionId, session.token, "infected")
                lines += "infected_website_observation=${infected.detail}"
                check(infected.reachable) { "INFECTED website request failed" }
                lines += "phone_test=completed"
            } catch (e: Exception) {
                lines += "phone_test=incomplete"
                lines += "failed_step=$currentStep"
                lines += "reason=${e.message ?: "Unexpected test error"}"
            } finally {
                if (touchedState) {
                    stage("Restoring CLEAN…")
                    DemoClient.setGatewayInfected(control, false)
                    lines += "restored_clean=${runCatching { confirmed(false) }.getOrDefault(false)}"
                }
                lines += "gateway_route_state_at_start=${route.applyState}"
                lines += "route_proof=requires_relay_evidence"
                activity.runOnUiThread {
                    runLog = lines.toList()
                    runStage = ""
                    running = false
                }
            }
        }.start()
    }


    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Demo 4 targets a practical ISP challenge: a TaraSec hotspot may sit behind mobile-provider NAT or CGNAT and therefore has no stable public source address that another ISP can recognize.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "The intended solution is selective egress. Ordinary hotspot traffic keeps using the normal ISP path. Only TaraSec-selected security traffic is sent through an automatically provisioned encrypted tunnel to a TaraSec egress router with a stable public IP.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "That stable address is not a whitelist. It identifies a recognized TaraSec security path so the receiving ISP can trust where the security signal came from and apply its own blocking, rate-limiting or investigation policy.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "For production, WireGuard peer registration should be part of the standard hotspot installation: the hotspot creates a key, TaraSec assigns an egress router and tunnel address, and the egress router receives the peer automatically. No manual per-hotspot NetBird route should be required.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "The current directory below is the control-plane configuration used by the Demo 4 test. The completed packet-path test must still prove that only TaraSec-selected flows leave through the fixed public TaraSec egress address while ordinary traffic remains on the hotspot ISP.",
            style = MaterialTheme.typography.bodySmall
        )

        val current = status
        when {
            current == null -> Text("Checking Demo 4 partner routes…")
            !current.reachable -> Text(
                "🔴 Partner route directory unavailable: ${current.message}",
                color = MaterialTheme.colorScheme.error
            )
            else -> {
                Text("🟢 ${current.message}")
                Text("DB server sees this app path as ${current.sourceIp}", style = MaterialTheme.typography.bodySmall)
                current.routes.forEach { route ->
                    val stateIcon = when (route.applyState) {
                        "applied" -> "🟢"
                        "error" -> "🔴"
                        else -> "🟠"
                    }
                    TaraStatusRow(
                        (if (route.selected) "Selected · " else "") + route.partnerName.ifBlank { "TaraSec partner" },
                        "$stateIcon ${route.applyState.uppercase()} · ${route.destinationIp} / ${route.netmask} → configured security path ${route.taggedTrafficRoute}"
                    )
                    if (route.applyMessage.isNotBlank()) {
                        Text(route.applyMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        val chosenRoute = status?.routes?.firstOrNull { it.selected }
            ?: status?.routes?.singleOrNull()
        Text(
            "Run the test in this app and share the website viewer link. The DB server coordinates the session, the gateway confirms the phone status, and TaraSec.org records each source IP it receives.",
            style = MaterialTheme.typography.bodySmall
        )
        val websiteRouteReady = websiteIps.size == 1 &&
            chosenRoute?.destinationIp == websiteIps.first()
        val demoGatewayAddresses = setOf("100.68.25.154", "100.68.153.251", "100.68.165.190")
        val controlHost = gatewayControlBase?.let { runCatching { URL(it).host }.getOrNull() }
        val expectedGateway = current?.sourceIp?.takeIf { it in demoGatewayAddresses }
        val demo4Control = if (expectedGateway != null && controlHost in demoGatewayAddresses) {
            "http://$expectedGateway"
        } else {
            gatewayControlBase
        }
        Text("Demo 4 gateway control: ${demo4Control ?: "unavailable"}",
            style = MaterialTheme.typography.bodySmall)
        if (demo4Control != gatewayControlBase) {
            Text("Demo 4 selected the gateway used by this phone's DB connection.",
                style = MaterialTheme.typography.bodySmall)
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !running && chosenRoute != null && websiteRouteReady &&
                chosenRoute.netmask == "255.255.255.255" && demo4Control != null,
            onClick = {
                val route = chosenRoute
                val control = demo4Control
                if (route != null && control != null) runDemo(route, control)
            }
        ) { Text(if (running) "Running Demo 4…" else "Run Demo 4 and show IP on website") }
        if (demo4Control == null) {
            Text("Connect to a TaraSec gateway to run the test.", color = MaterialTheme.colorScheme.error)
        } else if (chosenRoute == null) {
            Text("Select a Demo 4 route on the gateway first.", color = MaterialTheme.colorScheme.error)
        } else if (chosenRoute.netmask != "255.255.255.255") {
            Text("Demo 4 needs a single /32 destination.", color = MaterialTheme.colorScheme.error)
        } else if (!websiteRouteReady) {
            Text(
                "The selected route targets ${chosenRoute.destinationIp}, but tarasec.org currently resolves to ${websiteIps.joinToString().ifEmpty { "unknown" }}. The website must have one stable IPv4 destination authorized by the gateway and relay before this test can run.",
                color = MaterialTheme.colorScheme.error
            )
        }
        if (viewerId.isNotBlank()) {
            val viewerUrl = "https://tarasec.org/demo4/observe.php?id=$viewerId"
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Demo 4 live viewer", viewerUrl))
                }
            ) { Text("Copy live website viewer link") }
            Text(viewerUrl, style = MaterialTheme.typography.bodySmall)
        }
        if (runStage.isNotBlank()) Text(runStage)
        runLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("TaraSec Demo 4 debug report", demo4DebugReport(baseUrl, status, runLog))
                )
            }
        ) {
            Text("Copy debug info for AI")
        }
    }
}
