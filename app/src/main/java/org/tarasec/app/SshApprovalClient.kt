package org.tarasec.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

data class SshApprovalTarget(val base: String, val serverRef: String) {
    init {
        val uri = URI(base)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.endsWith("/") &&
            uri.normalize().path == uri.path && serverRef.matches(Regex("[a-f0-9]{64}"))) {
            "This installation has no valid enrolled SSH approval identity."
        }
    }
    fun browserUrl(id: String? = null): String {
        require(id == null || id.matches(Regex("[a-f0-9]{32}")))
        return base + "?server=" + serverRef + "&locked=1" +
            (id?.let { "&mobile_request=$it" } ?: "")
    }
    fun managementBrowserUrl(id: String): String = browserUrl(id).replace(base + "?", base + "management.php?")
}
data class SshAppPairing(val id: String, val verifier: String) {
    override fun toString() = "SshAppPairing(verifier=omitted)"
}
data class SshAppGrant(val token: String, val expiresAt: Long) {
    override fun toString() = "SshAppGrant(token=omitted)"
}

class SshSignInRequiredException : IllegalStateException("Sign in with Google again.")

object SshApprovalClient {
    private fun request(url: String, body: JSONObject? = null, token: String? = null, accountToken: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000; c.readTimeout = 8000; c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            accountToken?.let { c.setRequestProperty("X-TaraSec-Subscriber-Token", it) }
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val reader = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()
            val text = reader?.use {
                val out = StringBuilder(); val chars = CharArray(4096)
                while (true) {
                    val n = it.read(chars); if (n < 0) break
                    require(out.length + n <= 262144) { "Approval response is too large." }
                    out.append(chars, 0, n)
                }
                out.toString()
            }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("The approval service returned an unexpected response (HTTP $code).")
            }
            if (code !in 200..299 || !json.optBoolean("ok")) {
                if (json.optString("error") in listOf("app_login_required", "operator_login_required"))
                    throw SshSignInRequiredException()
                throw IllegalStateException(when (json.optString("error")) {
                    "app_login_required", "operator_login_required" -> "Sign in with Google again."
                    "account_sign_in_required" -> "Your account cannot authorize SSH yet. Connect your Google account below."
                    "account_service_unavailable" -> "Account verification is unavailable. Retry or connect through the browser."
                    "authenticator_not_configured" -> "Set up your Authenticator on the secure sign-in page first."
                    "six_digit_code_required" -> "Enter the six-digit Authenticator code."
                    "invalid_code_or_proposal_not_pending" -> "The code was not accepted, or this request is no longer pending. Refresh and try a new code."
                    "authenticator_temporarily_limited" -> "Too many code attempts. Wait five minutes."
                    "app_wrong_node", "app_wrong_node_or_operation", "selected_node_unavailable_or_unauthorized",
                    "selected_node_unavailable_or_ambiguous" -> "The selected installation is unavailable or not authorized. No other node was selected."
                    "app_login_expired_or_invalid", "app_login_expired_or_wrong_node" -> "App sign-in expired. Start Google sign-in again."
                    else -> "SSH approval failed (HTTP $code). Check enrollment, access and connectivity."
                })
            }
            return json
        } finally { c.disconnect() }
    }

    fun discover(gatewayBase: String): SshApprovalTarget {
        val json = request(gatewayBase.trimEnd('/') + "/script/managerSsh.php")
        val value = json.optJSONObject("approvalTarget")
            ?: error("This node has not published its SSH approval identity. Update its approval installer or enroll it before opening SSH.")
        return SshApprovalTarget(value.getString("approval_url"), value.getString("server_ref"))
    }
    private fun readGrant(target: SshApprovalTarget, json: JSONObject): SshAppGrant {
        require(json.optString("server_ref") == target.serverRef) { "Sign-in returned a different node." }
        val token = json.getString("token")
        val expiry = json.getLong("expires_at")
        val now = System.currentTimeMillis() / 1000
        require(token.matches(Regex("[a-f0-9]{64}")) && expiry > now && expiry <= now + 1860) { "Invalid app sign-in response." }
        return SshAppGrant(token, expiry)
    }
    fun start(target: SshApprovalTarget, binding: String): SshAppPairing {
        require(binding.matches(Regex("[a-f0-9]{64}"))) { "Invalid management challenge." }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val json = request(target.base + "mobile.php?action=start",
            JSONObject().put("server_ref", target.serverRef).put("challenge", challenge).put("binding", binding))
        val id = json.getString("id")
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid app sign-in response." }
        return SshAppPairing(id, verifier)
    }
    fun exchange(target: SshApprovalTarget, pairing: SshAppPairing): SshAppGrant? {
        val json = request(target.base + "mobile.php?action=exchange",
            JSONObject().put("id", pairing.id).put("verifier", pairing.verifier))
        if (json.optBoolean("pending")) return null
        return readGrant(target, json)
    }
    fun status(target: SshApprovalTarget, grant: SshAppGrant): JSONObject {
        val json = request(target.base + "mobile.php?action=list&server=" + target.serverRef, token = grant.token)
        val checks = json.getJSONArray("checks")
        require(json.optString("selected_server") == target.serverRef &&
            checks.length() == 1 && checks.getJSONObject(0).getString("server_ref") == target.serverRef) {
            "The approval service returned a different node. SSH controls are unavailable."
        }
        return json
    }
    fun open(target: SshApprovalTarget, grant: SshAppGrant, seconds: Int, code: String): String {
        require(seconds in listOf(300, 600, 900) && code.matches(Regex("[0-9]{6}")))
        val request = request(target.base + "mobile.php?action=request_ssh",
            JSONObject().put("csrf", grant.token).put("server_ref", target.serverRef), grant.token)
        val id = request.getString("id")
        require(id.matches(Regex("[a-f0-9]{32}")))
        request(target.base + "mobile.php?action=decide",
            JSONObject().put("csrf", grant.token).put("id", id).put("decision", "approve")
                .put("duration_seconds", seconds).put("totp", code), grant.token)
        return id
    }
}
