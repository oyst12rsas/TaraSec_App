package org.tarasec.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun TaraSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            content()
        }
    }
}

@Composable
fun TaraStatusRow(label: String, value: String, detail: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        if (!detail.isNullOrBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TaraSectionHeading(title: String, explanation: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (!explanation.isNullOrBlank()) {
            Text(explanation, style = MaterialTheme.typography.bodySmall)
        }
    }
}

enum class TaraMenuDestination {
    MY_ACCESS,
    FIND_INTERNET,
    STATUS_UNITS,
    SECURITY_DEMO,
    AI_ASSISTANCE,
    RESEARCH,
    SETUP_HOTSPOTS
}

const val CONSOLE_DESTINATION_EXTRA = "org.tarasec.app.CONSOLE_DESTINATION"

private val taraMenuItems = listOf(
    TaraMenuDestination.MY_ACCESS to "My access",
    TaraMenuDestination.FIND_INTERNET to "Find Internet access",
    TaraMenuDestination.STATUS_UNITS to "Status / Units",
    TaraMenuDestination.SECURITY_DEMO to "Security Demo",
    TaraMenuDestination.AI_ASSISTANCE to "AI / Assistance",
    TaraMenuDestination.RESEARCH to "Research",
    TaraMenuDestination.SETUP_HOTSPOTS to "Setup / Gateways"
)

@Composable
fun TaraHamburgerMenu(onSelect: (TaraMenuDestination) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("☰", style = MaterialTheme.typography.headlineSmall)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            taraMenuItems.forEach { (destination, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(destination)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("About Us") },
                onClick = {
                    expanded = false
                    context.startActivity(Intent(context, AboutUsActivity::class.java))
                }
            )
        }
    }
}
