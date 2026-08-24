package org.tarasec.app

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val GatekeeperGreen = Color(0xFFAFB99D)
private val GatekeeperBorder = Color(0xFF8C4B4B)
private val DotGreen = Color(0xFF59E02D)
private val DotYellow = Color(0xFFFFD22E)
private val DotRed = Color(0xFFFF4B24)

private data class StatusSite(
    val name: String,
    val ip: String,
    val secondsSince: Int?,
    val status: JSONObject,
    val local: Boolean = false
)

private data class ActiveUnit(
    val hostname: String,
    val vendor: String,
    val mac: String,
    val lastSeen: String,
    val lastIp: String
)

private enum class DotState { GREEN, YELLOW, RED }

private fun intervalDot(value: Double?, ok: Double, error: Double): DotState {
    if (value == null) return DotState.YELLOW
    return when {
        value <= ok -> DotState.GREEN
        value < error -> DotState.YELLOW
        else -> DotState.RED
    }
}

private fun boolDot(status: JSONObject, field: String): DotState = when {
    !status.has(field) -> DotState.YELLOW
    status.optInt(field, 0) == 1 -> DotState.GREEN
    else -> DotState.RED
}

private fun sizeToKb(value: String): Double {
    val m = Regex("^\\s*([0-9.]+)\\s*([KMGT]?I?)?\\s*$", RegexOption.IGNORE_CASE).find(value) ?: return 0.0
    val n = m.groupValues[1].toDoubleOrNull() ?: return 0.0
    return n * when (m.groupValues[2].uppercase()) {
        "K", "KI", "" -> 1.0
        "M", "MI" -> 1_000.0
        "G", "GI" -> 1_000_000.0
        "T", "TI" -> 1_000_000_000.0
        else -> 1.0
    }
}

private fun statusDots(site: StatusSite): List<DotState> {
    val j = site.status
    val dots = mutableListOf<DotState>()
    dots += intervalDot(site.secondsSince?.toDouble(), 130.0, 200.0)
    dots += boolDot(j, "knl")
    dots += boolDot(j, "lnk")
    dots += boolDot(j, "cron")
    dots += intervalDot(if (j.has("dmesg")) j.optDouble("dmesg") else null, 60.0, 130.0)
    dots += intervalDot(if (j.has("trfc")) j.optDouble("trfc") else null, 60.0, 130.0)
    dots += intervalDot(if (j.has("sqlThrds")) j.optDouble("sqlThrds") else null, 12.0, 25.0)
    dots += if (j.has("bootReq")) if (j.optInt("bootReq") == 1) DotState.RED else DotState.GREEN else DotState.YELLOW

    val updates = j.optString("updates", "").split(';')
    dots += if (updates.isNotEmpty() && updates[0].toDoubleOrNull() != null) intervalDot(updates[0].toDouble(), 15.0, 30.0) else DotState.YELLOW
    dots += if (updates.size > 1 && updates[1].toDoubleOrNull() != null) intervalDot(updates[1].toDouble(), 0.0, 1.0) else DotState.YELLOW
    dots += intervalDot(if (j.has("lstUp")) j.optDouble("lstUp") else null, 7.0 * 86400.0, 30.0 * 86400.0)

    val loads = j.optString("ld", "").trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
    dots += if (loads.isNotEmpty()) intervalDot(loads.take(2).maxOrNull(), 0.7, 2.0) else DotState.YELLOW

    val disk = j.optString("df", "").trim().split(Regex("\\s+"))
    dots += if (disk.size >= 2) {
        val total = sizeToKb(disk[0])
        val used = sizeToKb(disk[1])
        intervalDot(if (total > 0) used * 100.0 / total else 100.0, 70.0, 90.0)
    } else DotState.YELLOW

    val mem = j.optString("mem", "").split('/')
    dots += if (mem.size == 2) {
        val free = sizeToKb(mem[0])
        val total = sizeToKb(mem[1])
        intervalDot(if (total > 0) (total - free) * 100.0 / total else 100.0, 80.0, 95.0)
    } else DotState.YELLOW

    dots += if (j.has("rsyslog")) {
        val v = j.optString("rsyslog")
        if (v.contains("log:1") && v.contains("rsyslog:active") && v.contains("setup:")) DotState.GREEN else DotState.RED
    } else DotState.YELLOW
    dots += if (j.has("srvcNtOk")) if (j.optString("srvcNtOk").isBlank()) DotState.GREEN else DotState.RED else DotState.YELLOW
    dots += intervalDot(if (j.has("usr")) j.optDouble("usr") else null, 1.0, 3.0)
    return dots
}

