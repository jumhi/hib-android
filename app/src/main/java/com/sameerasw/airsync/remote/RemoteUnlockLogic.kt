package com.sameerasw.airsync.remote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Unlock from phone (SPACE.md §15.1, design §6.6): the pure parts. The phone proves a fresh fingerprint/PIN to
 * the computer by signing `"hib-unlock-v1|" + id + "|" + nonce` with a Keystore key made for that computer.
 */
object UnlockSigning {
    const val PREFIX = "hib-unlock-v1|"

    /** The exact UTF-8 bytes the computer verifies. [nonce] is the base64 string exactly as it was sent. */
    fun signedBytes(computerId: String, nonce: String): ByteArray =
        (PREFIX + computerId + "|" + nonce).toByteArray(StandardCharsets.UTF_8)

    /** Keystore alias of a computer's key: `hib_unlock_<hex sha256(id)>`. */
    fun alias(computerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(computerId.toByteArray(StandardCharsets.UTF_8))
        return "hib_unlock_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** `publicKey` of `unlockEnroll`: base64 (no line breaks) of the X.509 SubjectPublicKeyInfo DER. */
    fun publicKeyBase64(spkiDer: ByteArray): String = java.util.Base64.getEncoder().encodeToString(spkiDer)

    fun signatureBase64(der: ByteArray): String = java.util.Base64.getEncoder().encodeToString(der)
}

/**
 * One unlock attempt for one computer, as a small state machine (pure; time comes in as [now]).
 *
 * Idle → [request] → Requesting → challenge → Challenged → [promptShown] → Prompting → signed → Approving →
 * result → Done / Failed. A challenge is good for [CHALLENGE_MS] (the computer's 30 s, less a margin for the
 * trip), so a slow prompt fails here with `expired` instead of being sent.
 */
class UnlockFlowState(val computer: String) {
    enum class Step { IDLE, REQUESTING, CHALLENGED, PROMPTING, APPROVING, DONE, FAILED }

    var step: Step = Step.IDLE
        private set
    var nonce: String? = null
        private set
    var error: String? = null
        private set
    private var stepAt = 0L
    private var challengeAt = 0L

    val busy: Boolean get() = step == Step.REQUESTING || step == Step.CHALLENGED || step == Step.PROMPTING || step == Step.APPROVING

    /** Start an attempt. False (nothing to send) while one is already running. */
    fun request(now: Long): Boolean {
        if (busy && !timedOut(now)) return false
        step = Step.REQUESTING; stepAt = now; nonce = null; error = null
        return true
    }

    /** `unlockChallenge` arrived. False when no attempt asked for one (ignored). */
    fun onChallenge(n: String, now: Long): Boolean {
        if (step != Step.REQUESTING) return false
        nonce = n; challengeAt = now; step = Step.CHALLENGED; stepAt = now
        return true
    }

    fun promptShown(now: Long) {
        if (step == Step.CHALLENGED) { step = Step.PROMPTING; stepAt = now }
    }

    /**
     * The prompt succeeded and produced a signature. Returns the nonce to send with it, or null when the
     * challenge is too old (the attempt fails with `expired`).
     */
    fun onSigned(now: Long): String? {
        if (step != Step.PROMPTING && step != Step.CHALLENGED) return null
        if (now - challengeAt > CHALLENGE_MS) { fail(ERR_EXPIRED); return null }
        step = Step.APPROVING; stepAt = now
        return nonce
    }

    /** The prompt was cancelled or the key couldn't sign ([code] is one of the phone-side errors). */
    fun onPromptFailed(code: String) = fail(code)

    fun onResult(result: UnlockResult) {
        if (step == Step.IDLE || step == Step.DONE) return
        if (result.ok) { step = Step.DONE; error = null } else fail(result.error ?: ERR_FAILED)
        nonce = null
    }

    /** Nothing came back in time for the current step (no challenge / no result). */
    fun timedOut(now: Long): Boolean = when (step) {
        Step.REQUESTING -> now - stepAt > REPLY_MS
        Step.APPROVING -> now - stepAt > RESULT_MS
        Step.CHALLENGED, Step.PROMPTING -> now - challengeAt > CHALLENGE_MS + PROMPT_GRACE_MS
        else -> false
    }

    /** Ends a timed-out step as failed (true when it did). */
    fun expireIfStale(now: Long): Boolean {
        if (!timedOut(now)) return false
        fail(if (step == Step.CHALLENGED || step == Step.PROMPTING) ERR_EXPIRED else ERR_NO_ANSWER)
        return true
    }

    fun reset() { step = Step.IDLE; nonce = null; error = null }

    private fun fail(code: String) {
        step = Step.FAILED; error = code; nonce = null
    }

    companion object {
        const val CHALLENGE_MS = 28_000L
        const val REPLY_MS = 8_000L
        /** The computer types the password and waits up to 5 s for the unlock to show. */
        const val RESULT_MS = 12_000L
        const val PROMPT_GRACE_MS = 2_000L

        // Computer-side errors (SPACE.md §15.1)
        const val ERR_NOT_SET_UP = "notSetUp"
        const val ERR_WRONG_PASSWORD = "wrongPassword"
        const val ERR_NOT_LOCKED = "notLocked"
        const val ERR_FAILED = "failed"
        const val ERR_EXPIRED = "expired"
        const val ERR_BAD_SIGNATURE = "badSignature"
        const val ERR_BUSY = "busy"
        /** SPACE.md §15.5: the Mac's helper isn't allowed to type (Accessibility missing or stale). */
        const val ERR_PERMISSION = "permission"

        // Phone-side errors
        const val ERR_CANCELLED = "cancelled"
        const val ERR_NO_KEY = "noKey"
        const val ERR_KEY_INVALIDATED = "keyInvalidated"
        const val ERR_NO_LOCK_SCREEN = "noLockScreen"
        const val ERR_NO_ANSWER = "noAnswer"

        /** What the Remote's locked card says for [code] about computer [name]. Null: say nothing (cancelled). */
        fun message(code: String?, name: String): String? = when (code) {
            null, ERR_CANCELLED -> null
            ERR_NOT_SET_UP -> "Set up Unlock from phone on $name first"
            ERR_NO_KEY, ERR_KEY_INVALIDATED, ERR_BAD_SIGNATURE -> "Set up unlock again on $name"
            ERR_NOT_LOCKED -> "$name is already unlocked"
            ERR_EXPIRED -> "That took too long. Try again."
            ERR_BUSY -> "Wait a few seconds, then try again"
            ERR_NO_LOCK_SCREEN -> "Set up a fingerprint or screen lock on this phone first"
            ERR_NO_ANSWER -> "$name didn't answer. Try again."
            ERR_PERMISSION -> "H.I.B Input on $name needs Accessibility again. On the Mac: Privacy & Security → " +
                "Accessibility → select H.I.B Input → remove (−) → allow it again."
            ERR_WRONG_PASSWORD -> "$name's password changed — set up Unlock from phone again there."
            else -> "Couldn't unlock $name. Try again, or unlock it at the desk."
        }
    }
}
