package org.tarasec.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val UNIT_IDENTITY_CODE_EXTRA = "org.tarasec.app.UNIT_IDENTITY_CODE"

@Composable
fun UnitAccountPanel(
    context: Context,
    version: Int,
    gateway: String,
    gatewayExample: String,
    gatewayHint: String,
    onGatewayChanged: (String) -> Unit,
    identityCode: String?,
    identityCodeConsumed: () -> Unit,
    onAccountChanged: () -> Unit,
    externalBusy: Boolean,
    onWorkingChanged: (Boolean) -> Unit
) {
    var services by remember(version) { mutableStateOf(ServiceDiscovery.selected(context)) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val signedIn = remember(version) { SubscriberAccountClient.storedToken(context) != null && MyUnitsClient.accountId(context) != null }

    fun accountTask(work: () -> String) {
        if (working || externalBusy) return
        scope.launch {
            working = true
            onWorkingChanged(true)
            try {
                message = withContext(Dispatchers.IO) { work() }
                services = ServiceDiscovery.selected(context)
                onAccountChanged()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = e.message ?: "Account service request failed."
            } finally { working = false; onWorkingChanged(false) }
        }
    }

    LaunchedEffect(identityCode, working, externalBusy) {
        if (working || externalBusy) return@LaunchedEffect
        val code = identityCode?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        identityCodeConsumed()
        accountTask {
            SubscriberAccountClient.exchangeIdentityCode(context, code)
            "Signed in for linked-unit access."
        }
    }

    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Link nodes to your account", style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(value=gateway, onValueChange=onGatewayChanged,
            label={Text("Gateway IP address")},
            placeholder={if(gatewayExample.isNotBlank()) Text(gatewayExample)},
            supportingText={Text(if(gatewayExample.isNotBlank()) "For example: $gatewayExample (reported by the DB server)" else gatewayHint)},
            enabled=!working && !externalBusy, singleLine=true, modifier=Modifier.fillMaxWidth())
        OutlinedButton(enabled=!working && !externalBusy && gateway.isNotBlank(), onClick={
            val input = gateway
            accountTask {
                val found = ServiceDiscovery.discover(input)
                ServiceDiscovery.select(context, found)
                "Account service: ${java.net.URI(found.identity).host}"
            }
        }) { Text(if(working) "Working…" else "Find account service") }
        Text("Account service: ${java.net.URI(services.identity).host}", style=MaterialTheme.typography.bodySmall)
        Text("Your gateway's account service is used when configured; otherwise tarasec.org is used. Only select gateways you trust.", style=MaterialTheme.typography.bodySmall)
        if(signedIn) {
            Text("Signed in. Use the same Google account when linking each node.")
            TextButton(enabled=!working && !externalBusy, onClick={
                SubscriberAccountClient.clearToken(context)
                message="Signed out."
                onAccountChanged()
            }) { Text("Sign out") }
        } else {
            Button(enabled=!working && !externalBusy, onClick={
                val url = SubscriberAccountClient.identityLoginUrl(context,"google",TaraMenuDestination.MY_UNITS.name)
                context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
            }) { Text("Continue with Google") }
        }
        if(message.isNotBlank()) Text(message)
    }
}
