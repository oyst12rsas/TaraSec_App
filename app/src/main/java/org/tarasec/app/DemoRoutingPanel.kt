package org.tarasec.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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

@Composable
fun DemoRoutingPanel(baseUrl: String) {
    val activity = LocalContext.current as ComponentActivity
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
            "The gateway must fail closed if a tagged participant route is unavailable. This panel reports DB configuration; it does not yet prove that taralink installed the Linux policy route.",
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
                    TaraStatusRow(
                        route.partnerName.ifBlank { "TaraSec partner" },
                        "${route.destinationIp} / ${route.netmask} → NetBird ${route.taggedTrafficRoute}"
                    )
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
    }
}
