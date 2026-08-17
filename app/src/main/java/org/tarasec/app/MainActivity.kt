package org.tarasec.app

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
import androidx.compose.ui.unit.dp

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
    var dbServer by remember { mutableStateOf("100.68.126.0") }
    var gateway by remember { mutableStateOf("10.100.0.1") }
    var status by remember { mutableStateOf("Not connected") }

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
            label = { Text("Gateway IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(onClick = {
            status = "Configured DB server $dbServer and gateway $gateway. Connectivity comes in A3."
        }) {
            Text("Save / test setup")
        }

        Text(status)
    }
}
