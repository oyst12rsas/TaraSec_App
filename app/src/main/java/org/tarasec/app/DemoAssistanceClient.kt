package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class DemoAssistanceParticipant(val id: Int, val nickname: String, val observedIp: String, val severity: Int, val decision: String)
data class DemoAssistanceSession(
    val id: Int, val name: String, val threshold: Int, val state: String, val blockAt: String,
    val secondsRemaining: Int, val participants: List<DemoAssistanceParticipant>, val blocked: Int, val allowed: Int
)
data class DemoAssistanceJoin(val participantId: Int, val participantToken: String, val session: DemoAssistanceSession)
data class DemoAssistanceCreate(val controllerToken: String, val session: DemoAssistanceSession)

object DemoAssistanceClient {
    fun list(baseUrl: String): List<DemoAssistanceSession> {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=list", "GET")
        val array = json.optJSONArray("sessions") ?: return emptyList()
        return buildList { for (i in 0 until array.length()) add(parseSession(array.getJSONObject(i))) }
    }

    fun create(baseUrl: String, name: String, threshold: Int, delaySeconds: Int): DemoAssistanceCreate {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=create", "POST",
            JSONObject().put("name", name).put("threshold", threshold).put("delay_seconds", delaySeconds))
        return DemoAssistanceCreate(json.getString("controller_token"), parseSession(json.getJSONObject("session")))
    }

    fun join(baseUrl: String, sessionId: Int, nickname: String): DemoAssistanceJoin {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=join", "POST",
            JSONObject().put("session_id", sessionId).put("nickname", nickname))
        return DemoAssistanceJoin(json.getInt("participant_id"), json.getString("participant_token"), parseSession(json.getJSONObject("session")))
    }

    fun setSeverity(baseUrl: String, sessionId: Int, participantToken: String, severity: Int): DemoAssistanceSession {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=severity", "POST",
            JSONObject().put("session_id", sessionId).put("participant_token", participantToken).put("severity", severity))
        return parseSession(json.getJSONObject("session"))
    }

    fun status(baseUrl: String, sessionId: Int): DemoAssistanceSession {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=status&session_id=$sessionId", "GET")
        return parseSession(json.getJSONObject("session"))
    }

    private fun parseSession(json: JSONObject): DemoAssistanceSession {
        val array = json.optJSONArray("participants")
        val participants = buildList {
            if (array != null) for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                add(DemoAssistanceParticipant(p.optInt("participant_id"), p.optString("nickname"), p.optString("observed_ip"), p.optInt("severity"), p.optString("decision", "pending")))
            }
        }
        val summary = json.optJSONObject("summary") ?: JSONObject()
        return DemoAssistanceSession(
            json.optInt("session_id"), json.optString("name", "Community infection exercise"), json.optInt("threshold", 7),
            json.optString("state", "active"), json.optString("block_at", ""), json.optInt("seconds_remaining", 0),
            participants, summary.optInt("blocked", 0), summary.optInt("allowed", 0)
        )
    }

    private fun request(baseUrl: String, path: String, method: String, body: JSONObject? = null): JSONObject {
        val base = baseUrl.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        val connection = URL("$base/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 10000; connection.useCaches = false; connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("Demo 3 returned invalid JSON (HTTP $code)") }
            if (code !in 200..299 || !json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Demo 3 failed (HTTP $code)"))
            return json
        } finally { connection.disconnect() }
    }
}
