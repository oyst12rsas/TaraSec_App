package org.tarasec.app

import android.content.Context

enum class TaraPaymentMethod(val id: String, val title: String, val note: String) {
    GOOGLE_PAY(
        "google_pay",
        "Google Pay",
        "Best fit for Android. Payment is completed through a provider-managed Google Pay flow."
    ),
    APPLE_PAY(
        "apple_pay",
        "Apple Pay",
        "Available through the hotspot web checkout on supported Apple devices and browsers."
    ),
    PAYPAL(
        "paypal",
        "PayPal",
        "Pay with a PayPal account through the payment provider checkout."
    );

    companion object {
        fun fromId(value: String?): TaraPaymentMethod =
            entries.firstOrNull { it.id == value } ?: GOOGLE_PAY
    }
}

object PaymentPreferences {
    private const val PREFS = "tarasec_payment_v1"
    private const val KEY_METHOD = "preferred_method"

    fun preferredMethod(context: Context): TaraPaymentMethod =
        TaraPaymentMethod.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_METHOD, null)
        )

    fun setPreferredMethod(context: Context, method: TaraPaymentMethod) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_METHOD, method.id)
            .apply()
    }
}
