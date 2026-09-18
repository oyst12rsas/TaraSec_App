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
                    TaraStatusRow(
                        route.partnerName.ifBlank { "TaraSec partner" },
                        "${route.destinationIp} / ${route.netmask} → configured security path ${route.taggedTrafficRoute}"
                    )
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = { refresh() }
        ) {
            Text(if (loading) "Checking…" else "Refresh Demo 4 partner routes")
        }
    }
}
