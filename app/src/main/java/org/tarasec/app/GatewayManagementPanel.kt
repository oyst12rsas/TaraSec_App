package org.tarasec.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
fun GatewayManagementPanel(baseUrl: String) {
    val activity = LocalContext.current as Activity
    var status by remember(baseUrl) { mutableStateOf<ManagedGatewayStatus?>(null) }
    var message by remember(baseUrl) { mutableStateOf("Gateway management status not loaded") }
    var loading by remember(baseUrl) { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        message = "Loading gateway management status..."
        Thread {
            try {
                val result = GatewayManagerClient.status(baseUrl)
                activity.runOnUiThread {
                    status = result
                    message = if (result.reachable) "Gateway is reachable." else "Gateway reported itself unavailable."
                    loading = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    status = null
                    message = e.message ?: "Gateway management request failed"
                    loading = false
                }
            }
        }.start()
    }

    LaunchedEffect(baseUrl) {
        if (baseUrl.isNotBlank()) refresh()
    }

    TaraSectionCard(
        title = "Gateway management",
        subtitle = "Capabilities are discovered from this gateway. Features that are not available stay out of the normal app workflow."
    ) {
        Text(baseUrl, style = MaterialTheme.typography.bodySmall)
        status?.let { gateway ->
            TaraStatusRow("Gateway", gateway.name)
            TaraStatusRow("Reachable", if (gateway.reachable) "Yes" else "No")
            if (gateway.managerEmail.isNotBlank()) TaraStatusRow("Manager", gateway.managerEmail)
            if (gateway.serverTime.isNotBlank()) TaraStatusRow("Gateway time", gateway.serverTime)

            val available = buildList {
                if (gateway.status) add("status")
                if (gateway.assistance) add("assistance")
                if (gateway.threats) add("threats")
                if (gateway.units) add("units")
                if (gateway.notifications) add("remote notifications")
            }
            Text(
                "Available: ${available.ifEmpty { listOf("status only") }.joinToString()}",
                style = MaterialTheme.typography.bodySmall
            )

            val unavailable = buildList {
                if (!gateway.threats) add("threats")
                if (!gateway.units) add("units")
                if (!gateway.notifications) add("remote notifications")
            }
            if (unavailable.isNotEmpty()) {
                Text("Not advertised by gateway: ${unavailable.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !loading, onClick = { refresh() }) {
                Text(if (loading) "Refreshing..." else "Refresh gateway")
            }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
