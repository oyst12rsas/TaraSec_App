package org.tarasec.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings

object TaraSecWifiConnector {
    fun connect(activity: Activity, ssid: String): String {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return "Invalid TaraSec Wi-Fi name."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return "Android Wi-Fi service is unavailable."

            // TaraSec hotspots provide Internet, so use the Internet-capable
            // suggestion API rather than ACTION_WIFI_ADD_NETWORKS (save only) or
            // WifiNetworkSpecifier (peer/app-scoped connection). Keep only the
            // hotspot the user just tapped as TaraSec's active suggestion.
            runCatching { wifi.removeNetworkSuggestions(emptyList()) }

            val builder = WifiNetworkSuggestion.Builder()
                .setSsid(cleanSsid)
                .setPriority(1000)
                .setIsAppInteractionRequired(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setIsInitialAutojoinEnabled(true)
            }

            val status = runCatching {
                wifi.addNetworkSuggestions(listOf(builder.build()))
            }.getOrElse {
                return "Android could not register $cleanSsid for connection: ${it.message ?: it.javaClass.simpleName}."
            }

            return when (status) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                    "TaraSec asked Android to connect to $cleanSsid. The first time, approve TaraSec's Wi-Fi suggestion when Android asks."
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> {
                    activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    "Android has disabled TaraSec Wi-Fi control. Allow TaraSec Wi-Fi control, then tap $cleanSsid again."
                }
                else ->
                    "Android rejected the $cleanSsid connection request (status $status)."
            }
        }

        activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        return "Select $cleanSsid in Android Wi-Fi settings to connect."
    }
}
