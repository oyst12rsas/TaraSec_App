package org.tarasec.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GithubVerificationChallenge(
    val challengeId: String,
    val token: String,
    val marker: String,
    val issueUrl: String,
    val expiresAt: String
)

data class GithubVerificationResult(
    val verified: Boolean,
    val githubUserId: Long?,
    val githubLogin: String?,
    val commentUrl: String?,
    val verifiedAt: String?,
    val message: String
)

object GithubVerificationClient {
    private const val ENDPOINT = "https://tarasec.org/api/v1/app/github-verification.php"
    private const val PREFS = "tarasec_github_verification"

    fun savedChallenge(context: Context): GithubVerificationChallenge? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val challengeId = prefs.getString("challenge_id", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val marker = prefs.getString("marker", null) ?: return null
        val issueUrl = prefs.getString("issue_url", null) ?: return null
        val expiresAt = prefs.getString("expires_at", null) ?: return null
        if (!challengeId.matches(Regex("^[a-f0-9]{24}$"))
            || !token.matches(Regex("^[a-f0-9]{32}$"))) return null
        return GithubVerificationChallenge(challengeId, token, marker, issueUrl, expiresAt)
    }

    fun clearSavedChallenge(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun start(context: Context): GithubVerificationChallenge {
        val json = post(JSONObject().put("action", "start"))
        val challenge = GithubVerificationChallenge(
            challengeId = json.getString("challenge_id"),
            token = json.getString("token"),
            marker = json.getString("marker"),
            issueUrl = json.getString("issue_url"),
            expiresAt = json.getString("expires_at")
        )
        require(challenge.challengeId.matches(Regex("^[a-f0-9]{24}$")))
        require(challenge.token.matches(Regex("^[a-f0-9]{32}$")))
        require(challenge.marker == "tarasec-verify:v1:" + challenge.token)
        require(DynamicWorkspaceClient.approvedExternalUrl(challenge.issueUrl) is UriResult.Valid)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("challenge_id", challenge.challengeId)
            .putString("token", challenge.token)
            .putString("marker", challenge.marker)
            .putString("issue_url", challenge.issueUrl)
            .putString("expires_at", challenge.expiresAt)
            .apply()
        return challenge
    }

    fun check(challenge: GithubVerificationChallenge): GithubVerificationResult {
        val json = post(
            JSONObject()
                .put("action", "check")
                .put("challenge_id", challenge.challengeId)
                .put("token", challenge.token)
        )
        val verified = json.optBoolean("verified", false)
        return GithubVerificationResult(
            verified = verified,
            githubUserId = if (verified) json.optLong("github_user_id") else null,
            githubLogin = json.optString("github_login").takeIf { it.isNotBlank() },
            commentUrl = json.optString("comment_url").takeIf { it.isNotBlank() },
            verifiedAt = json.optString("verified_at").takeIf { it.isNotBlank() },
            message = if (verified) {
                "GitHub account control verified."
            } else {
                json.optString("reason", "Verification comment not found").replace('_', ' ')
            }
        )
    }

    private fun post(payload: JSONObject): JSONObject {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("GitHub verification returned HTTP " + code)
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(
                    json.optString("reason", "GitHub verification returned HTTP " + code)
                        .replace('_', ' ')
                )
            }
            json
        } finally {
            connection.disconnect()
        }
    }
}
