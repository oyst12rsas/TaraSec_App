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

private fun demo4DebugReport(baseUrl: String, status: Demo4RouteStatus?): String = buildString {
    appendLine("TaraSec Demo 4 debug report")
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
fun DemoRoutingPanel(baseUrl: String) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    var status by remember { mutableStateOf<Demo4RouteStatus?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        Thread {
            val result = DemoRoutingClient.routes(baseUrl)
            activity.runOnUiThread {
                status = result
                loading = false
            }
        }.start()
    }

    LaunchedEffect(baseUrl) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Demo 4 compares the route to the same VPS partner. CLEAN traffic uses its public IP normally; tagged INFECTED traffic is eligible for the configured NetBird route. Untagged traffic is unchanged.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "The gateway must fail closed if a tagged participant route is unavailable. This panel distinguishes a configured route from a route the gateway has reported as applied.",
            style = MaterialTheme.typography.bodySmall
        )

        val current = status
        when {
            current == null -> Text("Checking Demo 4 routes…")
            !current.reachable -> Text(
                "🔴 Route directory unavailable: ${current.message}",
                color = MaterialTheme.colorScheme.error
            )
            else -> {
                Text("🟢 ${current.message}")
                Text("Seen by DB server as ${current.sourceIp}", style = MaterialTheme.typography.bodySmall)
                current.routes.forEach { route ->
                    val stateIcon = when (route.applyState) {
                        "applied" -> "🟢"
                        "error" -> "🔴"
                        else -> "🟠"
                    }
                    TaraStatusRow(
                        (if (route.selected) "Selected · " else "") + route.partnerName.ifBlank { "TaraSec partner" },
                        "$stateIcon ${route.applyState.uppercase()} · ${route.destinationIp} / ${route.netmask} → NetBird ${route.taggedTrafficRoute}"
                    )
                    if (route.applyMessage.isNotBlank()) {
                        Text(route.applyMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = { refresh() }
        ) {
            Text(if (loading) "Checking…" else "Refresh Demo 4 routes")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("TaraSec Demo 4 debug report", demo4DebugReport(baseUrl, status))
                )
            }
        ) {
            Text("Copy debug info for AI")
        }
    }
}
