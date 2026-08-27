package org.tarasec.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object LocalGateway {
    fun baseUrl(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val props = cm.getLinkProperties(network) ?: continue
            val gateway = props.routes
                .asSequence()
                .filter { it.isDefaultRoute }
                .mapNotNull { it.gateway }
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
            if (!gateway.isNullOrBlank()) return "http://$gateway"
        }
        return null
    }
}
