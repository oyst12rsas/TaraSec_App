package org.tarasec.app

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor
import androidx.core.content.ContextCompat

/**
 * Security gate in front of the TaraSec UI.
 *
 * Uses Android's own strong biometric or device credential (PIN/password/pattern)
 * rather than inventing a TaraSec-specific PIN. The actual manager credential is
 * still encrypted in Android Keystore by SecureCredentialStore.
 */
class SecureLauncherActivity : FragmentActivity() {
    private lateinit var executor: Executor
    private var prompt: BiometricPrompt? = null
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = ContextCompat.getMainExecutor(this)
        authenticate()
    }

    override fun onResume() {
        super.onResume()
        // If MainActivity has returned/closed, require authentication again.
        if (!launched && prompt == null) authenticate()
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val manager = BiometricManager.from(this)
        val available = manager.canAuthenticate(authenticators)
        if (available != BiometricManager.BIOMETRIC_SUCCESS) {
            // No secure screen lock/biometric is configured. Do not silently bypass
            // the lock; tell Android to leave this activity rather than exposing
            // manager credentials without local device authentication.
            setContentView(android.widget.TextView(this).apply {
                text = "TaraSec requires a configured device PIN/password/pattern or strong biometric before manager access can be opened."
                setPadding(48, 96, 48, 48)
                textSize = 18f
            })
            return
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                prompt = null
                launched = true
                startActivity(Intent(this@SecureLauncherActivity, MainActivity::class.java))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                prompt = null
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    setContentView(android.widget.TextView(this@SecureLauncherActivity).apply {
                        text = "TaraSec unlock failed: $errString\n\nClose and reopen TaraSec to try again."
                        setPadding(48, 96, 48, 48)
                        textSize = 18f
                    })
                }
            }
        }

        prompt = BiometricPrompt(this, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TaraSec")
            .setSubtitle("Use fingerprint, face, or your device PIN/password")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt?.authenticate(info)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        launched = false
    }
}
