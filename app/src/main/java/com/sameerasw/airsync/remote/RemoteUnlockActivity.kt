package com.sameerasw.airsync.remote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Unlock from phone: the transparent screen that shows the prompts (BiometricPrompt needs a FragmentActivity).
 *  - unlock: BiometricPrompt "Unlock <name>" with a CryptoObject on that computer's Keystore key; the signature
 *    exists only after a fresh fingerprint or phone PIN, and goes straight to [RemoteUnlockFlow.onSigned];
 *  - enrol: "Allow unlocking <name> with your fingerprint?", then the prompt, then the key is made and sent.
 */
class RemoteUnlockActivity : FragmentActivity() {
    private var prompt: BiometricPrompt? = null
    private var done = false
    private var computer: String = ""
    private var mode: String = MODE_UNLOCK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        computer = intent.getStringExtra(EXTRA_COMPUTER).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        if (computer.isBlank() || savedInstanceState != null) { finish(); return }
        when (mode) {
            MODE_ENROLL -> askEnroll()
            else -> showUnlockPrompt()
        }
    }

    // MARK: - unlock

    private fun showUnlockPrompt() {
        val nonce = RemoteUnlockFlow.promptShown(computer) ?: run { finish(); return }
        val keyId = RemoteUnlockFlow.keyIdFor(computer)
        if (keyId == null) { fail(UnlockFlowState.ERR_NO_KEY); return }
        val signature = when (val p = RemoteUnlockKeys.prepare(keyId)) {
            is RemoteUnlockKeys.Prepared.Ready -> p.signature
            RemoteUnlockKeys.Prepared.Missing -> { fail(UnlockFlowState.ERR_NO_KEY); return }
            RemoteUnlockKeys.Prepared.Invalidated -> { fail(UnlockFlowState.ERR_KEY_INVALIDATED); return }
            is RemoteUnlockKeys.Prepared.Failed -> { fail(UnlockFlowState.ERR_FAILED); return }
        }
        // The signed id is the computer's hello id (SPACE.md §15.1), whatever alias the key sits under.
        val signedId = RemoteHub.computerId(computer)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (done) return
                val sig = result.cryptoObject?.signature
                if (sig == null) { fail(UnlockFlowState.ERR_FAILED); return }
                try {
                    sig.update(UnlockSigning.signedBytes(signedId, nonce))
                    val der = sig.sign()
                    done = true
                    RemoteUnlockFlow.onSigned(computer, nonce, der)
                    finish()
                } catch (e: KeyPermanentlyInvalidatedException) {
                    RemoteUnlockKeys.delete(keyId)
                    fail(UnlockFlowState.ERR_KEY_INVALIDATED)
                } catch (e: Exception) {
                    fail(UnlockFlowState.ERR_FAILED)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                fail(UnlockFlowState.ERR_CANCELLED)
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock $computer")
            .setSubtitle("Use your fingerprint or screen lock")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).also {
            it.authenticate(info, BiometricPrompt.CryptoObject(signature))
        }
    }

    private fun fail(code: String) {
        if (done) return
        done = true
        RemoteUnlockFlow.onPromptFailed(computer, code)
        if (!isFinishing) finish()
    }

    // MARK: - enrol

    private fun askEnroll() {
        val name = RemoteUnlockFlow.enrollPending(computer) ?: run { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow unlocking $name with your fingerprint?")
            .setMessage("When $name is locked, the Remote can unlock it after you confirm on this phone. " +
                "Your password stays on $name.")
            .setPositiveButton("Allow") { _, _ -> showEnrollPrompt(name) }
            .setNegativeButton("Not now") { _, _ -> RemoteUnlockFlow.declineEnroll(this); finish() }
            .setOnCancelListener { RemoteUnlockFlow.declineEnroll(this); finish() }
            .show()
    }

    private fun showEnrollPrompt(name: String) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (done) return
                done = true
                if (!RemoteUnlockFlow.completeEnroll(this@RemoteUnlockActivity, computer)) {
                    android.widget.Toast.makeText(
                        this@RemoteUnlockActivity, "Couldn't set up unlock on $name", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (done) return
                done = true
                RemoteUnlockFlow.declineEnroll(this@RemoteUnlockActivity)
                finish()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock $name from this phone")
            .setSubtitle("Confirm with your fingerprint or screen lock")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).also { it.authenticate(info) }
    }

    override fun onDestroy() {
        // Leaving before an answer (back gesture, rotation) counts as cancelled.
        if (!done && mode == MODE_UNLOCK && computer.isNotBlank()) RemoteUnlockFlow.onPromptFailed(computer, UnlockFlowState.ERR_CANCELLED)
        prompt = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_COMPUTER = "computer"
        private const val EXTRA_MODE = "mode"
        private const val MODE_UNLOCK = "unlock"
        private const val MODE_ENROLL = "enroll"

        fun unlockIntent(context: Context, computer: String): Intent =
            Intent(context, RemoteUnlockActivity::class.java)
                .putExtra(EXTRA_COMPUTER, computer)
                .putExtra(EXTRA_MODE, MODE_UNLOCK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun enrollIntent(context: Context, computer: String): Intent =
            Intent(context, RemoteUnlockActivity::class.java)
                .putExtra(EXTRA_COMPUTER, computer)
                .putExtra(EXTRA_MODE, MODE_ENROLL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
