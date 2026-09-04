package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings

object TaraSecWifiConnector {
    fun connect(activity: Activity, ssid: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return

        // For an explicit user choice, open Android's Wi-Fi connection UI rather
        // than merely adding a network suggestion. Network suggestions may be
        // approved without Android actually leaving the currently connected Wi-Fi.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        activity.startActivity(intent)
    }
}
