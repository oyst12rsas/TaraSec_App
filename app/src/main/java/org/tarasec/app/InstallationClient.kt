package org.tarasec.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class InstallationThreatState(
    val installationId: String,
    val reachable: Boolean,
    val authenticated: Boolean,
    val infected: Boolean,
    val severity: Int,
    val activeInfections: Int,
    val recentReports: Int,
    val summary: String,
    val checkedAt: String,
    val error: String = ""
)

object InstallationClient {
    fun ensureManagerSession(context: Context, installation: TaraSecInstallation): Boolean {
        if (sessionActive(installation.managementBaseUrl)) return true
        val credential = SecureCredentialStore.get(context, installation.id) ?: return false
        return login(installation.managementBaseUrl, credential)
    }

    fun login(baseUrl: String, credential: String): Boolean {
        val c = URL("${baseUrl.trimEnd('/')}/script/managerAuth.php").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.useCaches = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = "action=login&key=${URLEncoder.encode(credential, Charsets.UTF_8.name())}"
            c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val json = readJson(c)
            return json.optBoolean("ok", false) && json.optBoolean("authenticated", false)
        } catch (_: Exception) {
            return false
        } finally {
            c.disconnect()
        }
    }

    fun sessionActive(baseUrl: String): Boolean {
        val c = URL("${baseUrl.trimEnd('/')}/script/managerAuth.php?action=session").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 5000
            c.useCaches = false
            val json = readJson(c)
            return json.optBoolean("ok", false) && json.optBoolean("authenticated", false)
        } catch (_: Exception) {
            return false
        } finally {
            c.disconnect()
        }
    }

    fun pollThreat(context: Context, installation: TaraSecInstallation): InstallationThreatState {
        val authenticated = ensureManagerSession(context, installation)
        if (!authenticated) {
            return InstallationThreatState(
                installationId = installation.id,
                reachable = true,
                authenticated = false,
                infected = false,
                severity = 0,
                activeInfections = 0,
                recentReports = 0,
                summary = "Manager authentication required",
                checkedAt = "",
                error = "manager_auth_required"
            )
        }

        val c = URL("${installation.managementBaseUrl.trimEnd('/')}/script/managerThreat.php").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.useCaches = false
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return InstallationThreatState(
                    installation.id, true, false, false, 0, 0, 0,
                    "Threat status unavailable", "", "HTTP $code: $body"
                )
            }
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            return InstallationThreatState(
                installationId = installation.id,
                reachable = true,
                authenticated = json.optBoolean("ok", false),
                infected = json.optBoolean("infected", false),
                severity = json.optInt("severity", 0),
                activeInfections = json.optInt("activeInfections", 0),
                recentReports = json.optInt("recentReports", 0),
                summary = json.optString("summary", ""),
                checkedAt = json.optString("server_time", ""),
                error = json.optString("error", "")
            )
        } catch (e: Exception) {
            return InstallationThreatState(
                installation.id, false, authenticated, false, 0, 0, 0,
                "Installation unreachable", "", e.message ?: e.javaClass.simpleName
            )
        } finally {
            c.disconnect()
        }
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
