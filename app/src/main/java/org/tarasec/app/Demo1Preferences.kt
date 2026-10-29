package org.tarasec.app

import android.content.Context

/** Demo 1 choices survive leaving the screen and restarting the app. */
object Demo1Preferences {
    private const val PREFS = "tarasec_demo1_v1"
    private const val SELECTED_IP = "selected_endpoint_ip"
    private const val CUSTOM_IP = "custom_endpoint_ip"
    private const val REMOVED_IPS = "removed_endpoint_ips"

    fun removedIps(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(REMOVED_IPS, emptySet()).orEmpty().toSet()

    /** Remove only the demo entry, never the saved installation or its credentials. */
    fun remove(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putStringSet(REMOVED_IPS, removedIps(context) + ip)
            if (selectedIp(context) == ip) remove(SELECTED_IP)
            if (customIp(context) == ip) remove(CUSTOM_IP)
        }.apply()
    }

    fun selectedIp(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED_IP, "").orEmpty()

    fun customIp(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CUSTOM_IP, "").orEmpty()

    fun select(context: Context, ip: String, custom: Boolean = false) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(SELECTED_IP, ip)
            if (custom) {
                putString(CUSTOM_IP, ip)
                putStringSet(REMOVED_IPS, removedIps(context) - ip)
            }
        }.apply()
    }
}
