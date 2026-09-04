package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

data class MentalHealthChatMessage(val fromUser: Boolean, val text: String)

class AboutUsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { AboutUsScreen() }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AboutUsScreen() {
    val activity = LocalContext.current as Activity
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
            TaraHamburgerMenu { destination ->
                activity.finish()
                when (destination) {
                    TaraMenuDestination.MY_ACCESS,
                    TaraMenuDestination.FIND_INTERNET -> activity.startActivity(
                        Intent(activity, SubscriberHomeActivity::class.java)
                            .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                    )
                    else -> activity.startActivity(
                        Intent(activity, MainActivity::class.java)
                            .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                    )
                }
            }
        }
        Text("About Us", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Taransvar is a Norwegian non-profit developing practical approaches to mental health and safer Internet services.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { activity.startActivity(Intent(activity, MentalHealthChatActivity::class.java)) }
        ) {
            Text("Mental Health Chat")
        }
    }
}

class MentalHealthChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { MentalHealthChatScreen() }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MentalHealthChatScreen() {
    val activity = LocalContext.current as Activity
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var chatId by remember { mutableStateOf<String?>(null) }
    var messages by remember {
        mutableStateOf(listOf(MentalHealthChatMessage(false, "Welcome. You can write what is on your mind, and we can talk about it here.")))
    }
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TaraSec", style = MaterialTheme.typography.headlineLarge)
            TaraHamburgerMenu { destination ->
                activity.finish()
                when (destination) {
                    TaraMenuDestination.MY_ACCESS,
                    TaraMenuDestination.FIND_INTERNET -> activity.startActivity(
                        Intent(activity, SubscriberHomeActivity::class.java)
                            .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                    )
                    else -> activity.startActivity(
                        Intent(activity, MainActivity::class.java)
                            .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                    )
                }
            }
        }

        Text("Mental Health Sanctuary", style = MaterialTheme.typography.headlineMedium)
        Text(
            "A private space to talk and reflect. The conversation is supported by AI and is not a diagnosis or a replacement for professional care.",
            style = MaterialTheme.typography.bodyMedium
        )

        messages.forEach { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.fromUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (message.fromUser) "You" else "Sanctuary", style = MaterialTheme.typography.labelMedium)
                    Text(message.text)
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("What's on your mind?") },
            enabled = !sending
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !sending && input.isNotBlank(),
                onClick = {
                    val text = input.trim()
                    input = ""
                    messages = messages + MentalHealthChatMessage(true, text)
                    sending = true
                    status = "Thinking…"
                    Thread {
                        try {
                            val reply = MentalHealthFlowiseClient.send(text, chatId)
                            activity.runOnUiThread {
                                chatId = reply.chatId
                                messages = messages + MentalHealthChatMessage(false, reply.text)
                                sending = false
                                status = "Ready"
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread {
                                messages = messages + MentalHealthChatMessage(false, "I couldn't reach the conversation service right now. ${e.message ?: "Please try again."}")
                                sending = false
                                status = "Connection problem"
                            }
                        }
                    }.start()
                }
            ) { Text(if (sending) "Sending…" else "Send") }

            Button(
                enabled = !sending && messages.size > 1,
                onClick = {
                    messages = listOf(MentalHealthChatMessage(false, "New conversation started. What would you like to talk about?"))
                    chatId = null
                    status = "New conversation"
                }
            ) { Text("New conversation") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
