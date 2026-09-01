package org.tarasec.app

import android.content.Context
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
fun SubscriberAccountPanel(context: Context) {
    var account by remember { mutableStateOf<SubscriberAccount?>(null) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
                    status = "Global TaraSec account connected."
                    loading = false
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    if (SubscriberAccountClient.storedToken(context) != null) {
                        SubscriberAccountClient.clearToken(context)
                    }
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
                    status = "Signed in to global TaraSec account."
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

    LaunchedEffect(Unit) {
        if (SubscriberAccountClient.storedToken(context) != null) refresh()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("My TaraSec account", style = MaterialTheme.typography.titleMedium)

        if (account == null) {
            Text(
                "Use your email address or phone number for the global TaraSec account. Local hotspot usernames remain separate.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = identifier,
                onValueChange = { identifier = it },
                singleLine = true,
                label = { Text("Email or phone") }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Password") }
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && identifier.isNotBlank() && password.isNotBlank(),
                onClick = { login() }
            ) { Text(if (loading) "Signing in..." else "Sign in to TaraSec") }
        } else {
            Text(account!!.email ?: account!!.phone ?: "TaraSec subscriber")
            Text("Balance", style = MaterialTheme.typography.labelLarge)
            Text("${account!!.balanceCredits} credits", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Credits are global. The amount of data they buy depends on the price of the hotspot you use.",
                style = MaterialTheme.typography.bodySmall
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !loading, onClick = { refresh() }) {
                    Text("Refresh")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        SubscriberAccountClient.clearToken(context)
                        account = null
                        status = "Signed out."
                    }
                ) { Text("Sign out") }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = account!!.paymentEnabled,
                onClick = { }
            ) { Text(if (account!!.paymentEnabled) "Add credits / Pay" else "Payments coming next") }

            HorizontalDivider()
            Text("Recent hotspot usage", style = MaterialTheme.typography.titleMedium)
            if (account!!.usages.isEmpty()) {
                Text("No TaraSec hotspot usage recorded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                account!!.usages.forEach { usage ->
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
