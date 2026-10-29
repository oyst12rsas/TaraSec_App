package org.tarasec.app

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/** Security gate in front of TaraSec. */
class SecureLauncherActivity : FragmentActivity() {
    private lateinit var executor: Executor
    private var prompt: BiometricPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = ContextCompat.getMainExecutor(this)
        authenticate()
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            setContentView(android.widget.TextView(this).apply {
                text = "TaraSec requires a configured device PIN/password/pattern or strong biometric before it can be opened."
                setPadding(48, 96, 48, 48)
                textSize = 18f
            })
            return
        }

        prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // The browser link only resumes the app. The pending management
                    // challenge still has to be exchanged and verified by the node.
                    val managementReturn = intent?.action == Intent.ACTION_VIEW &&
                        intent?.data?.scheme == "tarasec" && intent?.data?.host == "management-return"
                    val destination = if (managementReturn) MainActivity::class.java else SubscriberHomeActivity::class.java
                    val next = Intent(this@SecureLauncherActivity, destination)
                    if (managementReturn) {
                        // Preserve the existing Compose state and pairing verifier.
                        // No URI query parameters or external extras are forwarded.
                        next.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(next)
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        setContentView(android.widget.TextView(this@SecureLauncherActivity).apply {
                            text = "TaraSec unlock failed: $errString\n\nClose and reopen TaraSec to try again."
                            setPadding(48, 96, 48, 48)
                            textSize = 18f
                        })
                    } else {
                        finish()
                    }
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TaraSec")
            .setSubtitle("Use fingerprint, face, or your device PIN/password")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt?.authenticate(info)
    }
}
