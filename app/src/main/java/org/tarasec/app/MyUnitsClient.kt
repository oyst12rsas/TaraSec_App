package org.tarasec.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class LinkedUnit(val key: String, val gateway: String, val gatewayId: String, val unitId: Long, val ownerId: Long?, val name: String, val token: String, val identityApi: String = ServiceDiscovery.central.identity) {
    override fun toString() = "LinkedUnit(key=$key, token=omitted)"
}

object MyUnitsClient {
    fun accountId(context: Context): Long? = SecureCredentialStore.get(context, ServiceDiscovery.selected(context).key("subscriber-account-id"))?.toLongOrNull()

    fun gatewayOrigin(input: String): String = UnitGatewayTransport.origin(input)

    fun gatewayMetadata(context: Context, input: String): JSONObject {
        val base = gatewayOrigin(input)
        val meta = request(context,"$base/script/unitLinked.php")
        require(Regex("[a-f0-9]{32}").matches(meta.getString("gateway_id")) && gatewayOrigin(meta.getString("base_url")) == base) { "Gateway identity or address does not match its configuration" }
        require(meta.getString("link_path") == "/script/unitLink.php") { "Unsupported node linking page" }
        if (base.startsWith("http://")) require(meta.optString("link_mode") == "service_handoff" && meta.optString("transport") == "netbird") {
            "This gateway has not enabled encrypted VPN linking. Ask its operator to configure hosted linking."
        }
        return meta
    }