@Composable
private fun Dot(state: DotState) {
    val color = when (state) {
        DotState.GREEN -> DotGreen
        DotState.YELLOW -> DotYellow
        DotState.RED -> DotRed
    }
    Text("●", color = color, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SiteStatusDots(site: StatusSite) {
    statusDots(site).chunked(6).forEach { rowDots ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            rowDots.forEach { Dot(it) }
        }
    }
}

@Composable
private fun StatusSiteCard(site: StatusSite) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GatekeeperBorder)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            if (site.local) "This gateway — ${site.name}" else site.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (site.ip.isNotBlank()) {
            Text("IP: ${site.ip}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Status", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        SiteStatusDots(site)
    }
}

@Composable
private fun ActiveUnitCard(unit: ActiveUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(unit.hostname.ifBlank { "Unnamed unit" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (unit.vendor.isNotBlank()) Text("Vendor: ${unit.vendor}")
        if (unit.lastIp.isNotBlank()) Text("IP: ${unit.lastIp}")
        if (unit.mac.isNotBlank()) Text("MAC: ${unit.mac}", style = MaterialTheme.typography.bodySmall)
        if (unit.lastSeen.isNotBlank()) Text("Last seen: ${unit.lastSeen}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ServerStatusPanel(gatewayBaseUrl: String?, managerAuthenticated: Boolean) {
    val activity = LocalContext.current as Activity
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Server status not loaded") }
    var localSite by remember { mutableStateOf<StatusSite?>(null) }
    var sites by remember { mutableStateOf<List<StatusSite>>(emptyList()) }
    var units by remember { mutableStateOf<List<ActiveUnit>>(emptyList()) }
    var loadedBase by remember { mutableStateOf<String?>(null) }

    fun load() {
        val base = gatewayBaseUrl ?: return
        if (!managerAuthenticated || loading) return
        loading = true
        Thread {
            var c: HttpURLConnection? = null
            try {
                c = URL("${base.trimEnd('/')}/script/managerOverview.php").openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 10000
                c.useCaches = false
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = if (body.isBlank()) JSONObject() else JSONObject(body)
                if (code !in 200..299 || !json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "HTTP $code"))
                }

                fun parseSite(o: JSONObject, local: Boolean) = StatusSite(
                    name = o.optString("name", if (local) "Gateway" else "Site"),
                    ip = o.optString("ip", ""),
                    secondsSince = if (o.isNull("secondsSince")) null else o.optInt("secondsSince"),
                    status = o.optJSONObject("status") ?: JSONObject(),
                    local = local
                )
                val parsedLocal = json.optJSONObject("local")?.let { parseSite(it, true) }
                val parsedSites = buildList {
                    val arr = json.optJSONArray("sites") ?: JSONArray()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(parseSite(it, false)) }
                }
                val parsedUnits = buildList {
                    val arr = json.optJSONArray("activeUnits") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            ActiveUnit(
                                o.optString("hostname"),
                                o.optString("vendor"),
                                o.optString("mac"),
                                o.optString("lastSeen"),
                                o.optString("lastIp")
                            )
                        )
                    }
                }
                activity.runOnUiThread {
                    localSite = parsedLocal
                    sites = parsedSites
                    units = parsedUnits
                    loadedBase = base
                    message = "Updated"
                    loading = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    message = "Status unavailable: ${e.message}"
                    loading = false
                }
            } finally {
                c?.disconnect()
            }
        }.start()
    }

    LaunchedEffect(gatewayBaseUrl, managerAuthenticated) {
        if (gatewayBaseUrl != loadedBase) {
            localSite = null
            sites = emptyList()
            units = emptyList()
            loadedBase = gatewayBaseUrl
        }
        if (managerAuthenticated && !gatewayBaseUrl.isNullOrBlank()) load()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GatekeeperGreen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Status",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        val allSites = listOfNotNull(localSite) + sites
        if (allSites.isEmpty()) {
            Text("No gateway status reported yet.")
        } else {
            allSites.forEach { site -> StatusSiteCard(site) }
        }

        Text(
            "Dots show status age, TaraKernel, TaraLink, scheduled tasks, dmesg, traffic, SQL, boot/updates, load, disk, memory, rsyslog, services and active users.",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            "Units",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text("Active clients on this installation", style = MaterialTheme.typography.bodySmall)

        if (units.isEmpty()) {
            Text("No active units reported.")
        } else {
            units.forEach { unit -> ActiveUnitCard(unit) }
        }

        Button(
            enabled = managerAuthenticated && !loading && !gatewayBaseUrl.isNullOrBlank(),
            onClick = { load() }
        ) {
            Text(if (loading) "Refreshing…" else "Refresh status")
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
