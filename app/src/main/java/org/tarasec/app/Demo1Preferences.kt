package org.tarasec.app

import android.content.Context

/** Demo 1 choices survive leaving the screen and restarting the app. */
object Demo1Preferences {
    private const val PREFS = "tarasec_demo1_v1"
    private const val SELECTED_IP = "selected_endpoint_ip"
    private const val CUSTOM_IP = "custom_endpoint_ip"

    fun selectedIp(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED_IP, "").orEmpty()

    fun customIp(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CUSTOM_IP, "").orEmpty()

    fun select(context: Context, ip: String, custom: Boolean = false) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(SELECTED_IP, ip)
            if (custom) putString(CUSTOM_IP, ip)
        }.apply()
    }
}
