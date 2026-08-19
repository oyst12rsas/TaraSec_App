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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gateway", style = MaterialTheme.typography.titleMedium)
        Text(baseUrl, style = MaterialTheme.typography.bodySmall)
        status?.let { gateway ->
            Text(gateway.name)
            Text("Manager: ${gateway.managerEmail}", style = MaterialTheme.typography.bodySmall)
            if (gateway.serverTime.isNotBlank()) Text("Gateway time: ${gateway.serverTime}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Available now: status${if (gateway.assistance) ", assistance" else ""}",
                style = MaterialTheme.typography.bodySmall
            )
            val planned = buildList {
                if (!gateway.threats) add("threats")
                if (!gateway.units) add("units")
                if (!gateway.notifications) add("remote notifications")
            }
            if (planned.isNotEmpty()) Text("Next: ${planned.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = !loading, onClick = { refresh() }) {
                Text(if (loading) "Refreshing..." else "Refresh gateway")
            }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
