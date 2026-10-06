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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MyUnitsActivity : ComponentActivity() {
    private var resumeVersion by mutableStateOf(0)
    override fun onResume() { super.onResume(); resumeVersion++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MyUnitsScreen(resumeVersion) } } }
    }

    @Composable
    private fun MyUnitsScreen(version: Int) {
        val accountId = remember(version) { MyUnitsClient.accountId(this) }
        val signedIn = remember(version) { SubscriberAccountClient.storedToken(this) != null }
        var units by remember(accountId) { mutableStateOf(accountId?.let { MyUnitsClient.load(this,it) } ?: emptyList()) }
        var gateway by remember { mutableStateOf("") }
        var gatewayExample by remember { mutableStateOf("") }
        var gatewayDiscoveryMessage by remember { mutableStateOf("Checking your IP with the DB server…") }
        var pairing by remember { mutableStateOf("") }
        var manual by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var statuses by remember(accountId) { mutableStateOf<Map<String,JSONObject>>(emptyMap()) }
        var errors by remember(accountId) { mutableStateOf<Map<String,String>>(emptyMap()) }
        var revoke by remember { mutableStateOf<LinkedUnit?>(null) }
        var rename by remember { mutableStateOf<LinkedUnit?>(null) }
        var newName by remember { mutableStateOf("") }

        LaunchedEffect(version, signedIn) {
            gatewayExample = ""
            if (!signedIn) return@LaunchedEffect
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
            if (busy) return
            busy = true; message = "Working…"
            Thread {
                try { work(); runOnUiThread { message = "Updated"; busy = false } }
                catch (e: Exception) { runOnUiThread { message = e.message ?: "Request failed. Status is unknown."; busy = false } }
            }.start()
        }
        fun refresh(unit: LinkedUnit) {
            statuses = statuses - unit.key; errors = errors - unit.key
            runTask {
                try { val s = MyUnitsClient.status(unit); runOnUiThread { statuses = statuses + (unit.key to s) } }
                catch (e: Exception) { runOnUiThread { errors = errors + (unit.key to (e.message ?: "Status unknown")) } }
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("My units",style=MaterialTheme.typography.headlineMedium)
                TextButton(onClick={ finish() }) { Text("Back") }
            }
            Text("Your saved units and their gateway-provided threat information. Unit access does not grant gateway-manager access.")
            if (!signedIn || accountId == null) {
                Text("Sign in with Google in My access first. Use the same account when linking each unit.")
                Button(onClick={ startActivity(Intent(this@MyUnitsActivity,SubscriberHomeActivity::class.java).putExtra(CONSOLE_DESTINATION_EXTRA,TaraMenuDestination.MY_ACCESS.name)) }) { Text("Open sign-in") }
                return@Column
            }
            Text("On each laptop or unit, open its gateway's /script/unitLink.php page while connected to that gateway, then continue with Google. Enter the gateway IP address here and sync. Add only gateways you trust to receive your account proof.")
            OutlinedTextField(value=gateway,onValueChange={gateway=it},label={Text("Gateway IP address")},placeholder={if(gatewayExample.isNotBlank()) Text(gatewayExample)},supportingText={Text(if(gatewayExample.isNotBlank()) "For example: $gatewayExample (reported by the DB server)" else gatewayDiscoveryMessage)},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy && gateway.isNotBlank(),onClick={
                val input=gateway
                runTask { val result=MyUnitsClient.sync(this@MyUnitsActivity,accountId,input); runOnUiThread { units=result; statuses=emptyMap(); errors=emptyMap() } }
            }) { Text("Sync linked units") }
            TextButton(enabled=!busy,onClick={manual=!manual}) { Text(if(manual) "Hide manual pairing" else "Pair a device without Google/browser") }
            if(manual) {
                Text("Paste the JSON produced by the unit pairing tool. This contains a secret; do not share it in a debug report. Enter the reachable gateway IP address above.")
                OutlinedTextField(value=pairing,onValueChange={pairing=it},label={Text("Pairing JSON (secret)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                Button(enabled=!busy && pairing.isNotBlank(),onClick={ val input=pairing; val base=gateway; runTask { val result=MyUnitsClient.importPairing(this@MyUnitsActivity,accountId,input,base); runOnUiThread { units=result; pairing="" } } }) { Text("Import pairing") }
            }
            Text(message)
            if(units.isEmpty()) Text("No saved units. Link a unit and sync its gateway. An empty list does not mean a gateway is clean.")
            units.forEach { unit ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(unit.name,style=MaterialTheme.typography.titleMedium)
                    Text("${unit.gateway} · Unit ${unit.unitId}",style=MaterialTheme.typography.bodySmall)
                    val status=statuses[unit.key]; val threat=status?.optJSONObject("threat")
                    if(threat==null) Text(errors[unit.key] ?: "Status not checked") else {
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
                        Button(enabled=!busy,onClick={refresh(unit)}) { Text("Check status") }
                        TextButton(enabled=!busy,onClick={rename=unit;newName=unit.name}) { Text("Name") }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled=!busy,onClick={units=units.filter { it.key!=unit.key };MyUnitsClient.save(this@MyUnitsActivity,accountId,units);statuses=statuses-unit.key;errors=errors-unit.key}) { Text("Remove from phone") }
                        if(unit.gatewayId.isNotBlank()) TextButton(enabled=!busy,onClick={revoke=unit}) { Text("Unlink account") }
                    }
                } }
            }
            Text("Saved units remain here when offline. Removing one from the phone does not revoke gateway access. Unlink account revokes this account's Google-linked app grants for that unit; other accounts and manual tokens are separate.",style=MaterialTheme.typography.bodySmall)
        }
        rename?.let { unit -> AlertDialog(onDismissRequest={rename=null},title={Text("Name this unit")},text={OutlinedTextField(value=newName,onValueChange={newName=it.take(100)})},confirmButton={TextButton(onClick={if(accountId!=null){ units=units.map { if(it.key==unit.key) it.copy(name=newName.trim().ifBlank {unit.name}) else it };MyUnitsClient.save(this@MyUnitsActivity,accountId,units)};rename=null}){Text("Save")}},dismissButton={TextButton(onClick={rename=null}){Text("Cancel")}}) }
        revoke?.let { unit -> AlertDialog(onDismissRequest={revoke=null},title={Text("Unlink ${unit.name}?")},text={Text("Revoke this account's linked app access on all phones for this unit. You will need to sign in again on the unit to link it again.")},confirmButton={TextButton(onClick={revoke=null;if(accountId!=null)runTask{MyUnitsClient.unlink(this@MyUnitsActivity,accountId,unit);val result=MyUnitsClient.load(this@MyUnitsActivity,accountId);runOnUiThread{units=result;statuses=statuses-unit.key}}}){Text("Unlink")}},dismissButton={TextButton(onClick={revoke=null}){Text("Cancel")}}) }
    }
}
