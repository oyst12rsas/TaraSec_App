package org.tarasec.app

import android.content.Context

enum class AppRole(val persistedValue: String) {
    HOTSPOT_USER("hotspot_user"),
    HOTSPOT_OWNER("hotspot_owner"),
    TARASEC_ADMIN("tarasec_admin");

    companion object {
        fun fromPersisted(value: String?): AppRole =
            entries.firstOrNull { it.persistedValue == value } ?: HOTSPOT_USER
    }
}

object AppRoleStore {
    private const val PREFS = "tarasec_app_role"
    private const val KEY_ROLE = "role"

    fun load(context: Context): AppRole {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppRole.fromPersisted(prefs.getString(KEY_ROLE, null))
    }

    fun save(context: Context, role: AppRole) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROLE, role.persistedValue)
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ROLE)
            .apply()
    }
}
