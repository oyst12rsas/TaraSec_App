package org.tarasec.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    onWorkingChanged: (Boolean) -> Unit,
    onAddLinkedNodes: () -> Unit
) {
    var services by remember(version) { mutableStateOf(ServiceDiscovery.selected(context)) }
    var working by remember { mutableStateOf(false) }
    var checkedGateway by rememberSaveable { mutableStateOf("") }
    var checkedProvider by rememberSaveable { mutableStateOf("") }
    var nodeName by rememberSaveable { mutableStateOf("") }
    var approvalUrl by rememberSaveable(MyUnitsClient.accountId(context),services.identity,gateway) { mutableStateOf("") }
    var confirmationCode by rememberSaveable(MyUnitsClient.accountId(context),services.identity,gateway) { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val signedIn = remember(version) { SubscriberAccountClient.storedToken(context) != null && MyUnitsClient.accountId(context) != null }

    fun accountTask(onSuccess: () -> Unit = {}, work: () -> String) {
        if (working || externalBusy) return
        scope.launch {
            working = true
            onWorkingChanged(true)
            try {
                message = withContext(Dispatchers.IO) { work() }
                services = ServiceDiscovery.selected(context)
                onSuccess()
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
        Text("Link a node", style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(value=gateway, onValueChange=onGatewayChanged,
            label={Text("Node IP address")},
            placeholder={if(gatewayExample.isNotBlank()) Text(gatewayExample)},
            supportingText={Text(if(gatewayExample.isNotBlank()) "Detected gateway: $gatewayExample" else gatewayHint)},
            enabled=!working && !externalBusy, singleLine=true, modifier=Modifier.fillMaxWidth())
        when {
            checkedGateway != gateway || checkedProvider != services.identity || gateway.isBlank() -> {
                Button(enabled=!working && !externalBusy && gateway.isNotBlank(), onClick={
                    val input=gateway
                    accountTask(onSuccess={checkedGateway=input}) {
                        val meta=MyUnitsClient.gatewayMetadata(context,input)
                        val found=ServiceDiscovery.parse(meta.getJSONObject("account_services"))
                        nodeName=meta.getString("name")
                        checkedProvider=found.identity
                        ServiceDiscovery.select(context,found)
                        ""
                    }
                }) { Text(if(working) "Checking gateway…" else "Continue") }
            }
            !signedIn -> {
                Text("Sign in with the Google account you want to link to this node. Service: ${java.net.URI(services.identity).host}.")
                Button(enabled=!working && !externalBusy, onClick={
                    val url=SubscriberAccountClient.identityLoginUrl(context,"google",TaraMenuDestination.MY_UNITS.name)
                    context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                }) { Text(if(working) "Signing in…" else "Continue with Google") }
            }
            else -> {
                Text("Link this phone app to $nodeName.")
                if(approvalUrl.isBlank()) {
                    Button(enabled=!working && !externalBusy,onClick={
                        val input=gateway
                        val account=MyUnitsClient.accountId(context) ?: return@Button
                        accountTask {
                            val request=MyUnitsClient.requestGateway(context,account,input)
                            approvalUrl=request.getString("approval_url")
                            confirmationCode=request.getString("confirmation_code")
                            "Request created. Approve it using the gateway administrator login."
                        }
                    }) { Text(if(working) "Requesting…" else "Request app link") }
                } else {
                    Text("Confirmation code: $confirmationCode",style=MaterialTheme.typography.titleMedium)
                    Text("Open the approval page on this phone or any computer with a browser. Sign in as the gateway administrator and approve only if the code matches. The request expires after ten minutes.")
                    Button(enabled=!working && !externalBusy,onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(approvalUrl)))}) { Text("Open approval page") }
                    OutlinedButton(enabled=!working && !externalBusy,onClick={
                        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TaraSec app approval URL",approvalUrl))
                        message="Approval link copied."
                    }) { Text("Copy approval link") }
                    Button(enabled=!working && !externalBusy,onClick=onAddLinkedNodes) { Text(if(externalBusy) "Checking…" else "Finish linking") }
                    TextButton(enabled=!working && !externalBusy,onClick={approvalUrl="";confirmationCode="";message=""}) { Text("Start a new request") }
                }

            }
        }
        if(signedIn) {
            TextButton(enabled=!working && !externalBusy,onClick={advanced=!advanced}) { Text(if(advanced) "Hide account options" else "Account options") }
            if(advanced) TextButton(enabled=!working && !externalBusy, onClick={
                SubscriberAccountClient.clearToken(context)
                message="Signed out."
                onAccountChanged()
            }) { Text("Sign out") }
        }
        if(message.isNotBlank()) Text(message)
    }
}
