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
    var testing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
        Text("Initial owner setup", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = dbServer,
            onValueChange = { dbServer = it },
            label = { Text("DB server IP") },
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
                val host = dbServer.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .trimEnd('/')

                if (host.isBlank()) {
                    status = "Enter a DB server IP first."
                    return@Button
                }

                testing = true
                status = "Connecting to $host..."

                Thread {
                    var connection: HttpURLConnection? = null
                    try {
                        val url = URL("http://$host/script/appSetup.php")
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
                                "Connected. DB server sees gateway/client as $seenGateway" +
                                    if (serverTime.isNotBlank()) " (server $serverTime)" else ""
                            } else {
                                "Connected, but DB server did not return a gateway address."
                            }
                            testing = false
                        }
                    } catch (e: Exception) {
                        activity.runOnUiThread {
                            status = "Connection failed: ${e.message ?: e.javaClass.simpleName}"
                            testing = false
                        }
                    } finally {
                        connection?.disconnect()
                    }
                }.start()
            }
        ) {
            Text(if (testing) "Testing..." else "Save / test setup")
        }

        Text(status)
    }
}
