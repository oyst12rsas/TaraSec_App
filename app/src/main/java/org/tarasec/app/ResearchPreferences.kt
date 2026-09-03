package org.tarasec.app

import android.content.Context

object ResearchPreferences {
    private const val PREFS = "tarasec_research_v1"
    private const val KEY_PARTICIPATION = "participation_enabled"
    private const val KEY_DISCOUNT_OPT_IN = "discount_opt_in"

    fun participationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PARTICIPATION, false)

    fun setParticipationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PARTICIPATION, enabled)
            .apply()
        if (!enabled) setDiscountOptIn(context, false)
    }

    fun discountOptIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCOUNT_OPT_IN, false)

    fun setDiscountOptIn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISCOUNT_OPT_IN, enabled)
            .apply()
    }
}
