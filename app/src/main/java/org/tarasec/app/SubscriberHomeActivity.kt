package org.tarasec.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    var directoryStatus by remember { mutableStateOf("Loading published TaraSec hotspots...") }
    var connectedStatus by remember { mutableStateOf("Not checked") }
    var loading by remember { mutableStateOf(false) }
    var detectingConnected by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    fun refreshDirectory() {
        if (loading) return
        loading = true
        directoryStatus = "Loading published TaraSec hotspots..."
        Thread {
            try {
                val result = HotspotDirectoryClient.list()
                activity.runOnUiThread {
                    hotspots = result
                    directoryStatus = when {
                        result.isEmpty() -> "No participating hotspots are published yet."
                        result.size == 1 -> "1 published TaraSec hotspot found."
                        else -> "${result.size} published TaraSec hotspots found."
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

    fun openNearbyWifi() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        activity.startActivity(intent)
    }

    fun detectConnectedTaraSec() {
        if (detectingConnected) return
        detectingConnected = true
        connectedStatus = "Checking the currently connected Wi-Fi gateway..."
        Thread {
            val base = LocalGateway.baseUrl(activity)
            if (base == null) {
                activity.runOnUiThread {
                    connectedStatus = "No active Wi-Fi gateway detected. Connect to a Wi-Fi network first."
                    detectingConnected = false
                }
                return@Thread
            }

            var connection: HttpURLConnection? = null
            try {
                val endpoint = base.trimEnd('/') + "/script/appNode.php"
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 2500
                connection.readTimeout = 3500
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                val json = runCatching { JSONObject(body) }.getOrNull()
                val taraSec = code in 200..299 && json != null &&
                    (json.optBoolean("ok", false) || json.has("name"))
                val name = json?.optString("name", "")?.takeIf { it.isNotBlank() }

                activity.runOnUiThread {
                    connectedStatus = if (taraSec) {
                        "Connected to TaraSec${name?.let { " gateway: $it" } ?: ""} · $base"
                    } else {
                        "Wi-Fi is connected through $base, but that gateway did not identify itself as TaraSec."
                    }
                    detectingConnected = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    connectedStatus = "Wi-Fi gateway found at $base, but TaraSec could not be confirmed: ${e.message ?: e.javaClass.simpleName}"
                    detectingConnected = false
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    LaunchedEffect(Unit) { refreshDirectory() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
            Box {
                TextButton(onClick = { menuExpanded = true }) {
                    Text("☰", style = MaterialTheme.typography.headlineSmall)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Nearby Wi-Fi") },
                        onClick = {
                            menuExpanded = false
                            openNearbyWifi()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Detect connected TaraSec") },
                        onClick = {
                            menuExpanded = false
                            detectConnectedTaraSec()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Browse TaraSec hotspots") },
                        onClick = {
                            menuExpanded = false
                            refreshDirectory()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("My access") },
                        onClick = {
                            menuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Status / Units") },
                        onClick = {
                            menuExpanded = false
                            openConsole()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Security Demo") },
                        onClick = {
                            menuExpanded = false
                            openConsole()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("AI / Assistance") },
                        onClick = {
                            menuExpanded = false
                            openConsole()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Setup / My hotspots") },
                        onClick = {
                            menuExpanded = false
                            openConsole()
                        }
                    )
                }
            }
        }

        Text("Find secure Internet access", style = MaterialTheme.typography.titleLarge)
        Text(
            "Use Nearby Wi-Fi to discover and connect to networks around you. Once connected, TaraSec can verify whether the current gateway is a TaraSec hotspot. The published hotspot directory is a separate Internet service.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text("Current role: ${roleLabel(role)}", style = MaterialTheme.typography.bodySmall)

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { openNearbyWifi() }
        ) { Text("Find nearby Wi-Fi") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !detectingConnected,
            onClick = { detectConnectedTaraSec() }
        ) { Text(if (detectingConnected) "Checking connected Wi-Fi..." else "Detect connected TaraSec") }

        Text(connectedStatus, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Published TaraSec hotspots", style = MaterialTheme.typography.titleMedium)
        Text(
            "This list comes from the TaraSec hotspot directory. It does not mean the listed Wi-Fi networks are currently within radio range.",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = { refreshDirectory() }
        ) { Text(if (loading) "Loading directory..." else "Refresh TaraSec hotspot directory") }

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
