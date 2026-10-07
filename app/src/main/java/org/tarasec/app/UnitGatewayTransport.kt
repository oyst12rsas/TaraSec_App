package org.tarasec.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

object UnitGatewayTransport {
    fun isNetBird(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts[0] == "100" && parts[1] == "68" && parts.all {
            it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255
        }
    }

    fun origin(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "Enter the gateway IP address" }
        val guessed = URI(if (value.contains("://")) value else "https://$value")
        val scheme = if (!value.contains("://") && isNetBird(guessed.host.orEmpty())) "http" else guessed.scheme
        require(!guessed.host.isNullOrBlank() && guessed.rawUserInfo == null && guessed.rawQuery == null && guessed.rawFragment == null &&
            (guessed.path.isNullOrEmpty() || guessed.path == "/") && (guessed.port == -1 || guessed.port in 1..65535) &&
            (scheme == "https" || (scheme == "http" && isNetBird(guessed.host)))) {
            "Enter the gateway IP address, without a path, login or query"
        }
        val host = guessed.host.lowercase().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
        val port = guessed.port.takeUnless { it == -1 || it == if (scheme == "https") 443 else 80 }
        return "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }

    @Suppress("DEPRECATION")
    fun connection(context: Context, url: URL): HttpURLConnection {
        if (url.protocol == "https") return url.openConnection() as HttpURLConnection
        require(url.protocol == "http" && isNetBird(url.host)) { "Unit credentials require HTTPS or an encrypted TaraSec VPN connection" }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val destination = InetAddress.getByName(url.host) // Strict numeric IPv4 above; no DNS redirect.
        val network = cm.allNetworks.firstOrNull { candidate ->
            val capabilities = cm.getNetworkCapabilities(candidate)
            val properties = cm.getLinkProperties(candidate)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                properties != null && properties.linkAddresses.any { address ->
                    val host = address.address.hostAddress.orEmpty()
                    isNetBird(host) || host.startsWith("10.100.")
                } && properties.routes.any { it.destination.contains(destination) }
        } ?: error("Connect the TaraSec VPN before linking or reading units through this NetBird gateway")
        // Bind this request to the selected VPN; never fall back to mobile/Wi-Fi.
        return network.openConnection(url) as HttpURLConnection
    }
}
