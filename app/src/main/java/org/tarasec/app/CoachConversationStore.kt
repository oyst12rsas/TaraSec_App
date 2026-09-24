package org.tarasec.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the opaque conversation identifier persists on the phone; chat text stays in memory. */
internal class CoachConversationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("coach_conversation", Context.MODE_PRIVATE)

    fun load(): String? = runCatching {
        val encoded = preferences.getString("encrypted_chat_id", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8).takeIf(String::isNotBlank)
    }.getOrNull()

    fun save(chatId: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(chatId.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted_chat_id", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .remove("encrypted_conversation")
            .commit()) { "Could not save Coach conversation ID" }
    }

    fun clear() {
        preferences.edit().remove("encrypted_chat_id").remove("encrypted_conversation").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "tarasec_coach_conversation_v1"
    }
}
