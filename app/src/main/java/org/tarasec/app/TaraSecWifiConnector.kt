package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings

object TaraSecWifiConnector {
    fun connect(activity: Activity, ssid: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return

        // Connecting to a red TaraSec hotspot must be independent of subscriber
        // sign-in. First join the Wi-Fi. Once Android reports the hotspot as
        // connected but not authorized (yellow), the user can tap "Tap to log in"
        // and complete TaraSec/Google identity through the hotspot walled garden.
        //
        // Modern Android does not let ordinary apps silently force the device onto
        // a Wi-Fi network. On Android 11+ we can nevertheless take the user to a
        // focused system confirmation for this exact TaraSec SSID instead of the
        // generic nearby-network picker. Older Android versions fall back to the
        // regular Wi-Fi settings panel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(cleanSsid)
                .build()

            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                putParcelableArrayListExtra(
                    Settings.EXTRA_WIFI_NETWORK_LIST,
                    arrayListOf(suggestion)
                )
            }
            activity.startActivityForResult(intent, 0)
            return
        }

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        activity.startActivity(intent)
    }
}
