package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object TaraSecWifiConnector {
    fun connect(activity: Activity, ssid: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return

        // Android no longer allows an ordinary app to silently force the whole
        // device away from its current Internet Wi-Fi and onto another access
        // point. ACTION_WIFI_ADD_NETWORKS only saves/approves a configuration; it
        // does not perform the system-wide switch, which made a TaraSec card look
        // as though it had been ignored.
        //
        // Take the user directly to Android's Wi-Fi switcher instead. This keeps
        // the connection system-wide (browser/captive portal included) rather
        // than using WifiNetworkSpecifier, whose requested network is scoped to
        // the requesting app and is not the right API for an Internet hotspot.
        Toast.makeText(
            activity,
            "Select $cleanSsid to connect",
            Toast.LENGTH_LONG
        ).show()

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        activity.startActivity(intent)
    }
}
