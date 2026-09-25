package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import java.util.UUID

private enum class CoachInfoPage { Privacy, Professional, About }

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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
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
            "TaraSec is developed and owned by Taransvar, a Norwegian non-profit that explores mental health through neuroplasticity.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "We see biological differences as potential talents. Mental health problems can be understood as destructive patterns shaped and reinforced through neuroplasticity. With appropriate support, constructive patterns can be strengthened through assisted training, while destructive patterns can become less problematic as they grow rusty and are obscured by stronger constructive patterns. In this view, focus and motivation are essential for change.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "TaraSec is our collaborative approach to cybersecurity. It connects those who know whether traffic is malicious—the receiver—with those who know which technical unit sent it—the originating network—without exposing private identity data.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Income from cybersecurity will help fund Taransvar's work on mental health.",
            style = MaterialTheme.typography.bodyLarge
        )
        if (MentalHealthFlowiseClient.isConfigured) {
            Text(
                "You can explore an example AI chatbot built on our assumptions here:",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { activity.startActivity(Intent(activity, MentalHealthChatActivity::class.java)) }
            ) {
                Text("Explore Mental Health AI Chat")
            }
        } else {
            Text(
                "The example AI chat is not available in this build. Please use Taransvar's official mental-health app, or contact Taransvar for access.",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://taransvar.no/contact.php"))
                    )
                }
            ) {
                Text("Contact Taransvar")
            }
        }
    }
}

class MentalHealthChatActivity : FragmentActivity() {
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            setContentView(android.widget.TextView(this).apply {
                text = "Set up a device PIN/password/pattern or strong biometric to use Coach."
                setPadding(48, 96, 48, 48)
            })
            return
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    setContent {
                        MaterialTheme {
                            Surface(Modifier.fillMaxSize()) { MentalHealthChatScreen() }
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finish()
                }
            }
        ).authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Taransvar Coach")
            .setSubtitle("Use biometrics or your device PIN/password")
            .setAllowedAuthenticators(authenticators)
            .build())
    }

    override fun onStop() {
        super.onStop()
        if (unlocked) finish() // Returning from background always requires another unlock.
    }
}

@androidx.compose.runtime.Composable
private fun MentalHealthChatScreen() {
    val activity = LocalContext.current as Activity
    val keyboard = LocalSoftwareKeyboardController.current
    val settings = remember { activity.getSharedPreferences("coach_settings", android.content.Context.MODE_PRIVATE) }
    var sendWithEnter by remember { mutableStateOf(settings.getBoolean("send_with_enter", false)) }
    val conversationStore = remember { CoachConversationStore(activity) }
    val savedChatId = remember { conversationStore.load() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (savedChatId == null) "New conversation" else "Conversation resumed") }
    var chatId by remember { mutableStateOf(savedChatId) }
    var confirmNewConversation by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var infoPage by remember { mutableStateOf<CoachInfoPage?>(null) }
    var messages by remember {
        mutableStateOf(
            listOf(
                MentalHealthChatMessage(false, "Welcome. You can write what is on your mind, and we can talk about it here.")
            )
        )
    }
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size) { scroll.animateScrollTo(scroll.maxValue) }

    fun sendMessage() {
        val text = input.trim()
        if (sending || text.isEmpty()) return
        val sessionId = chatId ?: UUID.randomUUID().toString()
        if (chatId == null) {
            try {
                conversationStore.save(sessionId)
            } catch (e: Exception) {
                status = "Could not securely save this conversation. Please try again."
                return
            }
            chatId = sessionId
        }
        keyboard?.hide()
        input = ""
        messages = messages + MentalHealthChatMessage(true, text)
        sending = true
        status = "Thinking…"
        Thread {
            try {
                val reply = MentalHealthFlowiseClient.send(text, sessionId)
                activity.runOnUiThread {
                    chatId = reply.chatId
                    messages = messages + MentalHealthChatMessage(false, reply.text)
                    val saved = runCatching { reply.chatId?.let(conversationStore::save) }.isSuccess
                    sending = false
                    status = if (saved) "Ready" else "Conversation ID could not be saved on this device"
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    messages = messages + MentalHealthChatMessage(
                        false,
                        "I couldn't reach the conversation service right now. ${e.message ?: "Please try again."}"
                    )
                    sending = false
                    status = "Connection problem"
                }
            }
        }.start()
    }

    Column(
        Modifier.fillMaxSize().imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Taransvar Coach", style = MaterialTheme.typography.headlineMedium)
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Text("☰", style = MaterialTheme.typography.headlineMedium)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Send with Enter") },
                        trailingIcon = { Switch(checked = sendWithEnter, onCheckedChange = null) },
                        onClick = {
                            sendWithEnter = !sendWithEnter
                            settings.edit().putBoolean("send_with_enter", sendWithEnter).apply()
                            menuExpanded = false
                        }
                    )
                    CoachInfoPage.entries.forEach { page ->
                        DropdownMenuItem(
                            text = { Text(page.name) },
                            onClick = { menuExpanded = false; infoPage = page }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Back to TaraSec") },
                        onClick = { menuExpanded = false; activity.finish() }
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "A private space to talk and reflect. The conversation is supported by AI and is not a diagnosis or a replacement for professional care.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            messages.forEach { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.fromUser)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (message.fromUser) "You" else "Sanctuary", style = MaterialTheme.typography.labelMedium)
                        Text(message.text)
                    }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (sendWithEnter && event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                    sendMessage()
                    true
                } else false
            },
            minLines = 2,
            maxLines = 4,
            label = { Text("What's on your mind?") },
            enabled = !sending,
            keyboardOptions = KeyboardOptions(imeAction = if (sendWithEnter) ImeAction.Send else ImeAction.Default),
            keyboardActions = KeyboardActions(onSend = { if (sendWithEnter) sendMessage() })
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !sending && input.isNotBlank(),
                onClick = { sendMessage() }
            ) { Text(if (sending) "Sending…" else "Send") }

            Button(
                enabled = !sending && chatId != null,
                onClick = { confirmNewConversation = true }
            ) { Text("New conversation") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }

    if (infoPage != null) {
        val page = infoPage!!
        AlertDialog(
            onDismissRequest = { infoPage = null },
            title = { Text(page.name) },
            text = {
                Text(when (page) {
                    CoachInfoPage.Privacy ->
                        "Messages are sent through TaraSec's conversation service and may be stored there. Coach keeps an encrypted conversation ID on this device so replies can use that history. Past messages are not displayed after closing the app. New conversation removes the local ID; it does not delete server records."
                    CoachInfoPage.Professional ->
                        "Multiple accounts and additional professional features are planned. They are not available in this version."
                    CoachInfoPage.About ->
                        "Taransvar Coach is an AI-supported place to talk and reflect. It does not diagnose or replace professional or emergency care."
                })
            },
            confirmButton = { TextButton(onClick = { infoPage = null }) { Text("Close") } }
        )
    }
    if (confirmNewConversation) {
        AlertDialog(
            onDismissRequest = { confirmNewConversation = false },
            title = { Text("Start a new conversation?") },
            text = { Text("Your current conversation cannot be reopened in Coach after you start a new one. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    conversationStore.clear()
                    messages = listOf(MentalHealthChatMessage(false, "New conversation started. What would you like to talk about?"))
                    chatId = null
                    status = "New conversation"
                    confirmNewConversation = false
                }) { Text("Start new") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewConversation = false }) { Text("Keep conversation") }
            }
        )
    }
}