    fun load(context: Context, accountId: Long, services: AccountServices = ServiceDiscovery.selected(context)): List<LinkedUnit> {
        val raw = SecureCredentialStore.get(context, services.key("my-units-v1-$accountId")) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            LinkedUnit(o.getString("key"), o.getString("gateway"), o.optString("gatewayId"), o.getLong("unitId"), if (o.isNull("ownerId")) null else o.getLong("ownerId"), o.getString("name"), o.getString("token"), o.optString("identityApi",ServiceDiscovery.central.identity))
        }
    }

    fun save(context: Context, accountId: Long, units: List<LinkedUnit>, services: AccountServices = ServiceDiscovery.selected(context)) {
        val array = JSONArray()
        units.forEach { u -> array.put(JSONObject().put("key",u.key).put("gateway",u.gateway).put("gatewayId",u.gatewayId).put("unitId",u.unitId).put("ownerId",u.ownerId ?: JSONObject.NULL).put("name",u.name).put("token",u.token).put("identityApi",u.identityApi)) }
        SecureCredentialStore.put(context, services.key("my-units-v1-$accountId"), array.toString())
    }

    private fun clientId(context: Context, accountId: Long): String {
        val key = ServiceDiscovery.selected(context).key("my-units-client-$accountId")
        return SecureCredentialStore.get(context,key) ?: UUID.randomUUID().toString().replace("-", "").also { SecureCredentialStore.put(context,key,it) }
    }

    private fun form(vararg values: Pair<String,String>) = values.joinToString("&") { (k,v) -> "${URLEncoder.encode(k,"UTF-8")}=${URLEncoder.encode(v,"UTF-8")}" }

    private fun request(context: Context, url: String, body: String? = null, subscriber: String? = null, unitToken: String? = null): JSONObject {
        val endpoint = URL(url)
        val service = if (subscriber != null) "Identity service" else "Gateway"
        val origin = "${endpoint.protocol}://${endpoint.host}${if (endpoint.port == -1) "" else ":${endpoint.port}"}"
        val c = UnitGatewayTransport.connection(context, endpoint)
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
                is java.net.ConnectException -> "Connection failed. Check that the gateway service is listening and reachable through the VPN."
                else -> "Connection failed. Check VPN routing and service availability."
            }
            throw IllegalStateException("$service at $origin: $detail Status is unknown.")
        } finally { c.disconnect() }
    }

    private fun ticket(context: Context, gatewayId: String, identityApi: String): String {
        val services = ServiceDiscovery.selected(context)
        require(services.identity == identityApi) { "This gateway uses a different account service. In My access, find this gateway's account service and sign in there first." }
        val token = SecureCredentialStore.get(context, services.key("global-subscriber-token")) ?: throw IllegalStateException("Sign in with Google first")
        return request(context,"$identityApi/unit-identity.php",form("gateway_id" to gatewayId),subscriber=token).getString("ticket")
    }

    fun sync(context: Context, accountId: Long, input: String): List<LinkedUnit> {
        require(accountId(context) == accountId) { "Account changed. Reopen My units." }
        val services = ServiceDiscovery.selected(context)
        val base = gatewayOrigin(input)
        val meta = gatewayMetadata(context, input)
        val provider = meta.optJSONObject("account_services")?.let { ServiceDiscovery.parse(it) } ?: ServiceDiscovery.central
        require(provider == services) { "In My access, find this gateway's account service and sign in there first." }
        val gatewayId = meta.getString("gateway_id")
        require(Regex("[a-f0-9]{32}").matches(gatewayId) && gatewayOrigin(meta.getString("base_url")) == base) { "Gateway identity or address does not match its configuration" }
        val json = request(context,"$base/script/unitLinked.php",form("ticket" to ticket(context,gatewayId,provider.identity),"client_id" to clientId(context,accountId),"action" to "list"))
        require(json.getString("gateway_id") == gatewayId) { "Gateway identity changed" }
        val old = load(context,accountId,services)
        val array = json.getJSONArray("units")
        val incoming = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            require(o.getString("scope") == "single_unit_read_only") { "Unsupported unit access scope" }
            val unitId = o.getLong("unitId"); val token = o.getString("token")
            require(unitId > 0 && Regex("[a-f0-9]{64}").matches(token)) { "Invalid unit credential" }
            val key = "$gatewayId:$unitId"
            LinkedUnit(key,base,gatewayId,unitId,if (o.isNull("ownerId")) null else o.getLong("ownerId"),old.firstOrNull { it.key == key }?.name ?: o.optString("hostname").ifBlank { "Unit $unitId" },token,provider.identity)
        }
        require(accountId(context) == accountId && ServiceDiscovery.selected(context) == services) { "Account changed. Reopen My units." }
        return (old.filter { it.gatewayId != gatewayId && it.gateway != base } + incoming).also { save(context,accountId,it,services) }
    }

    fun status(context: Context, unit: LinkedUnit): JSONObject {
        val json = request(context,"${unit.gateway}/script/unitStatus.php",unitToken=unit.token)
        val identity = json.getJSONObject("unit")
        require(json.getString("scope") == "single_unit_read_only" && identity.getLong("unitId") == unit.unitId && (unit.ownerId == null || identity.optLong("ownerId") == unit.ownerId)) { "Status does not match the linked unit" }
        val threat = json.getJSONObject("threat")
        threat.getBoolean("warning"); threat.getBoolean("confirmedLocalInfection"); threat.getInt("severity")
        java.time.OffsetDateTime.parse(json.getString("server_time"))
        return json
    }

    fun unlink(context: Context, accountId: Long, unit: LinkedUnit) {
        val services = ServiceDiscovery.selected(context)
        require(accountId(context) == accountId) { "Account changed. Reopen My units." }
        require(unit.gatewayId.isNotBlank()) { "For a manual pairing, ask the gateway operator to revoke its token" }
        request(context,"${unit.gateway}/script/unitLinked.php",form("action" to "unlink","unit_id" to unit.unitId.toString(),"ticket" to ticket(context,unit.gatewayId,unit.identityApi)))
        require(accountId(context) == accountId && ServiceDiscovery.selected(context) == services) { "Account changed. Reopen My units." }
        save(context,accountId,load(context,accountId,services).filter { it.key != unit.key },services)
    }

    fun importPairing(context: Context, accountId: Long, input: String, gatewayInput: String): List<LinkedUnit> {
        val services = ServiceDiscovery.selected(context)
        require(input.length <= 32768) { "Pairing data is too large" }
        val json = try { JSONObject(input) } catch (_: Exception) { throw IllegalArgumentException("Invalid pairing JSON") }
        require(json.optBoolean("ok") && json.getString("scope") == "single_unit_read_only") { "Unsupported pairing" }
        val base = gatewayOrigin(gatewayInput)
        val identity = json.getJSONObject("unit"); val unitId = identity.getLong("unitId"); val token = json.getString("token")
        require(unitId > 0 && Regex("[a-fA-F0-9]{64}").matches(token)) { "Invalid pairing credential" }
        val unit = LinkedUnit("manual:$base:$unitId",base,"",unitId,if (identity.isNull("ownerId")) null else identity.getLong("ownerId"),identity.optString("hostname").ifBlank { "Unit $unitId" },token.lowercase())
        require(accountId(context) == accountId) { "Account changed. Reopen My units." }
        status(context,unit) // Validate the endpoint and credential before saving.
        require(accountId(context) == accountId && ServiceDiscovery.selected(context) == services) { "Account changed. Reopen My units." }
        return (load(context,accountId,services).filter { it.gateway != base || it.unitId != unitId } + unit).also { save(context,accountId,it,services) }
    }
}
