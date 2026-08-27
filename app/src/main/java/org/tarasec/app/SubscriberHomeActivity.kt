package org.tarasec.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class SubscriberHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SubscriberHome(
                        openConsole = {
                            AppRoleStore.save(this, AppRole.HOTSPOT_OWNER)
                            startActivity(Intent(this, MainActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SubscriberHome(openConsole: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    var role by remember { mutableStateOf(AppRoleStore.load(activity)) }
    var hotspots by remember { mutableStateOf<List<DirectoryHotspot>>(emptyList()) }
    var directoryStatus by remember { mutableStateOf("Finding TaraSec hotspots...") }
    var loading by remember { mutableStateOf(false) }

    fun refreshDirectory() {
        if (loading) return
        loading = true
        directoryStatus = "Finding TaraSec hotspots..."
        Thread {
            try {
                val result = HotspotDirectoryClient.list()
                activity.runOnUiThread {
                    hotspots = result
                    directoryStatus = when {
                        result.isEmpty() -> "No participating hotspots are published yet."
                        result.size == 1 -> "1 participating hotspot found."
                        else -> "${result.size} participating hotspots found."
                    }
                    loading = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    directoryStatus = "Hotspot directory unavailable: ${e.message ?: e.javaClass.simpleName}"
                    loading = false
                }
            }
        }.start()
    }

    LaunchedEffect(Unit) { refreshDirectory() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
        Text("Find secure Internet access", style = MaterialTheme.typography.titleLarge)
        Text(
            "When you are not directly connected to one of your own TaraSec installations, finding a participating hotspot is the main experience.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text("Current role: ${roleLabel(role)}", style = MaterialTheme.typography.bodySmall)

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = { refreshDirectory() }
        ) { Text(if (loading) "Finding hotspots..." else "Find TaraSec WiFi") }

        Text(directoryStatus)

        hotspots.forEach { hotspot ->
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(hotspot.name, style = MaterialTheme.typography.titleMedium)
                    val place = listOfNotNull(hotspot.locality, hotspot.countryCode).joinToString(", ")
                    if (place.isNotBlank()) Text(place)
                    hotspot.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("TaraSec hotspot ID: ${hotspot.id}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()
        Text("My access", style = MaterialTheme.typography.titleMedium)
        Text("Subscription, payment and cross-hotspot access will appear here as the central subscriber account is connected to the app.")

        HorizontalDivider()
        Text("TaraSec Security", style = MaterialTheme.typography.titleMedium)
        Text("The existing TaraSec gateway, unit, threat and cybersecurity demonstration remains available in the advanced console.")

        if (role != AppRole.HOTSPOT_USER) {
            Text("Owner/admin mode is remembered on this device. Remote management still requires the installation's manager authentication.")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = openConsole
        ) { Text("Security / hotspot owner console") }

        if (role != AppRole.HOTSPOT_USER) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    AppRoleStore.save(activity, AppRole.HOTSPOT_USER)
                    role = AppRole.HOTSPOT_USER
                }
            ) { Text("Switch back to Hotspot User") }
        }

        Text(
            "Hotspot User is the default role. Gateway capabilities will determine which owner controls are relevant once capability detection is connected.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun roleLabel(role: AppRole): String = when (role) {
    AppRole.HOTSPOT_USER -> "Hotspot User"
    AppRole.HOTSPOT_OWNER -> "Hotspot Owner"
    AppRole.TARASEC_ADMIN -> "TaraSec Admin"
}
