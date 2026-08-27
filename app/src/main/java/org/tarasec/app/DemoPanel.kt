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
    val selectedGatewayName = gatewayName?.takeIf { it.isNotBlank() } ?: "No remote gateway selected"
    val selectedGatewayBase = gatewayBaseUrl?.takeIf { it.isNotBlank() }
    var target by remember { mutableStateOf(DemoClient.presets.first()) }
    var targetIp by remember { mutableStateOf(target.ip) }
    var discoveredName by remember { mutableStateOf(target.name) }
    var localState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var selectedGatewayState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var receiverState by remember { mutableStateOf<DemoThreatStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("The Clean/Infected control is this phone's own TaraSec infection state.") }

    fun validIpv4(value: String): Boolean = try { val a=InetAddress.getByName(value.trim()); a is Inet4Address && a.hostAddress==value.trim() } catch (_:Exception){false}
    fun currentTarget()=DemoTarget(DemoClient.presets.firstOrNull{it.ip==targetIp.trim()}?.name?:"Custom node",targetIp.trim())

    fun refresh(after:String="Status updated") {
        val t=currentTarget(); if(!validIpv4(t.ip)){message="Enter a valid IPv4 address for the receiving node.";return}
        Thread {
            val identity=DemoClient.probe(t)
            val local=localGatewayBase?.let{DemoClient.threatStatusBase(it)}
            val selected=selectedGatewayBase?.let{DemoClient.threatStatusBase(it)}
            val receiver=DemoClient.threatStatus(t)
            activity.runOnUiThread {
                discoveredName=identity.nodeName; localState=local; selectedGatewayState=selected; receiverState=receiver
                message=if(identity.reachable)after else "Destination reached poorly: ${identity.message}"
            }
        }.start()
    }

    fun setPhoneState(infected:Boolean) {
        if(busy)return
        val base=localGatewayBase?:run{message="No local Wi-Fi gateway detected. Connect the phone to a TaraSec hotspot first.";return}
        val current=localState; if(current!=null&&current.reachable&&current.infected==infected)return
        busy=true; message=if(infected)"Registering this phone as infected on the local TaraSec hotspot…" else "Marking this phone clean on the local TaraSec hotspot…"
        Thread {
            val result=DemoClient.setGatewayInfected(base,infected)
            try{Thread.sleep(if(infected)1200L else 500L)}catch(_:InterruptedException){}
            val t=currentTarget(); val identity=if(validIpv4(t.ip))DemoClient.probe(t)else null
            val local=DemoClient.threatStatusBase(base)
            val selected=selectedGatewayBase?.let{DemoClient.threatStatusBase(it)}
            val receiver=if(validIpv4(t.ip))DemoClient.threatStatus(t)else null
            activity.runOnUiThread {
                if(identity!=null)discoveredName=identity.nodeName; localState=local; selectedGatewayState=selected; if(receiver!=null)receiverState=receiver
                message=result+if(local.infected==infected)" — local hotspot confirmed this phone" else " — waiting for local hotspot state"; busy=false
            }
        }.start()
    }

    DisposableEffect(localGatewayBase,selectedGatewayBase,targetIp) {
        val running=AtomicBoolean(true)
        val worker=Thread {
            while(running.get()) {
                val t=currentTarget()
                if(validIpv4(t.ip)) {
                    val identity=DemoClient.probe(t)
                    val local=localGatewayBase?.let{DemoClient.threatStatusBase(it)}
                    val selected=selectedGatewayBase?.let{DemoClient.threatStatusBase(it)}
                    val receiver=DemoClient.threatStatus(t)
                    if(!running.get())break
                    activity.runOnUiThread{discoveredName=identity.nodeName;localState=local;selectedGatewayState=selected;receiverState=receiver}
                }
                try{Thread.sleep(2500L)}catch(_:InterruptedException){break}
            }
        }.also{it.start()}
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
        Text("The app shows each network role separately: this phone, the local Wi-Fi hotspot, an optional selected TaraSec/VPN gateway, and the receiving node.",style=MaterialTheme.typography.bodySmall)

        TaraSectionCard(title="Local Wi-Fi hotspot", subtitle="First hop from this phone") {
            TaraStatusRow("Endpoint", localGatewayBase ?: "not detected")
            TaraStatusRow("Reachable", when(localState?.reachable){true->"Yes";false->"No";null->"Checking…"})
            Text("This is Cigar while the phone is connected to the Cigar hotspot. It is not automatically the selected TaraSec VPN gateway.",style=MaterialTheme.typography.bodySmall)
        }

        TaraSectionCard(title=selectedGatewayName, subtitle="Selected TaraSec gateway / VPN path") {
            TaraStatusRow("Endpoint", selectedGatewayBase ?: "not configured")
            val selected=selectedGatewayState
            val vpnText=when {
                selectedGatewayBase==null -> "VPN gateway not configured / not required"
                selected?.reachable==true -> "VPN / gateway path active"
                selected==null -> "Checking selected gateway…"
                else -> "VPN appears to be off — turn on your VPN"
            }
            TaraStatusRow("VPN status",vpnText)
            if(selectedGatewayBase!=null && selected?.reachable==false) Text("$selectedGatewayName is the selected gateway but cannot currently be reached. If you normally reach it through VPN, turn the VPN on.",style=MaterialTheme.typography.bodySmall)
        }

        Text("Receiving node",style=MaterialTheme.typography.titleMedium)
        DemoClient.presets.forEach{preset->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={target=preset;targetIp=preset.ip;discoveredName=preset.name;receiverState=null}){Text((if(preset.ip==targetIp)"✓ " else "")+"${preset.name} · ${preset.ip}")}}
        OutlinedTextField(value=targetIp,onValueChange={targetIp=it.filter{c->c.isDigit()||c=='.'};target=currentTarget();discoveredName=target.name;receiverState=null},label={Text("Other TaraSec node IP")},modifier=Modifier.fillMaxWidth(),singleLine=true)

        Text("Path",style=MaterialTheme.typography.titleMedium)
        Text(if(selectedGatewayBase!=null) "Phone → local Wi-Fi hotspot → $selectedGatewayName → $discoveredName" else "Phone → local Wi-Fi hotspot → $discoveredName")
        Text("Tomato being reachable does not by itself prove that the VPN to $selectedGatewayName is active; the selected gateway is tested separately.",style=MaterialTheme.typography.bodySmall)

        Text("This phone",style=MaterialTheme.typography.titleMedium)
        val state=localState
        Row(horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.wrapContentHeight()) { RadioButton(selected=state?.reachable==true&&!state.infected,enabled=state?.reachable==true&&!busy,onClick={setPhoneState(false)}); Text("Clean") }
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.wrapContentHeight()) { RadioButton(selected=state?.reachable==true&&state.infected,enabled=state?.reachable==true&&!busy,onClick={setPhoneState(true)}); Text("Infected") }
        }
        Text(if(state==null)"Waiting for local hotspot…" else if(!state.reachable)"Local hotspot status unavailable" else "Local hotspot reports this phone: ${if(state.infected)"INFECTED" else "CLEAN"} · severity ${state.severity}",style=MaterialTheme.typography.bodySmall)

        selectedGatewayState?.let{gateway->
            TaraSectionCard(title=selectedGatewayName,subtitle="Selected gateway reachability") {
                TaraStatusRow("Reachable",if(gateway.reachable)"Yes" else "No")
                TaraStatusRow("VPN indication",if(gateway.reachable)"Active / reachable" else "Turn VPN on if this gateway normally uses VPN")
                if(gateway.message.isNotBlank())Text(gateway.message,style=MaterialTheme.typography.bodySmall)
            }
            if(showDebugInfo)debugStatus(selectedGatewayName,gateway)
        }
        receiverState?.let{receiver->
            TaraSectionCard(title=discoveredName,subtitle="Receiving TaraSec node") {
                TaraStatusRow("Reachable",if(receiver.reachable)"Yes" else "No")
                if(receiver.reachable){TaraStatusRow("Reports this phone",if(receiver.infected)"🔴 INFECTED" else "🟢 CLEAN");TaraStatusRow("Severity",receiver.severity.toString());if(receiver.publicIp.isNotBlank())TaraStatusRow("Observed source","${receiver.publicIp}:${receiver.publicPort}");if(receiver.source.isNotBlank())TaraStatusRow("Evidence",receiver.source)}else if(receiver.message.isNotBlank())Text(receiver.message,style=MaterialTheme.typography.bodySmall)
            }
            if(showDebugInfo)debugStatus(discoveredName,receiver)
        }
        Button(enabled=!busy,onClick={refresh()},modifier=Modifier.fillMaxWidth()){Text(if(busy)"Working…" else "Refresh now")}
        Text(message,style=MaterialTheme.typography.bodySmall)
    }
}
