package org.tarasec.app

import android.content.Context
import java.util.UUID

data class TaraSecIdentity(
    val installationId: String,
    val unitId: String
)

object TaraSecIdentityStore {
    private const val PREFS = "tarasec_identity_v1"
    private const val KEY_UNIT_ID = "unit_id"

    fun current(context: Context): TaraSecIdentity {
        val installationId = InstallationStore.selectedId(context).orEmpty()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var unitId = prefs.getString(KEY_UNIT_ID, null).orEmpty()
        if (unitId.isBlank()) {
            unitId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UNIT_ID, unitId).apply()
        }
        return TaraSecIdentity(
            installationId = installationId,
            unitId = unitId
        )
    }
}
