package org.tarasec.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class MaintenanceWindow(val start: Long, val end: Long)

@Composable
internal fun MaintenanceNotice() {
    var window by remember { mutableStateOf<MaintenanceWindow?>(null) }
    var now by remember { mutableStateOf(Instant.now().epochSecond) }
    LaunchedEffect(Unit) {
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching { fetchMaintenanceWindow() } }
            result.onSuccess { window = it }
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now().epochSecond
            delay(1_000)
        }
    }
    val notice = window ?: return
    if (now >= notice.end) return
    val format = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(ZoneId.systemDefault())
    val start = format.format(Instant.ofEpochSecond(notice.start))
    val end = format.format(Instant.ofEpochSecond(notice.end))
    val textColor = Color(0xFF382800)
    Column(Modifier.fillMaxWidth().background(Color(0xFFFFF2C6)).padding(12.dp)) {
        Text(
            if (now >= notice.start) "Maintenance in progress" else "Scheduled maintenance",
            style = MaterialTheme.typography.titleSmall,
            color = textColor
        )
        Text("Service may be disrupted from $start to $end.", color = textColor)
    }
}

private fun fetchMaintenanceWindow(): MaintenanceWindow? {
    val url = URL("https://tarasec.org/script/maintenanceNotice.php")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.useCaches = false
        check(connection.responseCode == HttpURLConnection.HTTP_OK)
        val response = connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(1025)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read == -1) break
                count += read
            }
            check(count in 1..1024)
            String(buffer, 0, count)
        }
        val notice = JSONObject(response).get("notice")
        if (notice == JSONObject.NULL) return null
        val schedule = notice as JSONObject
        val start = schedule.getLong("starts_at")
        val end = schedule.getLong("ends_at")
        check(start > 0 && end > start && end <= 253402300799L)
        return MaintenanceWindow(start, end)
    } finally {
        connection.disconnect()
    }
}
