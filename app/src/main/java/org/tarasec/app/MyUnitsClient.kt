package org.tarasec.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class LinkedUnit(val key: String, val gateway: String, val gatewayId: String, val unitId: Long, val ownerId: Long?, val name: String, val token: String) {
    override fun toString() = "LinkedUnit(key=$key, token=omitted)"
}

object MyUnitsClient {
    private fun storeKey(accountId: Long) = "my-units-v1-$accountId"
    fun accountId(context: Context): Long? = SecureCredentialStore.get(context, "subscriber-account-id")?.toLongOrNull()

    fun gatewayOrigin(input: String): String {
        val address = input.trim()
        require(address.isNotEmpty()) { "Enter the gateway IP address" }
        val normalized = if (address.contains("://")) address else "https://$address"
        val uri = try { URI(normalized) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid gateway IP address") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "Enter the gateway IP address, without a path, login or query"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid gateway port" }
        return "https://${if (uri.host.contains(':')) uri.host.let { if (it.startsWith("[")) it else "[$it]" } else uri.host.lowercase()}${if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"}"
    }

    fun load(context: Context, accountId: Long): List<LinkedUnit> {
        val raw = SecureCredentialStore.get(context, storeKey(accountId)) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            LinkedUnit(o.getString("key"), o.getString("gateway"), o.optString("gatewayId"), o.getLong("unitId"), if (o.isNull("ownerId")) null else o.getLong("ownerId"), o.getString("name"), o.getString("token"))
        }
    }

    fun save(context: Context, accountId: Long, units: List<LinkedUnit>) {
        val array = JSONArray()
        units.forEach { u -> array.put(JSONObject().put("key",u.key).put("gateway",u.gateway).put("gatewayId",u.gatewayId).put("unitId",u.unitId).put("ownerId",u.ownerId ?: JSONObject.NULL).put("name",u.name).put("token",u.token)) }
        SecureCredentialStore.put(context, storeKey(accountId), array.toString())
    }

    private fun clientId(context: Context, accountId: Long): String {
        val key = "my-units-client-$accountId"
        return SecureCredentialStore.get(context,key) ?: UUID.randomUUID().toString().replace("-", "").also { SecureCredentialStore.put(context,key,it) }
    }

    private fun form(vararg values: Pair<String,String>) = values.joinToString("&") { (k,v) -> "${URLEncoder.encode(k,"UTF-8")}=${URLEncoder.encode(v,"UTF-8")}" }

    private fun request(url: String, body: String? = null, subscriber: String? = null, unitToken: String? = null): JSONObject {
        val endpoint = URL(url)
        val service = if (subscriber != null) "Identity service" else "Gateway"
        val origin = "${endpoint.protocol}://${endpoint.host}${if (endpoint.port == -1) "" else ":${endpoint.port}"}"
        val c = endpoint.openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 15000; c.instanceFollowRedirects = false; c.useCaches = false
        try {
            subscriber?.let { c.setRequestProperty("X-TaraSec-Subscriber-Token",it) }
            unitToken?.let { c.setRequestProperty("Authorization","Bearer $it") }
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("Authorization expired, revoked or unavailable. Sign in with Google and sync again.")
            if (code == 404 || code == 405) throw IllegalStateException("This gateway does not provide the required unit API. Ask its operator to upgrade it.")
            if (code == 429) throw IllegalStateException("Too many sync requests. Retry in a minute.")
            if (code !in 200..299) throw IllegalStateException("Service unavailable (HTTP $code). Status is unknown.")
            val raw = c.inputStream.bufferedReader().use { it.readText() }
            val json = try { JSONObject(raw) } catch (_: Exception) { throw IllegalStateException("The service returned an unsupported reply. Status is unknown.") }
            if (!json.optBoolean("ok")) throw IllegalStateException("The service could not complete the request.")
            return json
        } catch (e: java.io.IOException) {
            val detail = when (e) {
                is javax.net.ssl.SSLException -> "HTTPS failed. Check that the service has a trusted certificate valid for this address."
                is java.net.SocketTimeoutException -> "Connection timed out. Check VPN routing, firewall and service availability."
                is java.net.UnknownHostException -> "Address could not be resolved. Check the service address."
                is java.net.ConnectException -> "Connection failed. Check that the HTTPS service is listening and reachable through the VPN."
                else -> "Connection failed. Check VPN routing and service availability."
            }
            throw IllegalStateException("$service at $origin: $detail Status is unknown.")
        } finally { c.disconnect() }
    }

    private fun ticket(context: Context, gatewayId: String): String {
        val token = SubscriberAccountClient.storedToken(context) ?: throw IllegalStateException("Sign in with Google first")
        return request("https://tarasec.org/api/v1/identity/unit-identity.php",form("gateway_id" to gatewayId),subscriber=token).getString("ticket")
    }

    fun sync(context: Context, accountId: Long, input: String): List<LinkedUnit> {
        require(accountId(context) == accountId) { "Account changed. Reopen My units." }
        val base = gatewayOrigin(input)
        val meta = request("$base/script/unitLinked.php")
        val gatewayId = meta.getString("gateway_id")
        require(Regex("[a-f0-9]{32}").matches(gatewayId) && gatewayOrigin(meta.getString("base_url")) == base) { "Gateway identity or address does not match its configuration" }
        val json = request("$base/script/unitLinked.php",form("ticket" to ticket(context,gatewayId),"client_id" to clientId(context,accountId),"action" to "list"))
        require(json.getString("gateway_id") == gatewayId) { "Gateway identity changed" }
        val old = load(context,accountId)
        val array = json.getJSONArray("units")
        val incoming = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            require(o.getString("scope") == "single_unit_read_only") { "Unsupported unit access scope" }
            val unitId = o.getLong("unitId"); val token = o.getString("token")
            require(unitId > 0 && Regex("[a-f0-9]{64}").matches(token)) { "Invalid unit credential" }
            val key = "$gatewayId:$unitId"
            LinkedUnit(key,base,gatewayId,unitId,if (o.isNull("ownerId")) null else o.getLong("ownerId"),old.firstOrNull { it.key == key }?.name ?: o.optString("hostname").ifBlank { "Unit $unitId" },token)
        }
        require(accountId(context) == accountId) { "Account changed. Reopen My units." }
        return (old.filter { it.gatewayId != gatewayId && it.gateway != base } + incoming).also { save(context,accountId,it) }
    }

    fun status(unit: LinkedUnit): JSONObject {
        val json = request("${unit.gateway}/script/unitStatus.php",unitToken=unit.token)
        val identity = json.getJSONObject("unit")
        require(json.getString("scope") == "single_unit_read_only" && identity.getLong("unitId") == unit.unitId && (unit.ownerId == null || identity.optLong("ownerId") == unit.ownerId)) { "Status does not match the linked unit" }
        val threat = json.getJSONObject("threat")
        threat.getBoolean("warning"); threat.getBoolean("confirmedLocalInfection"); threat.getInt("severity")
        java.time.OffsetDateTime.parse(json.getString("server_time"))
        return json
    }

    fun unlink(context: Context, accountId: Long, unit: LinkedUnit) {
        require(unit.gatewayId.isNotBlank()) { "For a manual pairing, ask the gateway operator to revoke its token" }
        request("${unit.gateway}/script/unitLinked.php",form("action" to "unlink","unit_id" to unit.unitId.toString(),"ticket" to ticket(context,unit.gatewayId)))
        save(context,accountId,load(context,accountId).filter { it.key != unit.key })
    }

    fun importPairing(context: Context, accountId: Long, input: String, gatewayInput: String): List<LinkedUnit> {
        require(input.length <= 32768) { "Pairing data is too large" }
        val json = try { JSONObject(input) } catch (_: Exception) { throw IllegalArgumentException("Invalid pairing JSON") }
        require(json.optBoolean("ok") && json.getString("scope") == "single_unit_read_only") { "Unsupported pairing" }
        val base = gatewayOrigin(gatewayInput)
        val identity = json.getJSONObject("unit"); val unitId = identity.getLong("unitId"); val token = json.getString("token")
        require(unitId > 0 && Regex("[a-fA-F0-9]{64}").matches(token)) { "Invalid pairing credential" }
        val unit = LinkedUnit("manual:$base:$unitId",base,"",unitId,if (identity.isNull("ownerId")) null else identity.getLong("ownerId"),identity.optString("hostname").ifBlank { "Unit $unitId" },token.lowercase())
        status(unit) // Validate the endpoint and credential before saving.
        return (load(context,accountId).filter { it.gateway != base || it.unitId != unitId } + unit).also { save(context,accountId,it) }
    }
}
