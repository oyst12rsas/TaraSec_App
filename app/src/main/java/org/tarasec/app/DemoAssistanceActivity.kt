package org.tarasec.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class DemoAssistanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installations = InstallationStore.load(this)
        val selectedId = InstallationStore.selectedId(this)
        val selectedInstallation = installations.firstOrNull { it.id == selectedId }
            ?: installations.firstOrNull()
        val gatewayControlBase = selectedInstallation?.serviceIp
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "http://$it" }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Text("Demo 3 · Request for Assistance", style = MaterialTheme.typography.headlineSmall)
                        DemoAssistancePanel(
                            baseUrl = "http://100.68.126.0",
                            initialGatewayControlBase = gatewayControlBase
                        )
                    }
                }
            }
        }
    }
}
