package org.tarasec.app

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun ManagerSshPanel(installationName: String) {
    val context = LocalContext.current
    TaraSectionCard(
        title = "SSH access",
        subtitle = "Google sign-in and Authenticator approval are required to open SSH."
    ) {
        Text("Installation: $installationName")
        Text(
            "Open the secure approval page, select this machine using its enrolled nickname, " +
                "and verify the selection before requesting a temporary opening. " +
                "Enter your Google Authenticator code on that page.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://tarasec.org/ops/agent/")))
        }) { Text("Open SSH approval") }
        Text(
            "The approval page shows the request result and confirmed access countdown. " +
                "Returning to the app does not by itself confirm that SSH is open.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Use your separately configured SSH client connection. " +
                "The app does not display or request the SSH port. " +
                "Your SSH credentials and the node's permitted-source policy still apply.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
