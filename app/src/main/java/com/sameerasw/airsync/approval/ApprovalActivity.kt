package com.sameerasw.airsync.approval

import android.app.KeyguardManager
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * H.I.B "Touch ID on Phone": transparent activity that shows BiometricPrompt for one Mac
 * approval request. Approval is sent only from onAuthenticationSucceeded; dismissing, failing
 * out or any error denies.
 */
class ApprovalActivity : FragmentActivity() {
    private var requestId: String? = null
    private var prompt: BiometricPrompt? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val id = intent.getStringExtra(PhoneApprovalManager.EXTRA_ID)
        val request = id?.let { PhoneApprovalManager.get(it) }
        if (request == null) {
            finish()
            return
        }
        requestId = id

        // Close as soon as the request ends elsewhere (Mac cancelled it, timeout, Deny tapped).
        lifecycleScope.launch {
            PhoneApprovalManager.pending.collect { pending ->
                if (!pending.containsKey(request.id)) {
                    answered = true
                    prompt?.cancelAuthentication()
                    if (!isFinishing) finish()
                }
            }
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { showPrompt(request) }
                // Leave the request (and its notification) pending so the user can try again.
                override fun onDismissCancelled() { finish() }
                override fun onDismissError() { finish() }
            })
        } else {
            showPrompt(request)
        }
    }

    private fun showPrompt(request: PhoneApprovalManager.Request) {
        if (isFinishing || isDestroyed) return
        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No fingerprint/face or screen lock set up: can't approve safely.
            answer { PhoneApprovalManager.deny(this, request.id) }
            return
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val viaBiometric = result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC
                answer { PhoneApprovalManager.approveAuthenticated(this@ApprovalActivity, request.id, viaBiometric) }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelled, too many attempts, lockout, ...: deny (no-op if already answered elsewhere).
                answer { PhoneApprovalManager.deny(this@ApprovalActivity, request.id) }
            }

            // onAuthenticationFailed: a single non-matching attempt; the prompt stays up for a retry.
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("${request.title}?")
            .setSubtitle("Use your fingerprint or screen lock to approve")
            .setDescription(request.detail)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(true)
            .build()

        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).also { it.authenticate(info) }
    }

    private inline fun answer(action: () -> Unit) {
        if (answered) return
        answered = true
        action()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        // Leaving without an answer (e.g. back gesture before the prompt appeared) keeps the request
        // pending; it still expires on its own timer.
        prompt = null
        super.onDestroy()
    }
}
