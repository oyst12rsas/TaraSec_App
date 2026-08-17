package org.tarasec.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TaraSecSetupScreen()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TaraSecSetupScreen() {
    val activity = LocalContext.current as Activity
    var dbServer by remember { mutableStateOf("100.68.126.0") }
    var gateway by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not connected") }
    var infectionStatus by remember { mutableStateOf("Infection status not checked") }
    var severity by remember { mutableStateOf(0) }
    var infected by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    fun gatewayRequest(action: String) {
        val gatewayHost = gateway.trim()
        if (gatewayHost.isBlank()) {
            infectionStatus = "Connect to the DB server first so TaraSec can learn the gateway IP."
            return
        }

        testing = true
        infectionStatus = if (action == "clear") "Declaring this unit clear..." else "Checking gateway infection status..."

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://$gatewayHost/script/appInfection.php")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = if (action == "clear") "POST" else "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.useCaches = false
                if (action == "clear") {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write("action=clear".toByteArray(Charsets.UTF_8)) }
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw IllegalStateException("Gateway returned HTTP $code: $body")
                }

                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "Gateway rejected request"))
                }

                val nowInfected = json.optBoolean("infected", false)
                val nowSeverity = json.optInt("severity", 0)
                val count = json.optInt("infection_count", 0)
                val clientIp = json.optString("client_ip", "")
                val cleared = json.optInt("cleared", 0)

                activity.runOnUiThread {
                    infected = nowInfected
                    severity = nowSeverity
                    infectionStatus = when {
                        action == "clear" && cleared > 0 && !nowInfected ->
                            "Unit declared clear. Gateway deactivated $cleared infection record(s)."
                        action == "clear" && cleared == 0 && !nowInfected ->
                            "Unit is already clear."
                        nowInfected ->
                            "Gateway reports this unit infected: severity $nowSeverity, $count active record(s)" +
                                if (clientIp.isNotBlank()) " (client $clientIp)" else ""
                        else ->
                            "Gateway reports this unit clear" + if (clientIp.isNotBlank()) " (client $clientIp)" else ""
                    }
                    testing = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    infectionStatus = "Gateway request failed: ${e.message ?: e.javaClass.simpleName}"
                    testing = false
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
        Text("Initial owner setup", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = dbServer,
            onValueChange = { dbServer = it },
            label = { Text("DB server host / URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = gateway,
            onValueChange = { gateway = it },
            label = { Text("Gateway IP seen by DB server") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = true
        )

        Button(
            enabled = !testing,
            onClick = {
                val entered = dbServer.trim().trimEnd('/')
                if (entered.isBlank()) {
                    status = "Enter a DB server host first."
                    return@Button
                }

                val baseUrl = when {
                    entered.startsWith("https://", ignoreCase = true) -> entered
                    entered.startsWith("http://", ignoreCase = true) -> {
                        status = "Unencrypted HTTP is not allowed. Use HTTPS."
                        return@Button
                    }
                    else -> "https://$entered"
                }

                testing = true
                status = "Connecting securely to $baseUrl..."

                Thread {
                    var connection: HttpURLConnection? = null
                    try {
                        val url = URL("$baseUrl/script/appSetup.php")
                        connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.useCaches = false

                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                        if (code !in 200..299) {
                            throw IllegalStateException("DB server returned HTTP $code: $body")
                        }

                        val json = JSONObject(body)
                        if (!json.optBoolean("ok", false)) {
                            throw IllegalStateException(json.optString("error", "DB server rejected setup request"))
                        }

                        val seenGateway = json.optString("gateway_ip", "")
                        val serverTime = json.optString("server_time", "")
                        activity.runOnUiThread {
                            gateway = seenGateway
                            status = if (seenGateway.isNotBlank()) {
                                "Secure connection established. DB server sees gateway/client as $seenGateway" +
                                    if (serverTime.isNotBlank()) " (server $serverTime)" else ""
                            } else {
                                "Secure connection established, but DB server did not return a gateway address."
                            }
                            testing = false
                        }
                    } catch (e: Exception) {
                        activity.runOnUiThread {
                            status = "Secure connection failed: ${e.message ?: e.javaClass.simpleName}"
                            testing = false
                        }
                    } finally {
                        connection?.disconnect()
                    }
                }.start()
            }
        ) {
            Text(if (testing) "Testing..." else "Save / test secure setup")
        }

        Text(status)

        Text("Unit infection status", style = MaterialTheme.typography.titleMedium)
        Text(infectionStatus)
        if (infected) Text("Severity: $severity")

        Button(
            enabled = !testing && gateway.isNotBlank(),
            onClick = { gatewayRequest("status") }
        ) {
            Text("Check infection status")
        }

        Button(
            enabled = !testing && gateway.isNotBlank() && infected,
            onClick = { gatewayRequest("clear") }
        ) {
            Text("Declare this unit clear")
        }
    }
}
