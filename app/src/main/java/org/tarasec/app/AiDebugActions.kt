package org.tarasec.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun AiDebugActions(section: String, evidence: String) {
    val context = LocalContext.current
    val report = "TaraSec $section debug report\ngenerated_at_epoch_ms=${System.currentTimeMillis()}\nCredentials and tokens omitted.\n\n$evidence"
    fun copy() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("TaraSec $section", report))
    }
    Column {
        OutlinedButton(onClick = {
            copy()
            Toast.makeText(context, "Debug information copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy debug info for AI") }
        OutlinedButton(onClick = {
            copy()
            try {
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Explain TaraSec $section")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }, "Choose an AI app"))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(context, "Report copied. Open your AI app and paste it.", Toast.LENGTH_LONG).show()
            }
        }) { Text("Share with AI app") }
        Text("Choose an installed AI app that accepts text, or paste the copied report. Review the report before sending.")
    }
}
