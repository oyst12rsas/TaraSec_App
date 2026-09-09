package org.tarasec.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

enum class DemoSshRole(val wireValue: String) {
    SUPERVISOR("supervisor"), SSH_CLIENT("ssh_client"), NODE_A("node_a"),
    GATEWAY("gateway"), NODE_B("node_b")
}

enum class DemoSshStage(val wireValue: String) {
    WAITING_FOR_PARTICIPANTS("waiting_for_participants"), READY("ready"),
    FIRST_SSH_EXPECTED("first_ssh_expected"), NODE_A_REJECTED("node_a_rejected"),
    GATEWAY_MARKED_UNIT("gateway_marked_unit"), SECOND_SSH_EXPECTED("second_ssh_expected"),
    NODE_B_ACCEPTED_TAGGED_SSH("node_b_accepted_tagged_ssh"),
    LEGITIMATE_REPORTED("legitimate_reported"), GATEWAY_CLEARED("gateway_cleared"),
    COMPLETE("complete"), FAILED("failed"), CANCELLED("cancelled"), UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): DemoSshStage =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class DemoSshParticipant(
    val role: DemoSshRole,
    val installationId: Long? = null,
    val unitId: Long? = null,
    val label: String = ""
)

data class DemoSshCredential(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
) {
    fun command(): String = "ssh -p $port $username@$host"
}

data class DemoSshEvent(val type: String, val message: String, val occurredAt: String)

data class DemoSshSession(
    val sessionId: String,
    val stage: DemoSshStage,
    val nodeBId: Long?,
    val credential: DemoSshCredential?,
    val events: List<DemoSshEvent>,
    val message: String = ""
)

/**
 * DB-server protocol for Demo 2.
 *
 * The app is only the supervisor. The DB server owns session state and the
 * shared demoSshNodeB credential. The plaintext credential is kept in memory
 * only; Node B must receive only a password hash from the server.
 */
object DemoSshClient {
    fun start(
        baseUrl: String,
        bearerToken: String,
        participants: List<DemoSshParticipant>
    ): DemoSshSession = request(
        baseUrl, "script/appDemoSshStart.php", bearerToken, "POST",
        JSONObject().put("participants", JSONArray().apply {
            participants.forEach { participant ->
                put(JSONObject().apply {
                    put("role", participant.role.wireValue)
                    participant.installationId?.let { put("installation_id", it) }
                    participant.unitId?.let { put("unit_id", it) }
                    if (participant.label.isNotBlank()) put("label", participant.label)
                })
            }
        })
    )

    fun status(baseUrl: String, bearerToken: String, sessionId: String): DemoSshSession =
        request(
            baseUrl,
            "script/appDemoSshStatus.php?session_id=" + encodePathValue(sessionId),
            bearerToken,
            "GET"
        )

    fun finish(
        baseUrl: String,
        bearerToken: String,
        sessionId: String,
        cancel: Boolean = false
    ): DemoSshSession = request(
        baseUrl, "script/appDemoSshFinish.php", bearerToken, "POST",
        JSONObject().put("session_id", sessionId).put("cancel", cancel)
    )

    private fun request(
        baseUrl: String,
        path: String,
        bearerToken: String,
        method: String,
        body: JSONObject? = null
    ): DemoSshSession {
        var connection: HttpURLConnection? = null
        return try {
            val base = normaliseBase(baseUrl)
            connection = URL("$base/$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (bearerToken.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val reply = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(reply) }.getOrNull()
                ?: return failure("Invalid DB-server response (HTTP $code)")

            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                return failure(json.optString("error", "DB server returned HTTP $code"))
            }
            parseSession(json)
        } catch (e: Exception) {
            failure(e.message ?: "Could not contact DB server")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseSession(json: JSONObject): DemoSshSession {
        val credentialJson = json.optJSONObject("credential")
        val credential = credentialJson?.let {
            val host = it.optString("host", "").trim()
            val username = it.optString("username", "").trim()
            val password = it.optString("password", "")
            if (host.isBlank() || username.isBlank() || password.isBlank()) null
            else DemoSshCredential(host, it.optInt("port", 22), username, password)
        }
        val eventArray = json.optJSONArray("events")
        val events = buildList {
            if (eventArray != null) {
                for (i in 0 until eventArray.length()) {
                    val item = eventArray.optJSONObject(i) ?: continue
                    add(DemoSshEvent(
                        item.optString("type", "event"),
                        item.optString("message", ""),
                        item.optString("occurred_at", "")
                    ))
                }
            }
        }
        return DemoSshSession(
            sessionId = json.optString("session_id", ""),
            stage = DemoSshStage.fromWire(json.optString("stage", "")),
            nodeBId = json.optLong("node_b_id")
                .takeIf { json.has("node_b_id") && !json.isNull("node_b_id") },
            credential = credential,
            events = events,
            message = json.optString("message", "")
        )
    }

    private fun failure(message: String) = DemoSshSession(
        "", DemoSshStage.FAILED, null, null, emptyList(), message
    )

    private fun normaliseBase(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true)
        ) trimmed else "https://$trimmed"
    }

    private fun encodePathValue(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
