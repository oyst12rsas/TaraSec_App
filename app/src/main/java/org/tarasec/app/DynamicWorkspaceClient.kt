package org.tarasec.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DynamicWorkspaceManifest(
    val title: String,
    val generatedAt: String?,
    val expiresAt: String?,
    val elements: List<JSONObject>,
    val cached: Boolean
)

data class WorkspaceChatMessage(val role: String, val text: String)

data class WorkspaceActionResult(
    val message: String,
    val receipt: String?,
    val refresh: Boolean
)

data class WorkspaceReceiptStatus(
    val receipt: String,
    val status: String,
    val message: String,
    val reviewerMessage: String?,
    val receivedAt: String?,
    val updatedAt: String?,
    val meritAwarded: Boolean
)

object DynamicWorkspaceClient {
    private const val WORKSPACE_URL = "https://tarasec.org/api/v1/app/workspace.php"
    private const val PREFS = "tarasec_dynamic_workspace"
    private const val CACHE_KEY = "last_valid_manifest"
    private const val RECEIPT_KEY = "latest_contribution_receipt"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_ELEMENTS = 150

    private val allowedTypes = setOf(
        "heading", "text", "ai_text", "card", "status", "list",
        "text_input", "select", "button", "qr_code", "chat", "divider"
    )
    private val allowedActionTypes = setOf("none", "open_url", "show_message", "refresh", "submit")
    private val allowedHosts = setOf("tarasec.org", "www.tarasec.org", "github.com")
    private val allowedChatEndpoints = setOf("/api/v1/id-chat/chat.php")
    private val allowedSubmitEndpoints = setOf("/api/v1/app/action.php")
    private const val RECEIPT_STATUS_ENDPOINT = "/api/v1/app/status.php"

