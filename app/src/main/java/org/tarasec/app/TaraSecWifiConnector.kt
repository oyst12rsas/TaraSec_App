package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings

object TaraSecWifiConnector {
    fun connect(activity: Activity, ssid: String): String {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return "Invalid TaraSec Wi-Fi name."

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }

        activity.startActivity(intent)
        return "Switching to $cleanSsid... Android is connecting; TaraSec will update automatically when the Wi-Fi connection is ready."
    }
}
