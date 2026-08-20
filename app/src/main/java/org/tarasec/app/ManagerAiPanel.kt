package org.tarasec.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class AiHistoryItem(
    val id: Int?,
    val created: String,
    val fundingMode: String,
    val assessment: JSONObject
)

@Composable
fun ManagerAiPanel(
    gatewayBaseUrl: String?,
    managerAuthenticated: Boolean
) {
    val activity = LocalContext.current as Activity
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var loadedBaseUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("AI assessment not loaded") }
    var latest by remember { mutableStateOf<JSONObject?>(null) }
    var latestTime by remember { mutableStateOf("") }
    var fundingMode by remember { mutableStateOf("") }
    var quotaText by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<AiHistoryItem>>(emptyList()) }
    var showDetails by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    fun loadAi() {
        val base = gatewayBaseUrl
        if (!managerAuthenticated) {
            status = "Manager access must be active before AI assessments can be viewed."
            return
        }
        if (base.isNullOrBlank()) {
            status = "Installation is not configured."
            return
        }
        if (loading) return
        loading = true
        status = "Loading AI assessment..."
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("${base.trimEnd('/')}/script/managerAi.php").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.useCaches = false
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = if (body.isBlank()) JSONObject() else JSONObject(body)
                if (code !in 200..299 || !json.optBoolean("ok", false)) {
                    val error = json.optString("error", "HTTP $code")
                    throw IllegalStateException(when (error) {
                        "manager_session_required", "manager_session_invalid" -> "Manager session is not active."
                        "manager_access_no_longer_active" -> "Manager access is no longer active on this installation."
                        "manager_ai_unavailable" -> "Installation AI assessment service is unavailable."
                        else -> "AI request failed: $error"
                    })
                }

                val latestJson = json.optJSONObject("gatewayAssessment")
                val meta = json.optJSONObject("gatewayAssessmentMeta")
                val quota = meta?.optJSONObject("quota")
                val parsedHistory = buildList {
                    val arr = json.optJSONArray("gatewayAssessmentHistory") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val assessment = item.optJSONObject("assessment") ?: continue
                        add(AiHistoryItem(
                            id = if (item.isNull("aiResponseId")) null else item.optInt("aiResponseId"),
                            created = item.optString("created", ""),
                            fundingMode = item.optString("fundingMode", ""),
                            assessment = assessment
                        ))
                    }
                }
                activity.runOnUiThread {
                    latest = latestJson
                    latestTime = json.optString("gatewayAssessmentTime", "")
                    fundingMode = meta?.optString("fundingMode", "").orEmpty()
                    quotaText = if (quota != null && quota.optInt("used", -1) >= 0 && quota.optInt("limit", -1) >= 0)
                        "${quota.optInt("used")} / ${quota.optInt("limit")} calls used today" else ""
                    history = parsedHistory
                    status = if (latestJson == null) "No installation AI assessment is available yet." else "Assessment loaded"
                    loading = false
                    loadedOnce = true
                    loadedBaseUrl = base
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    status = e.message ?: "AI assessment request failed"
                    loading = false
                    loadedOnce = true
                    loadedBaseUrl = base
                }
            } finally { connection?.disconnect() }
        }.start()
    }

    LaunchedEffect(managerAuthenticated, gatewayBaseUrl) {
        if (gatewayBaseUrl != loadedBaseUrl) {
            loadedOnce = false
            latest = null
            latestTime = ""
            fundingMode = ""
            quotaText = ""
            history = emptyList()
            showDetails = false
            showHistory = false
            loadedBaseUrl = gatewayBaseUrl
        }
        if (managerAuthenticated && !gatewayBaseUrl.isNullOrBlank() && !loadedOnce) loadAi()
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        // Keep the first manager view deliberately familiar with the Gatekeeper
        // web status page. Selection affects this installation only; global threat
        // watching remains independent in MainActivity.
        ServerStatusPanel(gatewayBaseUrl, managerAuthenticated)

        TaraSectionCard(
            title = "AI assessment",
            subtitle = "Local evidence combined with TaraSec network context. AI is supporting evidence, not a confirmed infection state."
        ) {
            latest?.let { assessment ->
                val severity = assessment.optInt("event_severity", assessment.optInt("severity", 0))
                val category = assessment.optString("category", "unknown")
                val confidenceRaw = assessment.optDouble("confidence", Double.NaN)
                val confidence = if (confidenceRaw.isNaN()) "—" else String.format("%.0f%%", confidenceRaw * 100.0)
                val summary = assessment.optString("summary", "")
                val reasoning = assessment.optString("reasoning", "")
                val action = assessment.optString("recommended_action", "")

                TaraStatusRow("Severity", "$severity / 10")
                TaraStatusRow("Category", category)
                TaraStatusRow("Confidence", confidence)
                if (latestTime.isNotBlank()) TaraStatusRow("Assessed", latestTime)
                if (summary.isNotBlank()) { Text("Summary", style = MaterialTheme.typography.labelLarge); Text(summary) }
                if (action.isNotBlank()) { Text("Recommended action", style = MaterialTheme.typography.labelLarge); Text(action) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Details") }
                    if (history.size > 1) OutlinedButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "Hide history" else "History (${history.size})")
                    }
                }

                if (showDetails) {
                    if (fundingMode.isNotBlank()) TaraStatusRow("AI mode", fundingMode.replace('_', ' '))
                    if (quotaText.isNotBlank()) TaraStatusRow("Quota", quotaText)
                    if (reasoning.isNotBlank()) { Text("Reasoning", style = MaterialTheme.typography.labelLarge); Text(reasoning, style = MaterialTheme.typography.bodySmall) }
                    val units = assessment.optJSONArray("unit_assessments") ?: JSONArray()
                    if (units.length() > 0) {
                        Text("Unit findings (${units.length()})", style = MaterialTheme.typography.labelLarge)
                        for (i in 0 until minOf(units.length(), 10)) {
                            val u = units.optJSONObject(i) ?: continue
                            Text("Owner ${u.optString("owner_id", "?")} · Unit ${u.optString("unit_id", "?")} · severity ${u.optInt("severity", 0)} — ${u.optString("summary", "")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val clusters = assessment.optJSONArray("botnet_clusters") ?: JSONArray()
                    if (clusters.length() > 0) {
                        Text("Coordinated-activity candidates (${clusters.length()})", style = MaterialTheme.typography.labelLarge)
                        for (i in 0 until minOf(clusters.length(), 5)) {
                            val c = clusters.optJSONObject(i) ?: continue
                            Text("${c.optString("candidate_key", c.optString("name", "Candidate ${i + 1}"))} — ${c.optString("summary", "")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (showHistory) {
                    Text("Assessment history", style = MaterialTheme.typography.titleSmall)
                    history.take(20).forEach { item ->
                        val a = item.assessment
                        Text("${item.id?.let { "#$it " } ?: ""}${item.created} · severity ${a.optInt("event_severity", a.optInt("severity", 0))} · ${a.optString("category", "unknown")} — ${a.optString("summary", "")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (latest == null) Text(status)
            Button(enabled = managerAuthenticated && !loading && !gatewayBaseUrl.isNullOrBlank(), onClick = { loadAi() }) {
                Text(if (loading) "Loading…" else if (latest == null) "Load assessment" else "Refresh")
            }
        }
    }
}
