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
fun DemoPanel(gatewayName: String?, gatewayBaseUrl: String?) {
    val activity = LocalContext.current as Activity
    var target by remember { mutableStateOf(DemoClient.presets.first()) }
    var targetIp by remember { mutableStateOf(target.ip) }
    var discoveredName by remember { mutableStateOf(target.name) }
    var gatewayState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Status follows what the TaraSec nodes currently report.") }

    fun validIpv4(value: String): Boolean = try { val a=InetAddress.getByName(value.trim()); a is Inet4Address && a.hostAddress==value.trim() } catch (_:Exception){false}
    fun currentTarget()=DemoTarget(DemoClient.presets.firstOrNull{it.ip==targetIp.trim()}?.name?:"Custom node",targetIp.trim())

    fun refresh(after:String="Status updated") {
        val t=currentTarget(); if(!validIpv4(t.ip)){message="Enter a valid IPv4 address for the receiving node.";return}
        Thread { val identity=DemoClient.probe(t); val gateway=gatewayBaseUrl?.takeIf{it.isNotBlank()}?.let{DemoClient.threatStatusBase(it)}; val receiver=DemoClient.threatStatus(t)
            activity.runOnUiThread { discoveredName=identity.nodeName; gatewayState=gateway; receiverState=receiver; message=if(identity.reachable)after else "Destination reached poorly: ${identity.message}" }
        }.start()
    }

    fun setGatewayState(infected:Boolean) {
        if(busy)return; val base=gatewayBaseUrl?.takeIf{it.isNotBlank()}?:run{message="The gateway must be registered before its infection state can be changed.";return}
        val current=gatewayState; if(current!=null&&current.reachable&&current.infected==infected)return
        busy=true; message=if(infected)"Requesting infected state on gateway…" else "Requesting clean state on gateway…"
        Thread { val result=DemoClient.setGatewayInfected(base,infected); try{Thread.sleep(if(infected)2000L else 700L)}catch(_:InterruptedException){}
            val t=currentTarget(); val identity=if(validIpv4(t.ip))DemoClient.probe(t)else null; val gateway=DemoClient.threatStatusBase(base); val receiver=if(validIpv4(t.ip))DemoClient.threatStatus(t)else null
            activity.runOnUiThread { if(identity!=null)discoveredName=identity.nodeName; gatewayState=gateway; if(receiver!=null)receiverState=receiver; message=result+if(gateway.infected==infected)" — confirmed by gateway" else " — waiting for gateway state to propagate"; busy=false }
        }.start()
    }

    DisposableEffect(gatewayBaseUrl,targetIp) {
        val running=AtomicBoolean(true); val worker=Thread { while(running.get()) { val t=currentTarget(); if(validIpv4(t.ip)){ val identity=DemoClient.probe(t); val gateway=gatewayBaseUrl?.takeIf{it.isNotBlank()}?.let{DemoClient.threatStatusBase(it)}; val receiver=DemoClient.threatStatus(t); if(!running.get())break; activity.runOnUiThread{discoveredName=identity.nodeName;gatewayState=gateway;receiverState=receiver} }; try{Thread.sleep(2500L)}catch(_:InterruptedException){break} } }.also{it.start()}
        onDispose{running.set(false);worker.interrupt()}
    }

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
        Text("WireGuard determines the route. Every displayed infection state is fetched from getTagData() on the node whose status is being shown.",style=MaterialTheme.typography.bodySmall)
        Text("Receiving node",style=MaterialTheme.typography.titleMedium)
        DemoClient.presets.forEach{preset->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={target=preset;targetIp=preset.ip;discoveredName=preset.name;gatewayState=null;receiverState=null}){Text((if(preset.ip==targetIp)"✓ " else "")+"${preset.name} · ${preset.ip}")}}
        OutlinedTextField(value=targetIp,onValueChange={targetIp=it.filter{c->c.isDigit()||c=='.'};target=currentTarget();discoveredName=target.name;gatewayState=null;receiverState=null},label={Text("Other TaraSec node IP")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("Path",style=MaterialTheme.typography.titleMedium); Text("Phone → ${gatewayName?:"gateway selected by WireGuard"} → $discoveredName"); Text("Destination: ${targetIp.trim()}",style=MaterialTheme.typography.bodySmall)

        Text("My gateway state",style=MaterialTheme.typography.titleMedium)
        val state=gatewayState
        Row(horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.wrapContentHeight()) { RadioButton(selected=state?.reachable==true&&!state.infected,enabled=state?.reachable==true&&!busy,onClick={setGatewayState(false)}); Text("Clean") }
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.wrapContentHeight()) { RadioButton(selected=state?.reachable==true&&state.infected,enabled=state?.reachable==true&&!busy,onClick={setGatewayState(true)}); Text("Infected") }
        }
        Text(if(state==null)"Waiting for gateway…" else if(!state.reachable)"Gateway status unavailable" else "Reported by gateway: ${if(state.infected)"INFECTED" else "CLEAN"} · severity ${state.severity}",style=MaterialTheme.typography.bodySmall)

        gatewayState?.let{gateway->
            TaraSectionCard(title=gatewayName?:"Gateway",subtitle="getTagData() on gateway"){TaraStatusRow("Reachable",if(gateway.reachable)"Yes" else "No");if(gateway.reachable){TaraStatusRow("Unit state",if(gateway.infected)"🔴 INFECTED" else "🟢 CLEAN");TaraStatusRow("Severity",gateway.severity.toString());if(gateway.source.isNotBlank())TaraStatusRow("Evidence",gateway.source)}else if(gateway.message.isNotBlank())Text(gateway.message,style=MaterialTheme.typography.bodySmall)}
            debugStatus(gatewayName?:"Gateway", gateway)
        }
        receiverState?.let{receiver->
            TaraSectionCard(title=discoveredName,subtitle="getTagData() on ${targetIp.trim()}"){TaraStatusRow("Reachable",if(receiver.reachable)"Yes" else "No");if(receiver.reachable){TaraStatusRow("Reports this unit",if(receiver.infected)"🔴 INFECTED" else "🟢 CLEAN");TaraStatusRow("Severity",receiver.severity.toString());if(receiver.publicIp.isNotBlank())TaraStatusRow("Observed source","${receiver.publicIp}:${receiver.publicPort}");if(receiver.source.isNotBlank())TaraStatusRow("Evidence",receiver.source)}else if(receiver.message.isNotBlank())Text(receiver.message,style=MaterialTheme.typography.bodySmall)}
            debugStatus(discoveredName, receiver)
        }
        Button(enabled=!busy,onClick={refresh()},modifier=Modifier.fillMaxWidth()){Text(if(busy)"Working…" else "Refresh now")}
        Text(message,style=MaterialTheme.typography.bodySmall)
        Text("The radio selection is not stored in the app. It follows the gateway's reported state, so changes made in Gatekeeper should appear here automatically as well.",style=MaterialTheme.typography.bodySmall)
    }
}
