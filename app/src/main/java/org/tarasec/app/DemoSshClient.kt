package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class DemoSshSetup(
    val id: Int,
    val name: String,
    val nodeA: String,
    val nodeAPort: Int,
    val nodeB: String,
    val nodeBPort: Int,
    val expiresIn: Int
)

data class DemoSshSession(
    val sessionId: Int,
    val sessionToken: String,
    val state: String,
    val nodeA: String,
    val nodeAPort: Int,
    val nodeB: String,
    val nodeBPort: Int,
    val username: String,
    val password: String,
    val attempts: Int = 0,
    val expires: String = "",
    val message: String = ""
) {
    fun nodeACommand(): String = "ssh -p $nodeAPort demo@$nodeA"
    fun nodeBCommand(): String = "ssh -p $nodeBPort $username@$nodeB"
    fun terminal(): Boolean = state in setOf("cleared", "owner_clear_required", "expired")
}

/** Android client for the DB-authoritative SSH attribution demo. */
object DemoSshClient {
    fun setups(baseUrl: String): Pair<List<DemoSshSetup>, String> {
        val reply = jsonRequest(baseUrl, "script/appDemoConfiguration.php", "GET")
        val error = reply.second
        val json = reply.first ?: return emptyList<DemoSshSetup>() to error
        if (!json.optBoolean("ok", false)) {
            return emptyList<DemoSshSetup>() to json.optString("error", "Demo configuration unavailable")
        }
        val array = json.optJSONArray("demo_ssh_setups")
            ?: return emptyList<DemoSshSetup>() to "No SSH demo setups are configured"
        val result = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optInt("id", 0)
                val nodeA = item.optString("node_a", "").trim()
                val nodeB = item.optString("node_b", "").trim()
                if (id < 1 || nodeA.isBlank() || nodeB.isBlank()) continue
                add(
                    DemoSshSetup(
                        id = id,
                        name = item.optString("name", "SSH demo $id"),
                        nodeA = nodeA,
                        nodeAPort = item.optInt("node_a_port", 22),
                        nodeB = nodeB,
                        nodeBPort = item.optInt("node_b_port", 22),
                        expiresIn = item.optInt("expires_in", 180)
                    )
                )
            }
        }
        return result to if (result.isEmpty()) "No active SSH demo setups are configured" else ""
    }

    fun create(baseUrl: String, setupId: Int): DemoSshSession {
        val body = JSONObject().put("action", "create").put("setup_id", setupId)
        val reply = jsonRequest(baseUrl, "script/appDemoSshSession.php", "POST", body)
        val json = reply.first ?: return failure(reply.second)
        if (!json.optBoolean("ok", false)) return failure(json.optString("error", "Unable to start demo"))
        return DemoSshSession(
            sessionId = json.optInt("session_id", 0),
            sessionToken = json.optString("session_token", ""),
            state = json.optString("state", "awaiting_node_a"),
            nodeA = json.optString("node_a", ""),
            nodeAPort = json.optInt("node_a_port", 22),
            nodeB = json.optString("node_b", ""),
            nodeBPort = json.optInt("node_b_port", 22),
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            expires = json.optString("expires_in", "")
        )
    }

    fun status(baseUrl: String, current: DemoSshSession): DemoSshSession {
        val query = "script/appDemoSshSession.php?action=status&session_id=" +
            encode(current.sessionId.toString()) + "&session_token=" + encode(current.sessionToken)
        val reply = jsonRequest(baseUrl, query, "GET")
        val outer = reply.first ?: return current.copy(message = reply.second)
        if (!outer.optBoolean("ok", false)) {
            return current.copy(message = outer.optString("error", "Unable to refresh demo"))
        }
        val json = outer.optJSONObject("session") ?: return current.copy(message = "Invalid demo status")
        return current.copy(
            state = json.optString("state", current.state),
            nodeA = json.optString("node_a", current.nodeA),
            nodeAPort = json.optInt("node_a_port", current.nodeAPort),
            nodeB = json.optString("node_b", current.nodeB),
            nodeBPort = json.optInt("node_b_port", current.nodeBPort),
            username = json.optString("username", current.username),
            attempts = json.optInt("attempts", current.attempts),
            expires = json.optString("expires", current.expires),
            message = ""
        )
    }

    private fun jsonRequest(
        baseUrl: String,
        path: String,
        method: String,
        body: JSONObject? = null
    ): Pair<JSONObject?, String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("${normaliseBase(baseUrl)}/$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json == null) null to "Invalid demo response (HTTP $code)"
            else if (code !in 200..299) json to json.optString("error", "Demo returned HTTP $code")
            else json to ""
        } catch (error: Exception) {
            null to (error.message ?: "Could not contact demo server")
        } finally {
            connection?.disconnect()
        }
    }

    private fun failure(message: String) = DemoSshSession(
        0, "", "failed", "", 22, "", 22, "", "", message = message
    )

    private fun normaliseBase(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true)
        ) trimmed else "http://$trimmed"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
