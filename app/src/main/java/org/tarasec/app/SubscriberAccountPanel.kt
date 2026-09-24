package org.tarasec.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.math.RoundingMode

private fun displayCreditBalance(value: String): String =
    value.toBigDecimalOrNull()
        ?.setScale(2, RoundingMode.HALF_UP)
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: value

private const val PRIVACY_POLICY_URL = "https://tarasec.org/app/privacy.html"
private const val ACCOUNT_DELETION_URL = "https://tarasec.org/app/delete-account.html"

private enum class AccountAccessLight(val color: Color) {
    RED(Color(0xFFC62828)),
    YELLOW(Color(0xFFF9A825)),
    GREEN(Color(0xFF2E7D32))
}

@Composable
fun SubscriberAccountPanel(
    context: Context,
    identityCode: String? = null,
    identityCodeConsumed: () -> Unit = {}
) {
    var account by remember { mutableStateOf<SubscriberAccount?>(null) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creditAmount by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not signed in to a global TaraSec account.") }
    var accessLight by remember { mutableStateOf(AccountAccessLight.RED) }
    var currentHotspotActivated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var wifiScanPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        wifiScanPermissionGranted = granted
    }

    fun refresh() {
        if (loading) return
        loading = true
        status = "Loading TaraSec account..."
        accessLight = AccountAccessLight.YELLOW
        Thread {
            try {
                val loaded = SubscriberAccountClient.account(context)
                val taraSecConnected = SubscriberAccountClient.currentWifiIsTaraSecHotspot(context)
                val internetAvailable = taraSecConnected &&
                    SubscriberAccountClient.checkWifiInternet(context)
                val taraSecNearby = !taraSecConnected && wifiScanPermissionGranted &&
                    SubscriberAccountClient.nearbyTaraSecWifiIsVisible(context)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    when {
                        internetAvailable -> {
                            currentHotspotActivated = true
                            status = "Signed in to TaraSec. Internet access confirmed on the current TaraSec hotspot."
                            accessLight = AccountAccessLight.GREEN
                        }
                        taraSecConnected -> {
                            currentHotspotActivated = false
                            status = "Signed in to TaraSec, but not yet this hotspot."
                            accessLight = AccountAccessLight.YELLOW
                        }
                        taraSecNearby -> {
                            currentHotspotActivated = false
                            status = "A TaraSec hotspot is nearby, but this phone is connected to another Wi-Fi network."
                            accessLight = AccountAccessLight.YELLOW
                        }
                        else -> {
                            currentHotspotActivated = false
                            status = "Signed in to TaraSec. The current Wi-Fi is not a TaraSec hotspot."
                            accessLight = AccountAccessLight.YELLOW
                        }
                    }
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    if (SubscriberAccountClient.storedToken(context) != null) SubscriberAccountClient.clearToken(context)
                    account = null
                    status = e.message ?: "Unable to load TaraSec account"
                    accessLight = AccountAccessLight.RED
                    loading = false
                }
            }
        }.start()
    }

    fun login() {
        if (loading || identifier.isBlank() || password.isBlank()) return
        loading = true
        status = "Signing in..."
        accessLight = AccountAccessLight.YELLOW
        Thread {
            try {
                val loaded = SubscriberAccountClient.login(context, identifier, password)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    password = ""
                    status = "Signed in to TaraSec, but not yet this hotspot."
                    accessLight = AccountAccessLight.YELLOW
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    account = null
                    status = e.message ?: "TaraSec sign-in failed"
                    accessLight = AccountAccessLight.RED
                    loading = false
                }
            }
        }.start()
    }

    fun applyForCredit() {
        if (loading || creditAmount.isBlank()) return
        loading = true
        status = "Submitting TaraSec test-credit application..."
        Thread {
            try {
                val loaded = SubscriberAccountClient.applyForCredit(context, creditAmount)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    creditAmount = ""
                    status = when (loaded.creditFacility.status) {
                        "active" -> "TaraSec test credit approved and ready to use."
                        "pending" -> "TaraSec test-credit application submitted for approval."
                        else -> "TaraSec test-credit application submitted."
                    }
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    status = e.message ?: "Unable to apply for TaraSec credit"
                    loading = false
                }
            }
        }.start()
    }

    fun drawCredit() {
        if (loading || creditAmount.isBlank()) return
        loading = true
        status = "Adding approved TaraSec credit..."
        Thread {
            try {
                val loaded = SubscriberAccountClient.drawCredit(context, creditAmount)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    creditAmount = ""
                    status = "TaraSec credit added to your spendable balance."
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    status = e.message ?: "Unable to use TaraSec credit"
                    loading = false
                }
            }
        }.start()
    }

    fun activateHotspot() {
        if (loading) return
        loading = true
        status = "Activating this account on the current hotspot..."
        accessLight = AccountAccessLight.YELLOW
        Thread {
            try {
                val result = SubscriberAccountClient.activateCurrentHotspot(context)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = result.account
                    currentHotspotActivated = true
                    status = if (result.internetAvailable) {
                        accessLight = AccountAccessLight.GREEN
                        "This TaraSec account is activated on the current hotspot. Internet access confirmed."
                    } else {
                        accessLight = AccountAccessLight.RED
                        "This TaraSec account is activated on the current hotspot, but no Internet access was detected."
                    }
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    status = e.message ?: "Unable to activate the current hotspot"
                    accessLight = AccountAccessLight.RED
                    loading = false
                }
            }
        }.start()
    }

    fun checkInternetAgain() {
        if (loading || !currentHotspotActivated) return
        loading = true
        status = "Checking Internet access through the current Wi-Fi..."
        accessLight = AccountAccessLight.YELLOW
        Thread {
            val taraSecConnected = SubscriberAccountClient.currentWifiIsTaraSecHotspot(context)
            val available = taraSecConnected && SubscriberAccountClient.checkWifiInternet(context)
            (context as? android.app.Activity)?.runOnUiThread {
                status = when {
                    available -> {
                        accessLight = AccountAccessLight.GREEN
                        "This TaraSec account is activated on the current hotspot. Internet access confirmed."
                    }
                    !taraSecConnected -> {
                        currentHotspotActivated = false
                        accessLight = AccountAccessLight.YELLOW
                        "The current Wi-Fi is not a TaraSec hotspot."
                    }
                    else -> {
                        accessLight = AccountAccessLight.RED
                        "This TaraSec account is activated on the current hotspot, but no Internet access was detected."
                    }
                }
                loading = false
            }
        }.start()
    }

    LaunchedEffect(account != null) {
        if (account != null && !wifiScanPermissionGranted) {
            wifiPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(wifiScanPermissionGranted) {
        if (wifiScanPermissionGranted && account != null && !loading) refresh()
    }

    LaunchedEffect(Unit) {
        if (identityCode == null && SubscriberAccountClient.storedToken(context) != null) refresh()
    }

    LaunchedEffect(identityCode) {
        val code = identityCode?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (loading) return@LaunchedEffect
        identityCodeConsumed()
        loading = true
        status = "Completing global TaraSec sign-in..."
        accessLight = AccountAccessLight.YELLOW
        Thread {
            try {
                val loaded = SubscriberAccountClient.exchangeIdentityCode(context, code)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    status = "Signed in to TaraSec, but not yet this hotspot."
                    accessLight = AccountAccessLight.YELLOW
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    account = null
                    status = e.message ?: "TaraSec identity sign-in failed"
                    accessLight = AccountAccessLight.RED
                    loading = false
                }
            }
        }.start()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("My TaraSec account", style = MaterialTheme.typography.titleMedium)

        if (account == null) {
            Text("Sign in with a global identity. This grants subscriber access only; node-management approval remains local.", style = MaterialTheme.typography.bodySmall)
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SubscriberAccountClient.identityLoginUrl("google"))))
                }
            ) { Text("Continue with Google") }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SubscriberAccountClient.identityLoginUrl("facebook"))))
                }
            ) { Text("Continue with Facebook") }
            HorizontalDivider()
            Text("Or use an existing TaraSec password", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = identifier, onValueChange = { identifier = it }, singleLine = true, label = { Text("Email or phone") })
            OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = password, onValueChange = { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Password") })
            Button(modifier = Modifier.fillMaxWidth(), enabled = !loading && identifier.isNotBlank() && password.isNotBlank(), onClick = { login() }) {
                Text(if (loading) "Signing in..." else "Sign in to TaraSec")
            }
        } else {
            val a = account!!
            Text(a.email ?: a.phone ?: "TaraSec subscriber")
            Text("Balance", style = MaterialTheme.typography.labelLarge)
            Text("${displayCreditBalance(a.balanceCredits)} credits", style = MaterialTheme.typography.headlineMedium)
            Text("Credits are global. The amount of data they buy depends on the price of the hotspot you use.", style = MaterialTheme.typography.bodySmall)

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = { activateHotspot() }
            ) {
                Text(if (loading) "Activating hotspot..." else "Use this account on current hotspot")
            }

            if (currentHotspotActivated) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    onClick = { checkInternetAgain() }
                ) {
                    Text(if (loading) "Checking Internet..." else "Check Internet again")
                }
            }

            // Keep experimental test-credit controls in debug builds while the
            // release app exposes only the working subscriber account features.
            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                Text("TaraSec credit", style = MaterialTheme.typography.titleMedium)
                when (a.creditFacility.status) {
                    "active" -> {
                        Text("Credit limit: ${a.creditFacility.creditLimitCredits} credits")
                        Text("Outstanding: ${a.creditFacility.debtCredits} credits")
                        Text("Available to borrow: ${a.creditFacility.availableCredit} credits")
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = creditAmount,
                            onValueChange = { creditAmount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            singleLine = true,
                            label = { Text("Credits to add") }
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading && a.creditFacility.drawEnabled && creditAmount.toDoubleOrNull()?.let { it > 0 } == true,
                            onClick = { drawCredit() }
                        ) { Text("Add credits using TaraSec credit") }
                        Text("Borrowed credits increase your outstanding TaraSec debt. The hotspot operator is still paid from the normal serving-hotspot accounting.", style = MaterialTheme.typography.bodySmall)
                    }
                    "pending" -> {
                        Text("Your TaraSec test-credit application is awaiting approval.")
                        Text("No real money is involved in the test-credit phase. Approved credits are used to test roaming, pricing and hotspot accounting before payments are enabled.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text("Apply for test credit to try TaraSec hotspot access before payment integration is enabled.")
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = creditAmount,
                            onValueChange = { creditAmount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            singleLine = true,
                            label = { Text("Requested test credits") }
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading && creditAmount.toDoubleOrNull()?.let { it > 0 } == true,
                            onClick = { applyForCredit() }
                        ) { Text("Apply for TaraSec test credit") }
                        Text("This is test credit for service testing, not a cash loan. Approval and limits can be replaced by the real payment/credit process later.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !loading, onClick = { refresh() }) { Text("Refresh") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    SubscriberAccountClient.clearToken(context)
                    account = null
                    currentHotspotActivated = false
                    status = "Signed out."
                    accessLight = AccountAccessLight.RED
                }) { Text("Sign out") }
            }

            HorizontalDivider()
            Text("Recent hotspot usage", style = MaterialTheme.typography.titleMedium)
            if (a.usages.isEmpty()) {
                Text("No TaraSec hotspot usage recorded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                a.usages.forEach { usage ->
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val place = usage.countryCode?.let { " · $it" }.orEmpty()
                            Text("${usage.hotspot}$place", style = MaterialTheme.typography.titleSmall)
                            usage.priceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("${usage.mib} MiB · ${usage.chargedCredits} credits")
                            Text("Rate: ${usage.priceCreditsPerMiB} credits/MiB", style = MaterialTheme.typography.bodySmall)
                            if (usage.startedAt.isNotBlank()) Text("Started: ${usage.startedAt}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }
            ) { Text("Privacy") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ACCOUNT_DELETION_URL))) }
            ) { Text("Delete account") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(accessLight.color, CircleShape)
            )
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
