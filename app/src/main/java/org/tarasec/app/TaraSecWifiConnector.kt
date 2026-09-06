package org.tarasec.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object TaraSecWifiConnector {
    fun connect(activity: Activity, ssid: String): String {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return "Invalid TaraSec Wi-Fi name."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return "Android Wi-Fi service is unavailable."

            // TaraSec hotspots provide Internet, so keep the tapped hotspot as
            // TaraSec's only current suggestion. Android may still prefer an
            // already-connected saved Wi-Fi, because suggestions do not override
            // the user's/system's active network selection.
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
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                    // A suggestion cannot force Android away from a currently
                    // working saved Wi-Fi. Open the system Wi-Fi switcher now so
                    // the user's next tap is the actual system-wide connection.
                    Toast.makeText(activity, "Tap $cleanSsid to switch Wi-Fi", Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(Settings.Panel.ACTION_WIFI))
                    "Android has registered $cleanSsid. Tap it in the Wi-Fi panel to switch the phone to this TaraSec hotspot."
                }
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
