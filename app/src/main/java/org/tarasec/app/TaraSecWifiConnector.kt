package org.tarasec.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object TaraSecWifiConnector {
    fun connect(activity: Activity, ssid: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return

        // A TaraSec subscriber identity is needed before roaming/accounting can
        // activate the selected hotspot. Ask for Google sign-in at the point it
        // becomes relevant instead of leaving the sign-in controls far down-page.
        if (SubscriberAccountClient.storedToken(activity) == null) {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(SubscriberAccountClient.identityLoginUrl("google"))
                )
            )
            return
        }

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
