package org.tarasec.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MyUnitsActivity : ComponentActivity() {
    private var identityCode by mutableStateOf<String?>(null)
    private var resumeVersion by mutableStateOf(0)
    override fun onResume() { super.onResume(); resumeVersion++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identityCode = intent.getStringExtra(UNIT_IDENTITY_CODE_EXTRA)
        intent.removeExtra(UNIT_IDENTITY_CODE_EXTRA)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MyUnitsScreen(resumeVersion) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        identityCode = intent.getStringExtra(UNIT_IDENTITY_CODE_EXTRA)
        intent.removeExtra(UNIT_IDENTITY_CODE_EXTRA)
    }

    @Composable
    private fun MyUnitsScreen(version: Int) {
        val installations = remember(version) { InstallationStore.load(this) }
        val provider = remember(version) { ServiceDiscovery.selected(this) }
        val accountId = remember(version) { MyUnitsClient.accountId(this) }
        val signedIn = remember(version) { SubscriberAccountClient.storedToken(this) != null }
        var units by remember(accountId, provider) { mutableStateOf(accountId?.let { MyUnitsClient.load(this,it) } ?: emptyList()) }
        var gateway by rememberSaveable { mutableStateOf("") }
        var gatewayExample by remember { mutableStateOf("") }
        var gatewayDiscoveryMessage by remember { mutableStateOf("Checking your IP with the DB server…") }
        var showAdd by rememberSaveable { mutableStateOf(false) }
        var pairing by remember { mutableStateOf("") }
        var manual by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var accountBusy by remember { mutableStateOf(false) }
        var statuses by remember(accountId, provider) { mutableStateOf<Map<String,JSONObject>>(emptyMap()) }
        var errors by remember(accountId, provider) { mutableStateOf<Map<String,String>>(emptyMap()) }
        var revoke by remember { mutableStateOf<LinkedUnit?>(null) }
        var rename by remember { mutableStateOf<LinkedUnit?>(null) }
        var newName by remember { mutableStateOf("") }

        // A linked node with a management mapping already has its own card.
        val standaloneInstallations = installations.filterNot { installation ->
            units.any { unit ->
                SecureCredentialStore.get(this, provider.key("manager-installation:$accountId:${unit.key}")) == installation.id
            }
        }

        LaunchedEffect(identityCode) {
            if (!identityCode.isNullOrBlank()) { showAdd=true }
        }

        LaunchedEffect(version, signedIn) {
            gatewayExample = ""
            gatewayDiscoveryMessage = "Checking your IP with the DB server…"
            val observed = withContext(Dispatchers.IO) {
                DemoSshClient.observedGateway(HotspotDirectoryClient.DEFAULT_BASE_URL)
            }
            if (observed.recognized && android.util.Patterns.IP_ADDRESS.matcher(observed.address).matches()) {
                gatewayExample = observed.address
                gatewayDiscoveryMessage = "DB server sees your connection through ${observed.address}"
            } else {
                gatewayDiscoveryMessage = if (observed.address.isNotBlank()) {
                    "DB server sees ${observed.address}, but could not identify it as a gateway. Enter your gateway IP address."
                } else {
                    "Could not check your IP with the DB server. Enter your gateway IP address."
                }
            }
        }

        fun runTask(work: () -> Unit) {
            if (busy || accountBusy) return
            busy = true; message = "Working…"
            Thread {
                try { work(); runOnUiThread { message = "Updated"; busy = false } }
                catch (e: Exception) { runOnUiThread { message = e.message ?: "Request failed. Status is unknown."; busy = false } }
            }.start()
        }
        fun refresh(unit: LinkedUnit) {
            statuses = statuses - unit.key; errors = errors - unit.key
            runTask {
                try { val s = MyUnitsClient.status(this,unit); runOnUiThread { statuses = statuses + (unit.key to s) } }
                catch (e: Exception) { runOnUiThread { errors = errors + (unit.key to (e.message ?: "Status unknown")) } }
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("My units",style=MaterialTheme.typography.headlineMedium)
                TextButton(onClick={ finish() }) { Text("Back") }
            }
            Text("Your nodes and installations, with the access granted to your account.")
            Button(enabled=!busy && !accountBusy,onClick={showAdd=true}) { Text("Link a node") }
            if(standaloneInstallations.isNotEmpty()) Text("Managed installations",style=MaterialTheme.typography.titleMedium)
            standaloneInstallations.forEach { installation ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(installation.name,style=MaterialTheme.typography.titleMedium)
                        Text(installation.managementBaseUrl,style=MaterialTheme.typography.bodySmall)
                        Text("Manager permission is checked when you open the installation.",style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!busy && !accountBusy,onClick={
                                InstallationStore.setSelected(this@MyUnitsActivity,installation.id)
                                startActivity(Intent(this@MyUnitsActivity,MainActivity::class.java)
                                    .putExtra(CONSOLE_DESTINATION_EXTRA,TaraMenuDestination.STATUS_UNITS.name))
                            }) { Text("Manage") }
                        }
                    }
                }
            }
            if(showAdd) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            UnitAccountPanel(
                                context=this@MyUnitsActivity,
                                version=version,
                                gateway=gateway,
                                gatewayExample=gatewayExample,
                                gatewayHint=gatewayDiscoveryMessage,
                                onGatewayChanged={gateway=it},
                                identityCode=identityCode,
                                identityCodeConsumed={identityCode=null},
                                onAccountChanged={resumeVersion++},
                                externalBusy=busy,
                                onWorkingChanged={accountBusy=it},
                                onAddLinkedNodes={
                                    val currentAccount=MyUnitsClient.accountId(this@MyUnitsActivity)
                                    val input=gateway
                                    if(currentAccount!=null) runTask {
                                        val result=MyUnitsClient.sync(this@MyUnitsActivity,currentAccount,input)
                                        runOnUiThread { units=result; statuses=emptyMap(); errors=emptyMap(); showAdd=false }
                                    }
                                }
                            )
                            if(signedIn && accountId!=null) {
                                TextButton(enabled=!busy && !accountBusy,onClick={manual=!manual}) { Text(if(manual) "Hide advanced" else "Advanced") }
                                if(manual) {
                                    Text("Manual pairing: paste the pairing JSON from the node. It contains a secret; do not share it in debug reports.")
                                    OutlinedTextField(value=pairing,onValueChange={pairing=it},label={Text("Pairing JSON")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                                    Button(enabled=!busy && !accountBusy && pairing.isNotBlank() && gateway.isNotBlank(),onClick={
                                        val input=pairing; val base=gateway
                                        runTask {
                                            val result=MyUnitsClient.importPairing(this@MyUnitsActivity,accountId,input,base)
                                            runOnUiThread { units=result;pairing="";manual=false;showAdd=false }
                                        }
                                    }) { Text("Add paired node") }
                                }
                            }
                        TextButton(enabled=!busy && !accountBusy,onClick={showAdd=false;manual=false}) { Text("Cancel") }
                    }
                }
            }
            Text(message)
            if(units.isNotEmpty()) Text("Linked nodes",style=MaterialTheme.typography.titleMedium)
            if(units.isEmpty() && installations.isEmpty() && !showAdd) Text("No units added yet. Tap Link a node to get started.")
            units.forEach { unit ->
                val managed = SecureCredentialStore.get(this@MyUnitsActivity,
                    provider.key("manager-installation:$accountId:${unit.key}"))
                val pending = SecureCredentialStore.get(this@MyUnitsActivity,"pending-manager-registration:${unit.key}")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(unit.name,style=MaterialTheme.typography.titleMedium)
                    Text(if(unit.scope in listOf("gateway_read_only","gateway_hosted_read_only")) "${unit.gateway} · Linked phone app" else "${unit.gateway} · Unit ${unit.unitId}",style=MaterialTheme.typography.bodySmall)
                    val status=statuses[unit.key]; val threat=status?.optJSONObject("threat")
                    if(unit.scope in listOf("gateway_read_only","gateway_hosted_read_only")) {
                        Text(errors[unit.key] ?: if(status!=null) "Gateway reachable · Checked: ${status.optString("server_time")}" else "Status not checked")
                        Text(if (managed != null) "Management access saved; permission is checked when opened."
                            else "Read-only gateway access. Management requires separate approval.",style=MaterialTheme.typography.bodySmall)
                    } else if(threat==null) Text(errors[unit.key] ?: "Status not checked") else {
                        Text(when { threat.optBoolean("confirmedLocalInfection") -> "Gateway reports a confirmed local infection"; threat.optBoolean("warning") -> "Threat warning — investigate"; else -> "No current warning reported" })
                        Text("Severity: ${threat.optInt("severity")} · Recent threat records (24h): ${threat.optInt("recentThreatRecords24h")}")
                        Text("Source: ${threat.optString("assessmentSource", "gateway_unit_status")} · Checked: ${status?.optString("server_time")}",style=MaterialTheme.typography.bodySmall)
                        threat.optString("why").takeIf { it.isNotBlank() && it!="null" }?.let { Text(it) }
                        threat.optString("aiSummary").takeIf { it.isNotBlank() }?.let { Text("AI: $it") }
                        if(threat.has("aiConfidence") && !threat.isNull("aiConfidence")) Text("AI confidence: ${threat.opt("aiConfidence")}")
                        Text("An assessment is evidence, not proof of wrongdoing. For a warning, check the evidence and seek help from your operator.",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://tarasec.org/ai/")))}) { Text("AI and recovery guidance") }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=!busy && !accountBusy,onClick={refresh(unit)}) { Text("Check status") }
                        TextButton(enabled=!busy && !accountBusy,onClick={rename=unit;newName=unit.name}) { Text("Name") }
                    }
                    OutlinedButton(enabled=!busy && !accountBusy,onClick={
                        startActivity(Intent(this@MyUnitsActivity,MainActivity::class.java)
                            .putExtra(CONSOLE_DESTINATION_EXTRA,if (managed != null) TaraMenuDestination.STATUS_UNITS.name else TaraMenuDestination.SETUP_HOTSPOTS.name)
                            .putExtra(UNIT_MANAGEMENT_REQUEST_EXTRA,true)
                            .putExtra(UNIT_MANAGEMENT_KEY_EXTRA,unit.key)
                            .putExtra(UNIT_MANAGEMENT_NAME_EXTRA,unit.name))
                    }) {
                        Text(when {
                            managed != null -> "Manage"
                            pending != null -> "Check management approval"
                            else -> "Request management access"
                        })
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled=!busy && !accountBusy,onClick={if(accountId!=null){units=units.filter { it.key!=unit.key };MyUnitsClient.save(this@MyUnitsActivity,accountId,units);statuses=statuses-unit.key;errors=errors-unit.key}}) { Text("Remove from phone") }
                        if(unit.gatewayId.isNotBlank()) TextButton(enabled=!busy && !accountBusy,onClick={revoke=unit}) { Text("Unlink account") }
                    }
                } }
            }
            if(units.isNotEmpty()) Text("Saved units remain here when offline. Removing one from the phone does not revoke gateway access. Unlink account revokes this account's Google-linked app grants for that unit; other accounts and manual tokens are separate.",style=MaterialTheme.typography.bodySmall)
        }
        rename?.let { unit -> AlertDialog(onDismissRequest={rename=null},title={Text("Name this unit")},text={OutlinedTextField(value=newName,onValueChange={newName=it.take(100)})},confirmButton={TextButton(onClick={if(accountId!=null){ units=units.map { if(it.key==unit.key) it.copy(name=newName.trim().ifBlank {unit.name}) else it };MyUnitsClient.save(this@MyUnitsActivity,accountId,units)};rename=null}){Text("Save")}},dismissButton={TextButton(onClick={rename=null}){Text("Cancel")}}) }
        revoke?.let { unit -> AlertDialog(onDismissRequest={revoke=null},title={Text("Unlink ${unit.name}?")},text={Text("Revoke this account's linked app access on all phones for this unit. You will need to sign in again on the unit to link it again.")},confirmButton={TextButton(onClick={revoke=null;if(accountId!=null)runTask{MyUnitsClient.unlink(this@MyUnitsActivity,accountId,unit);val result=MyUnitsClient.load(this@MyUnitsActivity,accountId);runOnUiThread{units=result;statuses=statuses-unit.key}}}){Text("Unlink")}},dismissButton={TextButton(onClick={revoke=null}){Text("Cancel")}}) }
    }
}
