package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class DemoAssistanceParticipant(
    val id: Int,
    val nickname: String,
    val observedIp: String,
    val severity: Int,
    val decision: String
)

data class DemoAssistanceSession(
    val id: Int,
    val name: String,
    val threshold: Int,
    val state: String,
    val blockAt: String,
    val secondsRemaining: Int,
    val participants: List<DemoAssistanceParticipant>,
    val blocked: Int,
    val allowed: Int
)

data class DemoAssistanceJoin(
    val participantId: Int,
    val participantToken: String,
    val session: DemoAssistanceSession
)

data class DemoAssistanceCreate(
    val controllerToken: String,
    val session: DemoAssistanceSession
)

object DemoAssistanceClient {
    fun current(baseUrl: String): DemoAssistanceSession? {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=current", "GET")
        val session = json.optJSONObject("session") ?: return null
        return parseSession(session)
    }

    fun create(baseUrl: String, name: String, threshold: Int, delaySeconds: Int): DemoAssistanceCreate {
        val body = JSONObject()
            .put("name", name)
            .put("threshold", threshold)
            .put("delay_seconds", delaySeconds)
        val json = request(baseUrl, "script/appDemoAssistance.php?action=create", "POST", body)
        return DemoAssistanceCreate(
            controllerToken = json.getString("controller_token"),
            session = parseSession(json.getJSONObject("session"))
        )
    }

    fun join(baseUrl: String, sessionId: Int, nickname: String): DemoAssistanceJoin {
        val body = JSONObject().put("session_id", sessionId).put("nickname", nickname)
        val json = request(baseUrl, "script/appDemoAssistance.php?action=join", "POST", body)
        return DemoAssistanceJoin(
            participantId = json.getInt("participant_id"),
            participantToken = json.getString("participant_token"),
            session = parseSession(json.getJSONObject("session"))
        )
    }

    fun setSeverity(baseUrl: String, sessionId: Int, participantToken: String, severity: Int): DemoAssistanceSession {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("participant_token", participantToken)
            .put("severity", severity)
        val json = request(baseUrl, "script/appDemoAssistance.php?action=severity", "POST", body)
        return parseSession(json.getJSONObject("session"))
    }

    fun status(baseUrl: String, sessionId: Int): DemoAssistanceSession {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=status&session_id=$sessionId", "GET")
        return parseSession(json.getJSONObject("session"))
    }

    private fun parseSession(json: JSONObject): DemoAssistanceSession {
        val array = json.optJSONArray("participants")
        val participants = buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val p = array.getJSONObject(i)
                    add(
                        DemoAssistanceParticipant(
                            id = p.optInt("participant_id"),
                            nickname = p.optString("nickname"),
                            observedIp = p.optString("observed_ip"),
                            severity = p.optInt("severity"),
                            decision = p.optString("decision", "pending")
                        )
                    )
                }
            }
        }
        val summary = json.optJSONObject("summary") ?: JSONObject()
        return DemoAssistanceSession(
            id = json.optInt("session_id"),
            name = json.optString("name", "Community infection exercise"),
            threshold = json.optInt("threshold", 7),
            state = json.optString("state", "active"),
            blockAt = json.optString("block_at", ""),
            secondsRemaining = json.optInt("seconds_remaining", 0),
            participants = participants,
            blocked = summary.optInt("blocked", 0),
            allowed = summary.optInt("allowed", 0)
        )
    }

    private fun request(baseUrl: String, path: String, method: String, body: JSONObject? = null): JSONObject {
        val base = baseUrl.trim().trimEnd('/').let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        val connection = URL("$base/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("Demo 3 returned invalid JSON (HTTP $code)")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error", "Demo 3 failed (HTTP $code)"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
