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
            activity.startActivity(intent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } else {
            activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }
}
