package org.tarasec.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class AccountServices(val identity: String, val subscriber: String) {
    val namespace: String get() = if (this == ServiceDiscovery.central) "" else
        MessageDigest.getInstance("SHA-256").digest("$identity|$subscriber".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun key(name: String): String = if (namespace.isEmpty()) name else "$name-$namespace"
}

object ServiceDiscovery {
    val central = AccountServices("https://tarasec.org/api/v1/identity", "https://tarasec.org/api/v1/subscriber")
    private const val PREFS = "tarasec_account_services_v1"

    private fun apiBase(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
            "Account services require a valid HTTPS address"
        }
        return input.trim().trimEnd('/')
    }

    fun parse(json: JSONObject): AccountServices {
        val identity = apiBase(json.getString("identity_api_base"))
        val subscriber = apiBase(json.getString("subscriber_api_base"))
        val a = URI(identity); val b = URI(subscriber)
        require(a.host.equals(b.host, true) && (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port)) {
            "Identity and subscriber services must share an HTTPS origin"
        }
        return AccountServices(identity, subscriber)
    }

    fun selected(context: Context): AccountServices {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identity = prefs.getString("identity", null) ?: return central
        val subscriber = prefs.getString("subscriber", null) ?: error("Saved service configuration is incomplete")
        return parse(JSONObject().put("identity_api_base", identity).put("subscriber_api_base", subscriber))
    }

    fun select(context: Context, services: AccountServices) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("identity", services.identity).putString("subscriber", services.subscriber).apply()
    }

    fun rememberSignIn(context: Context, destination: String? = null): AccountServices {
        val services = selected(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending_identity", services.identity).putString("pending_subscriber", services.subscriber)
            .putString("pending_destination", destination).apply()
        return services
    }

    fun signInDestination(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pending_destination", null)

    fun signInServices(context: Context): AccountServices {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identity = prefs.getString("pending_identity", null) ?: return selected(context)
        val subscriber = prefs.getString("pending_subscriber", null) ?: error("Sign-in service configuration is incomplete")
        val services = parse(JSONObject().put("identity_api_base", identity).put("subscriber_api_base", subscriber))
        select(context, services)
        prefs.edit().remove("pending_identity").remove("pending_subscriber").remove("pending_destination").apply()
        return services
    }

    private fun isNetBird(host: String): Boolean {
        val octets = host.split('.')
        return octets.size == 4 && octets[0] == "100" && octets[1] == "68" &&
            octets.all { it.matches(Regex("[0-9]{1,3}")) && (it.toIntOrNull() ?: -1) in 0..255 }
    }

    // This request is discovery only: no account token, password or ticket.
    fun discover(gatewayInput: String): AccountServices {
        var address = gatewayInput.trim()
        require(address.isNotBlank()) { "Enter the gateway IP address" }
        if (!address.contains("://")) {
            val host = URI("http://$address").host.orEmpty()
            address = (if (isNetBird(host)) "http://" else "https://") + address
        }
        val uri = URI(address)
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port in 1..65535)) { "Enter only the gateway IP or origin" }
        require(uri.scheme == "https" || (uri.scheme == "http" && isNetBird(uri.host))) { "Discovery requires HTTPS or a NetBird gateway address" }
        val connection = URL(address.trimEnd('/') + "/script/appServices.php").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            val code = connection.responseCode
            // An old gateway without discovery has no advertised local services.
            if (code == 404 || code == 410) return central
            check(code in 200..299) { "Gateway service discovery failed (HTTP $code). Provider was not changed." }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(json.optBoolean("ok") && json.optString("service") == "tarasec" && json.optInt("version") == 1) { "Unsupported gateway service discovery reply" }
            return parse(json.getJSONObject("account_services"))
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Gateway service discovery unreachable. Provider was not changed.", e)
        } finally { connection.disconnect() }
    }
}
