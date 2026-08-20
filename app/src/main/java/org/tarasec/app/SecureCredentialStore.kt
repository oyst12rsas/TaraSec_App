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

object SecureCredentialStore {
    private const val PREFS = "tarasec_manager_credentials_v1"
    private const val KEY_ALIAS = "tarasec-manager-credentials-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(context: Context, installationId: String, credential: String) {
        if (credential.isBlank()) return
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(credential.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + cipher.iv.size + encrypted.size)
        packed[0] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, packed, 1, cipher.iv.size)
        System.arraycopy(encrypted, 0, packed, 1 + cipher.iv.size, encrypted.size)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(installationId, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun get(context: Context, installationId: String): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(installationId, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.isEmpty()) return null
            val ivLength = packed[0].toInt() and 0xff
            if (ivLength <= 0 || packed.size <= 1 + ivLength) return null
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val encrypted = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun remove(context: Context, installationId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(installationId).apply()
    }
}
