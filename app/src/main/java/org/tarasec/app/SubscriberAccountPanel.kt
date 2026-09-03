package org.tarasec.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        status = "Loading TaraSec account..."
        Thread {
            try {
                val loaded = SubscriberAccountClient.account(context)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    status = "Signed in to TaraSec, but not yet this hotspot."
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    if (SubscriberAccountClient.storedToken(context) != null) SubscriberAccountClient.clearToken(context)
                    account = null
                    status = e.message ?: "Unable to load TaraSec account"
                    loading = false
                }
            }
        }.start()
    }

    fun login() {
        if (loading || identifier.isBlank() || password.isBlank()) return
        loading = true
        status = "Signing in..."
        Thread {
            try {
                val loaded = SubscriberAccountClient.login(context, identifier, password)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    password = ""
                    status = "Signed in to TaraSec, but not yet this hotspot."
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    account = null
                    status = e.message ?: "TaraSec sign-in failed"
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
        Thread {
            try {
                val result = SubscriberAccountClient.activateCurrentHotspot(context)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = result.account
                    status = if (result.internetAvailable) {
                        "This TaraSec account is activated on the current hotspot. Internet access confirmed."
                    } else {
                        "This TaraSec account is activated on the current hotspot, but no Internet access was detected."
                    }
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    status = e.message ?: "Unable to activate the current hotspot"
                    loading = false
                }
            }
        }.start()
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
        Thread {
            try {
                val loaded = SubscriberAccountClient.exchangeIdentityCode(context, code)
                (context as? android.app.Activity)?.runOnUiThread {
                    account = loaded
                    status = "Signed in to TaraSec, but not yet this hotspot."
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    account = null
                    status = e.message ?: "TaraSec identity sign-in failed"
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
            Text("${a.balanceCredits} credits", style = MaterialTheme.typography.headlineMedium)
            Text("Credits are global. The amount of data they buy depends on the price of the hotspot you use.", style = MaterialTheme.typography.bodySmall)

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = { activateHotspot() }
            ) {
                Text(if (loading) "Activating hotspot..." else "Use this account on current hotspot")
            }

            if (a.creditFacility.status == "active") {
                HorizontalDivider()
                Text("TaraSec credit", style = MaterialTheme.typography.titleMedium)
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !loading, onClick = { refresh() }) { Text("Refresh") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    SubscriberAccountClient.clearToken(context)
                    account = null
                    status = "Signed out."
                }) { Text("Sign out") }
            }

            Button(modifier = Modifier.fillMaxWidth(), enabled = a.paymentEnabled, onClick = { }) {
                Text(if (a.paymentEnabled) "Add credits / Pay" else "Payments coming next")
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

        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
