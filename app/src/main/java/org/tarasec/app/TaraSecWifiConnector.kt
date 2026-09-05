package org.tarasec.app

import android.app.Activity
import android.content.Intent
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
        // This keeps the intended sequence explicit:
        // red -> connect -> yellow -> log in -> green.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        activity.startActivity(intent)
    }
}