    fun load(context: Context): DynamicWorkspaceManifest {
        return try {
            val raw = get(WORKSPACE_URL)
            val parsed = parse(raw, cached = false)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(CACHE_KEY, raw).apply()
            parsed
        } catch (remoteError: Exception) {
            val cached = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null)
            if (cached.isNullOrBlank()) throw remoteError
            parse(cached, cached = true)
        }
    }

    fun postChat(endpoint: String, messages: List<WorkspaceChatMessage>): String {
        require(endpoint in allowedChatEndpoints) { "Chat endpoint is not approved" }
        val safeMessages = messages.takeLast(20).map {
            require(it.role == "user" || it.role == "assistant") { "Invalid chat role" }
            JSONObject().put("role", it.role).put("text", it.text.take(4000))
        }
        require(safeMessages.isNotEmpty() && safeMessages.last().getString("role") == "user") {
            "A user message is required"
        }
        val payload = JSONObject().put("messages", JSONArray(safeMessages)).toString()
        val connection = URL("https://tarasec.org$endpoint").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 45_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("TaraSec AI returned HTTP $code")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(
                    json.optString("reason", "TaraSec AI returned HTTP $code").replace('_', ' ')
                )
            }
            json.optString("reply").trim().ifBlank {
                throw IllegalStateException("TaraSec AI returned an empty reply")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun submitAction(
        endpoint: String,
        actionId: String,
        fields: Map<String, String>
    ): WorkspaceActionResult {
        require(endpoint in allowedSubmitEndpoints) { "Submission endpoint is not approved" }
        require(actionId.matches(Regex("^[a-z0-9_]{1,64}$"))) { "Invalid workspace action" }
        require(fields.size <= 20) { "Too many submission fields" }
        val safeFields = JSONObject()
        fields.forEach { (id, value) ->
            require(id.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) { "Invalid field ID" }
            safeFields.put(id, value.take(4000))
        }
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("action_id", actionId)
            .put("fields", safeFields)
            .toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= 16 * 1024) {
            "Submission is too large"
        }
        val connection = URL("https://tarasec.org$endpoint").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("TaraSec returned HTTP $code")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(
                    json.optString("reason", "TaraSec returned HTTP $code").replace('_', ' ')
                )
            }
            WorkspaceActionResult(
                message = json.optString("message", "Submission received"),
                receipt = json.optString("receipt").takeIf { it.isNotBlank() },
                refresh = json.optBoolean("refresh", false)
            )
        } finally {
            connection.disconnect()
        }
    }

    fun rememberReceipt(context: Context, receipt: String) {
        require(receipt.matches(Regex("^[a-f0-9]{24}$"))) { "Invalid receipt" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(RECEIPT_KEY, receipt).apply()
    }

    fun latestReceipt(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(RECEIPT_KEY, null)
            ?.takeIf { it.matches(Regex("^[a-f0-9]{24}$")) }

    fun getReceiptStatus(receipt: String): WorkspaceReceiptStatus {
        require(receipt.matches(Regex("^[a-f0-9]{24}$"))) { "Invalid receipt" }
        val encoded = java.net.URLEncoder.encode(receipt, Charsets.UTF_8.name())
        val connection = URL("https://tarasec.org$RECEIPT_STATUS_ENDPOINT?receipt=$encoded")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("Receipt status returned HTTP $code")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(
                    json.optString("reason", "Receipt status returned HTTP $code").replace('_', ' ')
                )
            }
            WorkspaceReceiptStatus(
                receipt = json.getString("receipt"),
                status = json.getString("status"),
                message = json.optString("message", "Status received"),
                reviewerMessage = json.optString("reviewer_message").takeIf { it.isNotBlank() },
                receivedAt = json.optString("received_at").takeIf { it.isNotBlank() },
                updatedAt = json.optString("updated_at").takeIf { it.isNotBlank() },
                meritAwarded = json.optBoolean("merit_awarded", false)
            )
        } finally {
            connection.disconnect()
        }
    }

    fun approvedExternalUrl(raw: String): UriResult {
        return runCatching {
            val url = URL(raw)
            require(url.protocol == "https") { "Only HTTPS links are allowed" }
            require(url.host.lowercase() in allowedHosts) { "This link is not approved" }
            UriResult.Valid(raw)
        }.getOrElse { UriResult.Invalid(it.message ?: "Invalid link") }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Workspace returned HTTP $code")
            if (body.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                throw IllegalStateException("Workspace response is too large")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(raw: String, cached: Boolean): DynamicWorkspaceManifest {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw IllegalStateException("Workspace response is too large")
        }
        val json = JSONObject(raw)
        require(json.optInt("schema_version", -1) == 1) { "Unsupported workspace schema" }
        require(json.optString("screen_id").isNotBlank()) { "Workspace screen ID is missing" }
        val array = json.optJSONArray("elements") ?: throw IllegalArgumentException("Workspace elements are missing")
        var count = 0
        fun validate(elements: JSONArray, depth: Int) {
            require(depth <= 4) { "Workspace nesting is too deep" }
            for (index in 0 until elements.length()) {
                val item = elements.optJSONObject(index)
                    ?: throw IllegalArgumentException("Invalid workspace element")
                count++
                require(count <= MAX_ELEMENTS) { "Workspace has too many elements" }
                val type = item.optString("type")
                require(type in allowedTypes) { "Unsupported workspace element: $type" }
                if (type == "button") {
                    val action = item.optJSONObject("action") ?: JSONObject().put("type", "none")
                    val actionType = action.optString("type", "none")
                    require(actionType in allowedActionTypes) { "Unsupported workspace action" }
                    if (actionType == "open_url") {
                        require(approvedExternalUrl(action.optString("url")) is UriResult.Valid) {
                            "Workspace contains an unapproved link"
                        }
                    }
                    if (actionType == "submit") {
                        require(action.optString("endpoint") in allowedSubmitEndpoints) {
                            "Workspace contains an unapproved submission endpoint"
                        }
                        require(action.optString("action_id").matches(Regex("^[a-z0-9_]{1,64}$"))) {
                            "Workspace contains an invalid action ID"
                        }
                        val fields = action.optJSONArray("fields")
                            ?: throw IllegalArgumentException("Workspace submission fields are missing")
                        require(fields.length() <= 20) { "Workspace submission has too many fields" }
                        for (fieldIndex in 0 until fields.length()) {
                            require(fields.optString(fieldIndex).matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) {
                                "Workspace submission contains an invalid field ID"
                            }
                        }
                    }
                }
                if (type == "chat") {
                    require(item.optString("endpoint") in allowedChatEndpoints) {
                        "Workspace contains an unapproved chat endpoint"
                    }
                }
                item.optJSONArray("elements")?.let { validate(it, depth + 1) }
            }
        }
        validate(array, 0)
        return DynamicWorkspaceManifest(
            title = json.optString("title", "My TaraSec workspace"),
            generatedAt = json.optString("generated_at").takeIf { it.isNotBlank() },
            expiresAt = json.optString("expires_at").takeIf { it.isNotBlank() },
            elements = List(array.length()) { array.getJSONObject(it) },
            cached = cached
        )
    }
}

sealed class UriResult {
    data class Valid(val url: String) : UriResult()
    data class Invalid(val reason: String) : UriResult()
}
