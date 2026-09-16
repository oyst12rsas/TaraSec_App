package org.tarasec.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun DynamicWorkspacePanel() {
    val context = LocalContext.current
    var manifest by remember { mutableStateOf<DynamicWorkspaceManifest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var latestReceipt by remember { mutableStateOf(DynamicWorkspaceClient.latestReceipt(context)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(refreshKey) {
        error = null
        Thread {
            runCatching { DynamicWorkspaceClient.load(context) }
                .onSuccess { loaded ->
                    (context as? android.app.Activity)?.runOnUiThread { manifest = loaded }
                }
                .onFailure { failure ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        error = failure.message ?: "Workspace unavailable"
                    }
                }
        }.start()
    }

    TaraSectionCard(
        title = manifest?.title ?: "My TaraSec workspace",
        subtitle = "Secure, server-driven content that can be updated without recompiling the app."
    ) {
        when {
            manifest == null && error == null -> Text("Loading workspace…")
            manifest == null -> {
                Text(error ?: "Workspace unavailable")
                Button(onClick = { refreshKey++ }) { Text("Try again") }
            }
            else -> {
                val loaded = requireNotNull(manifest)
                if (loaded.cached) {
                    Text(
                        "Showing the last valid workspace because the live service is unavailable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                loaded.generatedAt?.let {
                    Text("Updated: $it", style = MaterialTheme.typography.bodySmall)
                }
                loaded.elements.forEach { element ->
                    WorkspaceElement(
                        element = element,
                        fieldValues = fieldValues,
                        onNotice = { notice = it },
                        onRefresh = { refreshKey++ },
                        onReceipt = { latestReceipt = it }
                    )
                }
                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                latestReceipt?.let { ReceiptStatusPanel(it) }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { refreshKey++ }) {
                    Text("Refresh workspace")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceElement(
    element: JSONObject,
    fieldValues: MutableMap<String, String>,
    onNotice: (String) -> Unit,
    onRefresh: () -> Unit,
    onReceipt: (String) -> Unit
) {
    when (element.optString("type")) {
        "heading" -> Text(element.optString("text"), style = MaterialTheme.typography.titleLarge)
        "text" -> Text(element.optString("text"))
        "ai_text" -> AiWorkspaceText(element)
        "card" -> TaraSectionCard(
            title = element.optString("title", "TaraSec"),
            subtitle = element.optString("subtitle").takeIf { it.isNotBlank() }
        ) {
            element.optJSONArray("elements")?.forEachObject {
                WorkspaceElement(it, fieldValues, onNotice, onRefresh, onReceipt)
            }
        }
        "status" -> TaraStatusRow(
            label = element.optString("label", "Status"),
            value = element.optString("value", "Unknown")
        )
        "list" -> WorkspaceList(element)
        "text_input" -> WorkspaceTextInput(element, fieldValues)
        "select" -> WorkspaceSelect(element, fieldValues)
        "button" -> WorkspaceButton(element, fieldValues, onNotice, onRefresh, onReceipt)
        "qr_code" -> WorkspaceQrCode(element)
        "chat" -> WorkspaceChat(element)
        "divider" -> HorizontalDivider()
    }
}

@Composable
private fun AiWorkspaceText(element: JSONObject) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                element.optString("title", "AI-generated text"),
                style = MaterialTheme.typography.titleMedium
            )
            Text(element.optString("text"))
            val status = element.optString("status", "ai_draft").replace('_', ' ')
            val generated = element.optString("generated_at")
            Text(
                listOf("Status: $status", generated.takeIf { it.isNotBlank() }?.let { "Generated: $it" })
                    .filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WorkspaceList(element: JSONObject) {
    val items = element.optJSONArray("items")
    if (items == null || items.length() == 0) {
        Text(
            element.optString("empty_text", "Nothing to show yet."),
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items.forEachObject { item ->
            Column {
                val title = item.optString("title")
                if (title.isNotBlank()) Text(title, fontWeight = FontWeight.Bold)
                val text = item.optString("text", item.optString("description"))
                if (text.isNotBlank()) Text(text)
                val status = item.optString("status")
                if (status.isNotBlank()) {
                    Text("Status: " + status.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTextInput(element: JSONObject, fieldValues: MutableMap<String, String>) {
    val id = element.optString("id")
    if (id.isBlank()) return
    val current = fieldValues[id].orEmpty()
    OutlinedTextField(
        value = current,
        onValueChange = { fieldValues[id] = it.take(1000) },
        label = { Text(element.optString("label", id)) },
        placeholder = { Text(element.optString("placeholder")) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = element.optBoolean("single_line", true)
    )
}

@Composable
private fun WorkspaceSelect(element: JSONObject, fieldValues: MutableMap<String, String>) {
    val id = element.optString("id")
    val options = element.optJSONArray("options") ?: JSONArray()
    if (id.isBlank() || options.length() == 0) return
    var expanded by remember(id) { mutableStateOf(false) }
    val selectedValue = fieldValues[id].orEmpty()
    val selectedLabel = (0 until options.length()).firstNotNullOfOrNull { index ->
        options.optJSONObject(index)?.takeIf { it.optString("value") == selectedValue }?.optString("label")
    } ?: element.optString("label", "Choose")

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { expanded = true }) {
            Text(selectedLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (index in 0 until options.length()) {
                val option = options.optJSONObject(index) ?: continue
                DropdownMenuItem(
                    text = { Text(option.optString("label", option.optString("value"))) },
                    onClick = {
                        fieldValues[id] = option.optString("value")
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun WorkspaceButton(
    element: JSONObject,
    fieldValues: MutableMap<String, String>,
    onNotice: (String) -> Unit,
    onRefresh: () -> Unit,
    onReceipt: (String) -> Unit
) {
    val context = LocalContext.current
    val enabled = element.optBoolean("enabled", true)
    var busy by remember(element.optString("id")) { mutableStateOf(false) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && !busy,
        onClick = {
            val action = element.optJSONObject("action") ?: JSONObject()
            when (action.optString("type", "none")) {
                "open_url" -> when (val result = DynamicWorkspaceClient.approvedExternalUrl(action.optString("url"))) {
                    is UriResult.Valid -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                    is UriResult.Invalid -> onNotice(result.reason)
                }
                "show_message" -> onNotice(action.optString("message", "This action is not available yet."))
                "refresh" -> onRefresh()
                "submit" -> {
                    val fieldIds = action.optJSONArray("fields") ?: JSONArray()
                    val submitted = buildMap {
                        for (index in 0 until fieldIds.length()) {
                            val id = fieldIds.optString(index)
                            if (id.isNotBlank()) put(id, fieldValues[id].orEmpty())
                        }
                    }
                    busy = true
                    Thread {
                        runCatching {
                            DynamicWorkspaceClient.submitAction(
                                endpoint = action.optString("endpoint"),
                                actionId = action.optString("action_id"),
                                fields = submitted
                            )
                        }.onSuccess { result ->
                            (context as? android.app.Activity)?.runOnUiThread {
                                result.receipt?.let {
                                    DynamicWorkspaceClient.rememberReceipt(context, it)
                                    onReceipt(it)
                                }
                                val receipt = result.receipt?.let { " Receipt: $it" }.orEmpty()
                                onNotice(result.message + receipt)
                                busy = false
                                if (result.refresh) onRefresh()
                            }
                        }.onFailure { failure ->
                            (context as? android.app.Activity)?.runOnUiThread {
                                onNotice(failure.message ?: "Submission failed")
                                busy = false
                            }
                        }
                    }.start()
                }
                else -> onNotice("This action is not available yet.")
            }
        }
    ) {
        Text(if (busy) "Submitting…" else element.optString("label", "Continue"))
    }
}

@Composable
private fun ReceiptStatusPanel(receipt: String) {
    val context = LocalContext.current
    var result by remember(receipt) { mutableStateOf<WorkspaceReceiptStatus?>(null) }
    var error by remember(receipt) { mutableStateOf<String?>(null) }
    var busy by remember(receipt) { mutableStateOf(false) }
    var requestKey by remember(receipt) { mutableIntStateOf(0) }

    LaunchedEffect(receipt, requestKey) {
        busy = true
        error = null
        Thread {
            runCatching { DynamicWorkspaceClient.getReceiptStatus(receipt) }
                .onSuccess { loaded ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        result = loaded
                        busy = false
                    }
                }
                .onFailure { failure ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        error = failure.message ?: "Receipt status unavailable"
                        busy = false
                    }
                }
        }.start()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Latest contribution receipt", style = MaterialTheme.typography.titleMedium)
            Text(receipt, style = MaterialTheme.typography.bodySmall)
            result?.let { status ->
                Text("Status: " + status.status.replace('_', ' '), fontWeight = FontWeight.Bold)
                Text(status.message)
                status.reviewerMessage?.let { Text("Reviewer: $it") }
                status.updatedAt?.let { Text("Updated: $it", style = MaterialTheme.typography.bodySmall) }
                if (!status.meritAwarded) {
                    Text("No merit has been awarded.", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = { requestKey++ }
            ) {
                Text(if (busy) "Checking…" else "Check receipt status")
            }
        }
    }
}

@Composable
private fun WorkspaceQrCode(element: JSONObject) {
    val value = element.optString("value").take(1024)
    val bitmap = remember(value) {
        if (value.isBlank()) null else runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 480, 480)
            Bitmap.createBitmap(480, 480, Bitmap.Config.RGB_565).apply {
                for (x in 0 until 480) {
                    for (y in 0 until 480) {
                        setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
            }
        }.getOrNull()
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(element.optString("title", "QR code"), style = MaterialTheme.typography.titleMedium)
        if (bitmap != null) {
            Box(Modifier.background(Color.White).padding(10.dp)) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = element.optString("title", "TaraSec QR code"),
                    modifier = Modifier.size(220.dp)
                )
            }
        } else {
            Text("QR code unavailable")
        }
        val caption = element.optString("caption")
        if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WorkspaceChat(element: JSONObject) {
    val context = LocalContext.current
    val endpoint = element.optString("endpoint")
    var messages by remember(element.optString("id")) {
        mutableStateOf<List<WorkspaceChatMessage>>(emptyList())
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(element.optString("title", "TaraSec AI"), style = MaterialTheme.typography.titleMedium)
        val intro = element.optString("intro")
        if (intro.isNotBlank()) Text(intro, style = MaterialTheme.typography.bodySmall)
        messages.forEach { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (message.role == "user") {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(if (message.role == "user") "You" else "TaraSec AI", fontWeight = FontWeight.Bold)
                    Text(message.text)
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(4000) },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = element.optBoolean("enabled", true) && !busy && input.isNotBlank(),
            onClick = {
                val userMessage = WorkspaceChatMessage("user", input.trim())
                val next = messages + userMessage
                messages = next
                input = ""
                busy = true
                error = null
                Thread {
                    runCatching { DynamicWorkspaceClient.postChat(endpoint, next) }
                        .onSuccess { reply ->
                            (context as? android.app.Activity)?.runOnUiThread {
                                messages = messages + WorkspaceChatMessage("assistant", reply)
                                busy = false
                            }
                        }
                        .onFailure { failure ->
                            (context as? android.app.Activity)?.runOnUiThread {
                                error = failure.message ?: "TaraSec AI is unavailable"
                                busy = false
                            }
                        }
                }.start()
            }
        ) {
            Text(if (busy) "Waiting for TaraSec AI…" else "Send")
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "AI chat does not award merit, verify evidence or authorize deployment.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
        optJSONObject(index)?.let(block)
    }
}
