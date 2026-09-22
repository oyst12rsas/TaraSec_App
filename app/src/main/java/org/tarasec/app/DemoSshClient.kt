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

data class DemoGateway(
    val address: String,
    val name: String,
    val recognized: Boolean,
    val message: String = ""
)

data class DemoEligibility(
    val eligible: Boolean,
    val remediationRequired: Boolean,
    val demoResetAvailable: Boolean,
    val message: String
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
    val secondsRemaining: Int = 0,
    val nodeAObserved: Boolean = false,
    val unitMarked: Boolean = false,
    val nodeBObserved: Boolean = false,
    val nodeBLoginAccepted: Boolean? = null,
    val progressMessage: String = "",
    val message: String = ""
) : java.io.Serializable {
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

    fun observedGateway(baseUrl: String): DemoGateway {
        val reply = jsonRequest(baseUrl, "script/appDemoGateway.php", "GET")
        val json = reply.first ?: return DemoGateway("", "", false, reply.second)
        if (!json.optBoolean("ok", false)) {
            return DemoGateway("", "", false, json.optString("error", "Unable to identify gateway route"))
        }
        val gateway = json.optJSONObject("gateway")
            ?: return DemoGateway("", "", false, "Gateway identity missing")
        return DemoGateway(
            address = gateway.optString("address", "").trim(),
            name = gateway.optString("name", "TaraSec gateway").trim(),
            recognized = gateway.optBoolean("recognized", false)
        )
    }

    fun eligibility(baseUrl: String): DemoEligibility {
        val reply = jsonRequest(
            baseUrl,
            "script/appDemoSshSession.php?action=eligibility",
            "GET"
        )
        val json = reply.first ?: return DemoEligibility(
            eligible = false,
            remediationRequired = false,
            demoResetAvailable = false,
            message = reply.second.ifBlank { "Unable to confirm demo eligibility" }
        )
        if (!json.optBoolean("ok", false)) {
            return DemoEligibility(
                eligible = false,
                remediationRequired = json.optBoolean("remediation_required", false),
                demoResetAvailable = json.optBoolean("demo_reset_available", false),
                message = json.optString("error", "Unable to confirm demo eligibility")
            )
        }
        return DemoEligibility(
            eligible = json.optBoolean("eligible", false),
            remediationRequired = json.optString("next") == "remediation",
            demoResetAvailable = json.optBoolean("demo_reset_available", false),
            message = json.optString("message", "")
        )
    }

    fun gatewayEligibility(gatewayBaseUrl: String): DemoEligibility {
        val reply = jsonRequest(
            gatewayBaseUrl,
            "script/appLocalInfection.php",
            "GET"
        )
        val json = reply.first ?: return DemoEligibility(
            eligible = false,
            remediationRequired = false,
            demoResetAvailable = false,
            message = reply.second.ifBlank { "Unable to check the current gateway" }
        )
        if (!json.optBoolean("ok", false)) {
            return DemoEligibility(
                eligible = false,
                remediationRequired = false,
                demoResetAvailable = false,
                message = json.optString("error", "Unable to check the current gateway")
            )
        }
        val infected = json.optBoolean("infected", false)
        val resetAvailable = json.optBoolean("demo_reset_available", false)
        return DemoEligibility(
            eligible = !infected,
            remediationRequired = infected,
            demoResetAvailable = resetAvailable,
            message = json.optString(
                "message",
                if (infected) "This unit must be clean before Demo 2 can start"
                else "This unit may start the demonstration"
            )
        )
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
            secondsRemaining = json.optInt("expires_in", 0)
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
            secondsRemaining = json.optInt("seconds_remaining", current.secondsRemaining),
            nodeAObserved = json.optBoolean("node_a_observed", current.nodeAObserved),
            unitMarked = json.optBoolean("unit_marked", current.unitMarked),
            nodeBObserved = json.optBoolean("node_b_observed", current.nodeBObserved),
            nodeBLoginAccepted = if (json.isNull("node_b_login_accepted")) {
                null
            } else {
                json.optBoolean("node_b_login_accepted", false)
            },
            progressMessage = json.optString("progress_message", current.progressMessage),
            message = ""
        )
    }

    fun cancel(baseUrl: String, current: DemoSshSession): Pair<Boolean, String> {
        val body = JSONObject()
            .put("action", "cancel")
            .put("session_id", current.sessionId)
            .put("session_token", current.sessionToken)
        val reply = jsonRequest(baseUrl, "script/appDemoSshSession.php", "POST", body)
        val json = reply.first
            ?: return false to reply.second.ifBlank { "Unable to close Demo 2 session" }
        if (!json.optBoolean("ok", false)) {
            return false to json.optString("error", "Unable to close Demo 2 session")
        }
        return true to "Session closed on the DB server."
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
        sessionId = 0,
        sessionToken = "",
        state = "failed",
        nodeA = "",
        nodeAPort = 22,
        nodeB = "",
        nodeBPort = 22,
        username = "",
        password = "",
        message = message
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
