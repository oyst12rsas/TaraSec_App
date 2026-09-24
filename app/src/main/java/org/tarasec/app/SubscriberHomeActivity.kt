package org.tarasec.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class SubscriberHomeActivity : ComponentActivity() {
    private var identityCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureIdentityCode(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SubscriberHome(
                        initialDestination = intent.getStringExtra(CONSOLE_DESTINATION_EXTRA),
                        identityCode = identityCode,
                        identityCodeConsumed = { identityCode = null },
                        openConsole = { destination ->
                            AppRoleStore.save(this, AppRole.HOTSPOT_OWNER)
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureIdentityCode(intent)
    }

    private fun captureIdentityCode(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "tarasec" && uri.host == "identity") {
            identityCode = uri.getQueryParameter("code")
        }
    }
}

private const val DIRECTORY_PREFS = "tarasec_directory_state"
private const val PREF_DIRECTORY_REACHED = "directory_reached"
private const val PREF_DIRECTORY_REACHED_WITH_VPN = "directory_reached_with_vpn"

@Suppress("DEPRECATION") // Required to detect a VPN that is not the default network.
private fun vpnIsActive(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return cm.allNetworks.any { network ->
        cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

private fun roleLabel(role: AppRole): String = when (role) {
    AppRole.HOTSPOT_USER -> "Hotspot user"
    AppRole.HOTSPOT_OWNER -> "Hotspot owner"
    AppRole.TARASEC_ADMIN -> "TaraSec admin"
}

@androidx.compose.runtime.Composable
private fun SubscriberHome(
    initialDestination: String?,
    identityCode: String?,
    identityCodeConsumed: () -> Unit,
    openConsole: (TaraMenuDestination) -> Unit
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    var role by remember { mutableStateOf(AppRoleStore.load(activity)) }
    var hotspots by remember { mutableStateOf<List<DirectoryHotspot>>(emptyList()) }
    var directoryStatus by remember { mutableStateOf("Published hotspot directory not loaded.") }
    var connectedStatus by remember { mutableStateOf("Not checked") }
    var loading by remember { mutableStateOf(false) }
    var detectingConnected by remember { mutableStateOf(false) }
    var nearbyHotspots by remember { mutableStateOf<List<NearbyTaraSecHotspot>>(emptyList()) }
    var nearbyStatus by remember { mutableStateOf("Nearby TaraSec alternatives not checked.") }
    var scanningNearby by remember { mutableStateOf(false) }
    var connectedInternetAvailable by remember { mutableStateOf(false) }
    var nearbyPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        nearbyPermissionGranted = granted
        nearbyStatus = if (granted) {
            "Location permission granted. Scanning nearby TaraSec hotspots..."
        } else {
            "Location permission is required by Android to see nearby Wi-Fi names and signal levels."
        }
    }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var accountOffset by remember { mutableStateOf(0) }

    LaunchedEffect(initialDestination, accountOffset) {
        if (initialDestination == TaraMenuDestination.MY_ACCESS.name && accountOffset > 0) {
            scrollState.animateScrollTo(accountOffset)
        }
    }

    fun refreshDirectory() {
        if (loading) return
        loading = true
        directoryStatus = "Loading published TaraSec hotspots..."
        Thread {
            val prefs = activity.getSharedPreferences(DIRECTORY_PREFS, Context.MODE_PRIVATE)
            try {
                val result = HotspotDirectoryClient.list()
                val vpnNow = vpnIsActive(activity)
                prefs.edit()
                    .putBoolean(PREF_DIRECTORY_REACHED, true)
                    .putBoolean(PREF_DIRECTORY_REACHED_WITH_VPN, vpnNow)
                    .apply()

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
                val reachedBefore = prefs.getBoolean(PREF_DIRECTORY_REACHED, false)
                val previouslyNeededVpn = prefs.getBoolean(PREF_DIRECTORY_REACHED_WITH_VPN, false)
                val vpnNow = vpnIsActive(activity)

                activity.runOnUiThread {
                    directoryStatus = when {
                        reachedBefore && previouslyNeededVpn && !vpnNow ->
                            "The TaraSec hotspot directory worked before while a VPN was active, but no VPN is active now. TaraSec VPN/WireGuard may be turned off."
                        !reachedBefore ->
                            "Published hotspot directory is not reachable yet. This is a fresh installation, so TaraSec will not assume a VPN is required. Use Find nearby Wi-Fi to discover local hotspots while the public directory is unavailable."
                        else ->
                            e.message ?: "Published hotspot directory unavailable"
                    }
                    loading = false
                }
            }
        }.start()
    }

    fun scanNearbyTaraSec() {
        if (scanningNearby) return
        if (!nearbyPermissionGranted) {
            nearbyPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        scanningNearby = true
        nearbyStatus = "Scanning nearby TaraSec Wi-Fi..."

        Thread {
            try {
                val localResult = HotspotDirectoryClient.nearby(
                    activity,
                    hotspots,
                    includeConnectedPricing = false
                )
                val internetAvailable = localResult.any { it.connected } &&
                    SubscriberAccountClient.checkWifiInternet(activity)

                activity.runOnUiThread {
                    nearbyHotspots = localResult
                    connectedInternetAvailable = internetAvailable
                    nearbyStatus = when {
                        localResult.isEmpty() ->
                            "No TaraSec Wi-Fi signal is visible. Android may require Location to be turned on before Wi-Fi scan results are available."
                        localResult.size == 1 -> "1 TaraSec hotspot is visible."
                        else -> "${localResult.size} TaraSec hotspots are visible."
                    }
                    scanningNearby = false
                }

                val directory = runCatching { HotspotDirectoryClient.list() }.getOrDefault(emptyList())
                val enriched = runCatching {
                    HotspotDirectoryClient.nearby(
                        activity,
                        directory,
                        includeConnectedPricing = true
                    )
                }.getOrDefault(localResult)

                activity.runOnUiThread {
                    if (directory.isNotEmpty()) hotspots = directory
                    nearbyHotspots = enriched
                }
            } catch (e: SecurityException) {
                activity.runOnUiThread {
                    nearbyStatus = "Android blocked nearby Wi-Fi results. Allow location access and turn on Location, then try again."
                    scanningNearby = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    nearbyStatus = e.message ?: "Unable to check nearby TaraSec hotspots"
                    scanningNearby = false
                }
            }
        }.start()
    }

    LaunchedEffect(nearbyPermissionGranted) {
        if (nearbyPermissionGranted) {
            scanNearbyTaraSec()
        } else {
            nearbyPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    DisposableEffect(activity, nearbyPermissionGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && nearbyPermissionGranted) {
                scanNearbyTaraSec()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    val connectedSsid = nearbyHotspots.firstOrNull { it.connected }?.ssid
    LaunchedEffect(connectedInternetAvailable, connectedSsid) {
        if (!connectedInternetAvailable || connectedSsid == null || SubscriberAccountClient.storedToken(activity) == null) {
            return@LaunchedEffect
        }
        while (true) {
            val snapshot = nearbyHotspots
            val refreshed = withContext(Dispatchers.IO) {
                HotspotDirectoryClient.refreshConnectedPricingAndUsage(activity, snapshot)
            }
            nearbyHotspots = refreshed
            delay(10_000)
        }
    }

    fun openNearbyWifi() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        activity.startActivity(intent)
    }

    fun connectTo(candidate: NearbyTaraSecHotspot) {
        if (candidate.connected) return
        nearbyStatus = TaraSecWifiConnector.connect(activity, candidate.ssid)
    }

    fun logInToConnectedHotspot(candidate: NearbyTaraSecHotspot) {
        if (!candidate.connected || connectedInternetAvailable) return
        if (SubscriberAccountClient.storedToken(activity) == null) {
            nearbyStatus = "Sign in to TaraSec below, then authorize ${candidate.ssid}."
            coroutineScope.launch { scrollState.animateScrollTo(accountOffset) }
            return
        }

        nearbyStatus = "Authorizing Internet access on ${candidate.ssid}..."
        Thread {
            try {
                val activation = SubscriberAccountClient.activateCurrentHotspot(activity)
                activity.runOnUiThread {
                    connectedInternetAvailable = activation.internetAvailable
                    nearbyStatus = if (activation.internetAvailable) {
                        "Internet access through ${candidate.ssid} is authorized."
                    } else {
                        "Signed in to TaraSec, but ${candidate.ssid} still has no Internet access."
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    nearbyStatus = e.message ?: "Unable to authorize this TaraSec hotspot"
                }
            }
        }.start()
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

            val endpoints = listOf(
                "/hotspot/tarasec_identity.php",
                "/script/appNode.php"
            )
            var lastError: String? = null
            var identified: JSONObject? = null

            for (path in endpoints) {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
                    connection.connectTimeout = 2500
                    connection.readTimeout = 3500
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val roleValue = json?.optString("role", "").orEmpty()
                    val taraSec = code in 200..299 && json != null && json.optBoolean("ok", false) &&
                        (roleValue.startsWith("tarasec-") || json.optString("service") == "tarasec")
                    if (taraSec) {
                        identified = json
                        break
                    }
                    lastError = "HTTP $code from $path"
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    connection?.disconnect()
                }
            }

            activity.runOnUiThread {
                identified?.let { gateway ->
                    val name = gateway.optString("name", "").takeIf { it.isNotBlank() }
                    val detectedRole = gateway.optString("role", "")
                    val kind = if (detectedRole == "tarasec-hotspot") "TaraSec hotspot" else "TaraSec gateway"
                    connectedStatus = "Connected to $kind${name?.let { ": $it" } ?: ""} · $base"
                } ?: run {
                    connectedStatus = "Wi-Fi is connected through $base, but that gateway did not identify itself as TaraSec${lastError?.let { " ($it)" } ?: ""}."
                }
                detectingConnected = false
            }
        }.start()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
            TaraHamburgerMenu { destination ->
                when (destination) {
                    TaraMenuDestination.MY_ACCESS -> {
                        coroutineScope.launch { scrollState.animateScrollTo(accountOffset) }
                    }
                    TaraMenuDestination.FIND_INTERNET -> openNearbyWifi()
                    TaraMenuDestination.STATUS_UNITS,
                    TaraMenuDestination.SECURITY_DEMO,
                    TaraMenuDestination.AI_ASSISTANCE,
                    TaraMenuDestination.RESEARCH,
                    TaraMenuDestination.SETUP_HOTSPOTS -> openConsole(destination)
                    TaraMenuDestination.CONTRIBUTE -> Unit
                }
            }
        }

        Text("Find secure Internet access", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tap a red TaraSec hotspot to open Android's Wi-Fi selector, then tap that SSID there to switch. Tap a yellow connected hotspot to log in and authorize Internet access.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text("Current role: ${roleLabel(role)}", style = MaterialTheme.typography.bodySmall)

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !scanningNearby,
            onClick = { scanNearbyTaraSec() }
        ) { Text(if (scanningNearby) "Scanning nearby hotspots..." else "Refresh nearby hotspots") }

        Text(nearbyStatus, style = MaterialTheme.typography.bodySmall)

        nearbyHotspots.forEach { candidate ->
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !candidate.connected || !connectedInternetAvailable) {
                        if (candidate.connected) {
                            logInToConnectedHotspot(candidate)
                        } else {
                            connectTo(candidate)
                        }
                    }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val statusDot = when {
                        candidate.connected && connectedInternetAvailable -> "🟢"
                        candidate.connected -> "🟡"
                        else -> "🔴"
                    }
                    Text("$statusDot ${candidate.ssid}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            candidate.connected && connectedInternetAvailable ->
                                "Connected · ${candidate.signalDbm} dBm"
                            candidate.connected ->
                                "Connected · ${candidate.signalDbm} dBm · Tap to log in"
                            else ->
                                "${candidate.signalLabel} signal · ${candidate.signalDbm} dBm · Tap to choose in Android Wi-Fi"
                        }
                    )
                    when {
                        candidate.priceLabel != null ->
                            Text(candidate.priceLabel, style = MaterialTheme.typography.bodySmall)
                        candidate.priceCreditsPerMiB != null ->
                            Text("${candidate.priceCreditsPerMiB} credits/MiB", style = MaterialTheme.typography.bodySmall)
                        else ->
                            Text("Price is not published. Verify the price before connecting.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        when {
                            candidate.connected && connectedInternetAvailable -> "Connected and Internet access works through this Wi-Fi."
                            candidate.connected -> "Connected to TaraSec Wi-Fi, but Internet access is not authorized yet. Tap to log in."
                            candidate.verifiedDirectoryEntry -> "Matches a published TaraSec directory entry."
                            else -> "Nearby Wi-Fi name only; TaraSec identity must be verified after connecting."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { openNearbyWifi() }
        ) { Text("Open Wi-Fi settings") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !detectingConnected,
            onClick = { detectConnectedTaraSec() }
        ) { Text(if (detectingConnected) "Checking connected Wi-Fi..." else "Detect connected TaraSec") }

        Text(connectedStatus, style = MaterialTheme.typography.bodySmall)

        WireGuardQrHelp()

        HorizontalDivider()
        Text("Published TaraSec hotspots", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional global directory. It is not used for finding Wi-Fi networks that are physically nearby.",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = { refreshDirectory() }
        ) { Text(if (loading) "Loading directory..." else "Browse published TaraSec hotspots") }

        Text(directoryStatus, style = MaterialTheme.typography.bodySmall)

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
        Box(
            modifier = Modifier.onGloballyPositioned {
                accountOffset = it.positionInParent().y.roundToInt()
            }
        ) {
            SubscriberAccountPanel(
                context = activity,
                identityCode = identityCode,
                identityCodeConsumed = identityCodeConsumed
            )
        }

        HorizontalDivider()
        Text("TaraSec Security", style = MaterialTheme.typography.titleMedium)
        Text("The existing TaraSec gateway, unit, threat and cybersecurity demonstration remains available in the advanced console.")

        if (role != AppRole.HOTSPOT_USER) {
            Text("Owner/admin mode is remembered on this device. Remote management still requires the installation's manager authentication.")
        }
    }
}

@Composable
internal fun WireGuardQrHelp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Text("No TaraSec hotspot nearby?", style = MaterialTheme.typography.titleMedium)
    Text(
        "If you cannot otherwise access the TaraSec NetBird network, request a WireGuard QR code using your normal Internet connection.",
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        "Open the request page, enter your name, email and tester password, and confirm the assignment and email notice. One QR code is assigned per email. Ask the TaraSec team for a tester password if you do not have one.",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Install the WireGuard app. Display your assigned QR code on another screen, then use WireGuard on this phone to scan it and enable the tunnel. Return to TaraSec, choose a demo endpoint and refresh its status.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://tarasec.org/app/wireguard.php"))
                )
            } catch (_: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(
                    context,
                    "Open https://tarasec.org/app/wireguard.php in a web browser.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    ) { Text("Get a WireGuard QR code") }
}
