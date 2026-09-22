package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class DemoAssistanceParticipant(
    val id: Int,
    val nickname: String,
    val observedIp: String,
    val severity: Int?,
    val decision: String,
    val secondsSinceSeen: Int?
)

data class DemoAssistanceSession(
    val id: Int,
    val name: String,
    val threshold: Int,
    val state: String,
    val targetIp: String,
    val visibility: String,
    val groupLabel: String,
    val containmentSeconds: Int,
    val blockAt: String,
    val releaseAt: String,
    val secondsRemaining: Int,
    val releaseSecondsRemaining: Int,
    val observationSecondsRemaining: Int,
    val assistanceRequestId: Int?,
    val releaseRequestId: Int?,
    val participants: List<DemoAssistanceParticipant>,
    val connected: Int,
    val silent: Int,
    val recovered: Int
)

data class DemoAssistanceJoin(val participantId: Int, val participantToken: String, val session: DemoAssistanceSession)
data class DemoAssistanceCreate(val controllerToken: String, val session: DemoAssistanceSession)

object DemoAssistanceClient {
    fun list(baseUrl: String, joinCode: String = ""): List<DemoAssistanceSession> {
        val suffix = if (joinCode.isBlank()) "" else "&join_code=" + URLEncoder.encode(joinCode, Charsets.UTF_8.name())
        val json = request(baseUrl, "script/appDemoAssistance.php?action=list$suffix", "GET")
        val array = json.optJSONArray("sessions") ?: return emptyList()
        return buildList { for (i in 0 until array.length()) add(parseSession(array.getJSONObject(i))) }
    }

    fun create(baseUrl: String, name: String, threshold: Int, delaySeconds: Int, containmentSeconds: Int, groupLabel: String = "", joinCode: String = ""): DemoAssistanceCreate {
        val targetIp = URL(normaliseBase(baseUrl)).host
        val json = request(
            baseUrl,
            "script/appDemoAssistance.php?action=create",
            "POST",
            JSONObject()
                .put("name", name)
                .put("threshold", threshold)
                .put("delay_seconds", delaySeconds)
                .put("containment_seconds", containmentSeconds)
                .put("target_ip", targetIp)
                .put("group_label", groupLabel)
                .put("join_code", joinCode)
        )
        return DemoAssistanceCreate(json.getString("controller_token"), parseSession(json.getJSONObject("session")))
    }

    fun join(baseUrl: String, sessionId: Int, nickname: String, joinCode: String = ""): DemoAssistanceJoin {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=join", "POST",
            JSONObject().put("session_id", sessionId).put("nickname", nickname).put("join_code", joinCode))
        return DemoAssistanceJoin(json.getInt("participant_id"), json.getString("participant_token"), parseSession(json.getJSONObject("session")))
    }

    fun setSeverity(baseUrl: String, sessionId: Int, participantToken: String, severity: Int): DemoAssistanceSession {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=severity", "POST",
            JSONObject().put("session_id", sessionId).put("participant_token", participantToken).put("severity", severity))
        return parseSession(json.getJSONObject("session"))
    }

    fun leave(baseUrl: String, sessionId: Int, participantToken: String): DemoAssistanceSession {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=leave", "POST",
            JSONObject().put("session_id", sessionId).put("participant_token", participantToken))
        if (!json.optBoolean("ok")) error(json.optString("error", "Leave failed"))
        return parseSession(json.getJSONObject("session"))
    }

    fun release(baseUrl: String, sessionId: Int, controllerToken: String): DemoAssistanceSession {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=release", "POST",
            JSONObject().put("session_id", sessionId).put("controller_token", controllerToken))
        if (!json.optBoolean("ok")) error(json.optString("error", "Release failed"))
        return parseSession(json.getJSONObject("session"))
    }

    fun heartbeat(baseUrl: String, sessionId: Int, participantToken: String): DemoAssistanceSession {
        val json = request(baseUrl, "script/appDemoAssistance.php?action=heartbeat", "POST",
            JSONObject().put("session_id", sessionId).put("participant_token", participantToken))
        return parseSession(json.getJSONObject("session"))
    }

    fun status(baseUrl: String, sessionId: Int, joinCode: String = ""): DemoAssistanceSession {
        val suffix = if (joinCode.isBlank()) "" else "&join_code=" + URLEncoder.encode(joinCode, Charsets.UTF_8.name())
        val json = request(baseUrl, "script/appDemoAssistance.php?action=status&session_id=$sessionId$suffix", "GET")
        return parseSession(json.getJSONObject("session"))
    }

    fun setLocalSeverity(gatewayBaseUrl: String, severity: Int): String {
        val base = normaliseBase(gatewayBaseUrl)
        val connection = URL("$base/script/appInfectionControl.php").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 7000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            val infected = if (severity > 0) "1" else "0"
            val body = "infected=$infected&demo=1&severity=" + URLEncoder.encode(severity.toString(), Charsets.UTF_8.name())
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299 || json?.optBoolean("ok", false) != true) {
                throw IllegalStateException(json?.optString("error", "Gateway returned HTTP $code") ?: "Gateway returned HTTP $code")
            }
            return json.optString("message", "Local severity set to $severity")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSession(json: JSONObject): DemoAssistanceSession {
        val array = json.optJSONArray("participants")
        val participants = buildList {
            if (array != null) for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                add(
                    DemoAssistanceParticipant(
                        p.optInt("participant_id"),
                        p.optString("nickname"),
                        p.optString("observed_ip"),
                        if (p.isNull("severity")) null else p.optInt("severity"),
                        p.optString("decision", "pending"),
                        if (p.isNull("seconds_since_seen")) null else p.optInt("seconds_since_seen")
                    )
                )
            }
        }
        val summary = json.optJSONObject("summary") ?: JSONObject()
        return DemoAssistanceSession(
            id = json.optInt("session_id"),
            name = json.optString("name", "Community infection exercise"),
            threshold = json.optInt("threshold", 7),
            state = json.optString("state", "active"),
            targetIp = json.optString("target_ip", ""),
            visibility = json.optString("visibility", "public"),
            groupLabel = json.optString("group_label", ""),
            containmentSeconds = json.optInt("containment_seconds", 120),
            blockAt = json.optString("block_at", ""),
            releaseAt = json.optString("release_at", ""),
            secondsRemaining = json.optInt("seconds_remaining", 0),
            releaseSecondsRemaining = json.optInt("release_seconds_remaining", 0),
            observationSecondsRemaining = json.optInt("observation_seconds_remaining", 0),
            assistanceRequestId = if (json.isNull("assistance_request_id")) null else json.optInt("assistance_request_id"),
            releaseRequestId = if (json.isNull("release_request_id")) null else json.optInt("release_request_id"),
            participants = participants,
            connected = summary.optInt("connected", 0),
            silent = summary.optInt("silent", 0),
            recovered = summary.optInt("recovered", 0)
        )
    }

    private fun normaliseBase(value: String): String {
        val v = value.trim().trimEnd('/')
        return if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "http://$v"
    }

    private fun request(baseUrl: String, path: String, method: String, body: JSONObject? = null): JSONObject {
        val connection = URL("${normaliseBase(baseUrl)}/$path").openConnection() as HttpURLConnection
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
            val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("Demo 3 returned invalid JSON (HTTP $code)") }
            if (code !in 200..299 || !json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Demo 3 failed (HTTP $code)"))
            return json
        } finally {
            connection.disconnect()
        }
    }
}
