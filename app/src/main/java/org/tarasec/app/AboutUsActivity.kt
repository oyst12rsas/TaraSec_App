package org.tarasec.app

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class AboutUsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("About Us", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Taransvar is a Norwegian non-profit developing practical approaches to mental health and safer Internet services.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openMentalHealthChat() }
                        ) {
                            Text("Open Mental Health Chat")
                        }
                    }
                }
            }
        }
    }

    private fun openMentalHealthChat() {
        val chatIntent = packageManager.getLaunchIntentForPackage("org.tarasec.mentalhealth")
        if (chatIntent != null) {
            startActivity(chatIntent)
            return
        }
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://taransvar.no/mental-health.html")
            )
        )
    }
}
