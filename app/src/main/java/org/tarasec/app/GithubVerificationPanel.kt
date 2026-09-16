package org.tarasec.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun GithubVerificationPanel() {
    val context = LocalContext.current
    var challenge by remember { mutableStateOf(GithubVerificationClient.savedChallenge(context)) }
    var result by remember { mutableStateOf<GithubVerificationResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    TaraSectionCard(
        title = "Verify GitHub contributor account",
        subtitle = "Prove account control with a short-lived public challenge."
    ) {
        Text(
            "Verification identifies the GitHub account that posted the challenge. " +
                "It does not award merit or authorize repository access.",
            style = MaterialTheme.typography.bodySmall
        )

        if (challenge == null) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    Thread {
                        runCatching { GithubVerificationClient.start(context) }
                            .onSuccess { created ->
                                (context as? android.app.Activity)?.runOnUiThread {
                                    challenge = created
                                    result = null
                                    busy = false
                                }
                            }
                            .onFailure { failure ->
                                (context as? android.app.Activity)?.runOnUiThread {
                                    error = failure.message ?: "Unable to start verification"
                                    busy = false
                                }
                            }
                    }.start()
                }
            ) {
                Text(if (busy) "Creating challenge…" else "Start GitHub verification")
            }
        } else {
            val active = requireNotNull(challenge)
            Text("Copy and post this exact line:", fontWeight = FontWeight.Bold)
            Text(active.marker, fontFamily = FontFamily.Monospace)
            Text("Expires: " + active.expiresAt, style = MaterialTheme.typography.bodySmall)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("TaraSec GitHub verification", active.marker)
                        )
                    }
                ) {
                    Text("Copy verification line")
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(active.issueUrl)))
                    }
                ) {
                    Text("Open Contributor Identification issue")
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        Thread {
                            runCatching { GithubVerificationClient.check(active) }
                                .onSuccess { checked ->
                                    (context as? android.app.Activity)?.runOnUiThread {
                                        result = checked
                                        busy = false
                                    }
                                }
                                .onFailure { failure ->
                                    (context as? android.app.Activity)?.runOnUiThread {
                                        error = failure.message ?: "Unable to check verification"
                                        busy = false
                                    }
                                }
                        }.start()
                    }
                ) {
                    Text(if (busy) "Checking…" else "Check verification")
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        challenge = null
                        result = null
                    }
                ) {
                    Text("Create a new challenge")
                }
            }
        }

        result?.let { verified ->
            if (verified.verified) {
                Text(
                    "Verified GitHub account: @" + verified.githubLogin,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "GitHub user ID: " + verified.githubUserId,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(verified.message, style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
