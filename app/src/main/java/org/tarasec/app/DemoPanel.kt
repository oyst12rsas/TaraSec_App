package org.tarasec.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun DemoPanel(gatewayName: String?, gatewayBaseUrl: String?, showDebugInfo: Boolean = false) {
    val activity = LocalContext.current as Activity
    val localGatewayBase = remember { LocalGateway.baseUrl(activity) }
    var target by remember { mutableStateOf(DemoClient.presets.first()) }
    var targetIp by remember { mutableStateOf(target.ip) }
    var discoveredName by remember { mutableStateOf(target.name) }
    var gatewayState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("The Clean/Infected control is this phone's own TaraSec infection state.") }

    fun validIpv4(value: String): Boolean = try { val a=InetAddress.getByName(value.trim()); a is Inet4Address && a.hostAddress==value.trim() } catch (_:Exception){false}
    fun currentTarget()=DemoTarget(DemoClient.presets.firstOrNull{it.ip==targetIp.trim()}?.name?:"Custom node",targetIp.trim())

    fun refresh(after:String="Status updated") {
        val t=currentTarget(); if(!validIpv4(t.ip)){message="Enter a valid IPv4 address for the receiving node.";return}
        Thread { val identity=DemoClient.probe(t); val gateway=localGatewayBase?.let{DemoClient.threatStatusBase(it)}; val receiver=DemoClient.threatStatus(t)
            activity.runOnUiThread { discoveredName=identity.nodeName; gatewayState=gateway; receiverState=receiver; message=if(identity.reachable)after else "Destination reached poorly: ${identity.message}" }
        }.start()
    }

    fun setPhoneState(infected:Boolean) {
        if(busy)return
        val base=localGatewayBase?:run{message="No local Wi-Fi gateway detected. Connect the phone to a TaraSec hotspot first.";return}
        val current=gatewayState; if(current!=null&&current.reachable&&current.infected==infected)return
        busy=true; message=if(infected)"Marking this phone infected on the local gateway…" else "Marking this phone clean on the local gateway…"
        Thread { val result=DemoClient.setGatewayInfected(base,infected); try{Thread.sleep(if(infected)1200L else 500L)}catch(_:InterruptedException){}
            val t=currentTarget(); val identity=if(validIpv4(t.ip))DemoClient.probe(t)else null; val gateway=DemoClient.threatStatusBase(base); val receiver=if(validIpv4(t.ip))DemoClient.threatStatus(t)else null
            activity.runOnUiThread { if(identity!=null)discoveredName=identity.nodeName; gatewayState=gateway; if(receiver!=null)receiverState=receiver; message=result+if(gateway.infected==infected)" — local gateway confirmed this phone" else " — waiting for local gateway state"; busy=false }
        }.start()
    }

    DisposableEffect(localGatewayBase,targetIp) {
        val running=AtomicBoolean(true); val worker=Thread { while(running.get()) { val t=currentTarget(); if(validIpv4(t.ip)){ val identity=DemoClient.probe(t); val gateway=localGatewayBase?.let{DemoClient.threatStatusBase(it)}; val receiver=DemoClient.threatStatus(t); if(!running.get())break; activity.runOnUiThread{discoveredName=identity.nodeName;gatewayState=gateway;receiverState=receiver} }; try{Thread.sleep(2500L)}catch(_:InterruptedException){break} } }.also{it.start()}
        onDispose{running.set(false);worker.interrupt()}
    }

    @Composable
    fun debugStatus(title:String, state:DemoThreatStatus) {
        TaraSectionCard(title="$title debug", subtitle="Exact app status request") {
            TaraStatusRow("Polled at", state.polledAt.ifBlank { "n/a" })
            TaraStatusRow("HTTP", if (state.httpCode > 0) state.httpCode.toString() else "n/a")
            Text("Endpoint: ${state.endpoint.ifBlank { "n/a" }}", style=MaterialTheme.typography.bodySmall)
            Text("Raw JSON:", style=MaterialTheme.typography.bodySmall)
            Text(state.rawJson.ifBlank { "(no response body)" }, style=MaterialTheme.typography.bodySmall)
        }
    }

    Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth()) {
        Text("TaraSec Demo",style=MaterialTheme.typography.titleLarge)
        Text("This phone is marked clean/infected on the local TaraSec gateway. Traffic to the receiving TaraSec node is then used to demonstrate propagation of the tag.",style=MaterialTheme.typography.bodySmall)
        Text("Receiving node",style=MaterialTheme.typography.titleMedium)
        DemoClient.presets.forEach{preset->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={target=preset;targetIp=preset.ip;discoveredName=preset.name;gatewayState=null;receiverState=null}){Text((if(preset.ip==targetIp)"✓ " else "")+"${preset.name} · ${preset.ip}")}}
        OutlinedTextField(value=targetIp,onValueChange={targetIp=it.filter{c->c.isDigit()||c=='.'};target=currentTarget();discoveredName=target.name;gatewayState=null;receiverState=null},label={Text("Other TaraSec node IP")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("Path",style=MaterialTheme.typography.titleMedium)
        Text("Phone → ${localGatewayBase ?: "local gateway not detected"} → $discoveredName")
        if (!gatewayName.isNullOrBlank()) Text("Selected owned installation: $gatewayName",style=MaterialTheme.typography.bodySmall)
        Text("Destination: ${targetIp.trim()}",style=MaterialTheme.typography.bodySmall)

        Text("This phone",style=MaterialTheme.typography.titleMedium)
        val state=gatewayState
        Row(horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.wrapContentHeight()) { RadioButton(selected=state?.reachable==true&&!state.infected,enabled=state?.reachable==true&&!busy,onClick={setPhoneState(false)}); Text("Clean") }
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.wrapContentHeight()) { RadioButton(selected=state?.reachable==true&&state.infected,enabled=state?.reachable==true&&!busy,onClick={setPhoneState(true)}); Text("Infected") }
        }
        Text(if(state==null)"Waiting for local gateway…" else if(!state.reachable)"Local gateway status unavailable" else "Local gateway reports this phone: ${if(state.infected)"INFECTED" else "CLEAN"} · severity ${state.severity}",style=MaterialTheme.typography.bodySmall)

        gatewayState?.let{gateway->
            TaraSectionCard(title="This phone",subtitle="getTagData() through ${localGatewayBase ?: "local gateway"}"){TaraStatusRow("Gateway reachable",if(gateway.reachable)"Yes" else "No");if(gateway.reachable){TaraStatusRow("Phone state",if(gateway.infected)"🔴 INFECTED" else "🟢 CLEAN");TaraStatusRow("Severity",gateway.severity.toString());if(gateway.source.isNotBlank())TaraStatusRow("Evidence",gateway.source)}else if(gateway.message.isNotBlank())Text(gateway.message,style=MaterialTheme.typography.bodySmall)}
            if (showDebugInfo) debugStatus("This phone", gateway)
        }
        receiverState?.let{receiver->
            TaraSectionCard(title=discoveredName,subtitle="getTagData() on ${targetIp.trim()}"){TaraStatusRow("Reachable",if(receiver.reachable)"Yes" else "No");if(receiver.reachable){TaraStatusRow("Reports this phone",if(receiver.infected)"🔴 INFECTED" else "🟢 CLEAN");TaraStatusRow("Severity",receiver.severity.toString());if(receiver.publicIp.isNotBlank())TaraStatusRow("Observed source","${receiver.publicIp}:${receiver.publicPort}");if(receiver.source.isNotBlank())TaraStatusRow("Evidence",receiver.source)}else if(receiver.message.isNotBlank())Text(receiver.message,style=MaterialTheme.typography.bodySmall)}
            if (showDebugInfo) debugStatus(discoveredName, receiver)
        }
        Button(enabled=!busy,onClick={refresh()},modifier=Modifier.fillMaxWidth()){Text(if(busy)"Working…" else "Refresh now")}
        Text(message,style=MaterialTheme.typography.bodySmall)
        Text("The app does not store the radio selection. It reads this phone's state back from the local gateway's internalInfections/getTagData() result.",style=MaterialTheme.typography.bodySmall)
    }
}
