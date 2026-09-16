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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChallengeGateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var challenge by remember { mutableStateOf<NearbyChallenge?>(null) }
                    var checked by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        challenge = withContext(Dispatchers.IO) { NearbyChallengeClient.current() }
                        checked = true
                        if (challenge == null) {
                            startActivity(Intent(this@ChallengeGateActivity, SubscriberHomeActivity::class.java))
                            finish()
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
                        if (!checked) {
                            Text("Checking for a nearby TaraSec challenge…")
                        }

                        challenge?.let { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(item.title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                                    if (item.description.isNotBlank()) {
                                        Text(item.description, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB00020)),
                                        onClick = {
                                            when (item.destination.lowercase()) {
                                                "demo3" -> startActivity(Intent(this@ChallengeGateActivity, DemoAssistanceActivity::class.java))
                                                "url" -> if (item.destinationUrl.isNotBlank()) {
                                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.destinationUrl)))
                                                }
                                                else -> startActivity(Intent(this@ChallengeGateActivity, DemoAssistanceActivity::class.java))
                                            }
                                        }
                                    ) { Text(item.joinText.ifBlank { "Join challenge" }) }
                                }
                            }

                            Text(
                                "This challenge is shown because TaraSec.org matched this connection to an active local event. The app does not need your phone location for this check.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    startActivity(Intent(this@ChallengeGateActivity, SubscriberHomeActivity::class.java))
                                    finish()
                                }
                            ) { Text("Continue to TaraSec") }
                        }
                    }
                }
            }
        }
    }
}
