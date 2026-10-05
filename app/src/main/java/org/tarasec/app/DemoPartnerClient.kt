package org.tarasec.app

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import org.json.JSONObject

object DemoPartnerClient {
    fun request(base: String, path: String, token: String = "", body: JSONObject? = null): JSONObject {
        val connection = URL(base.trimEnd('/') + "/script/" + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) connection.setRequestProperty("X-TaraSec-Demo-Token", token)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: error("HTTP $code: endpoint returned no JSON; check node upgrade")
            check(code in 200..299 && json.optBoolean("ok")) { json.optString("error", "HTTP $code") }
            return json
        } finally { connection.disconnect() }
    }

    // One bounded connection to the DB-configured rejection target. The app
    // does not submit incident reports: the honeypot/firewall must report it.
    fun probe(ip: String, port: Int): String = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 3000)
            socket.soTimeout = 1500
            socket.getOutputStream().write("SSH-2.0-TaraSec_Demo5\r\n".toByteArray(Charsets.US_ASCII))
            runCatching { socket.getInputStream().read(ByteArray(256)) }
        }
        "Test connection closed; waiting for receiver report."
    } catch (e: Exception) { "Connection failed: ${e.message}. Failure alone does not prove blacklisting." }
}
