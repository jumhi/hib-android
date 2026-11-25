package com.sameerasw.airsync.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/** Unlock from phone (SPACE.md §15.1): the signed string, the key encoding and the attempt's state machine. */
class RemoteUnlockTest {

    @Test
    fun signedStringIsExact() {
        val nonce = "q83vEjRWeJA+/w=="
        val bytes = UnlockSigning.signedBytes("Haitham’s MacBook", nonce)
        assertArrayEquals("hib-unlock-v1|Haitham’s MacBook|q83vEjRWeJA+/w==".toByteArray(Charsets.UTF_8), bytes)
        // UTF-8: the curly apostrophe is three bytes.
        assertEquals("hib-unlock-v1|".length + "Haitham".length + 3 + "s MacBook".length + 1 + nonce.length, bytes.size)
    }

    @Test
    fun aliasIsSha256OfTheId() {
        // sha256("abc")
        assertEquals("hib_unlock_ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UnlockSigning.alias("abc"))
        assertTrue(UnlockSigning.alias("PC-INTERN").matches(Regex("hib_unlock_[0-9a-f]{64}")))
    }

    @Test
    fun publicKeyIsBase64SpkiAndVerifiesTheSignedString() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = gen.generateKeyPair()
        val b64 = UnlockSigning.publicKeyBase64(pair.public.encoded)
        assertFalse(b64.contains("\n"))
        val der = java.util.Base64.getDecoder().decode(b64)
        // X.509 SubjectPublicKeyInfo for P-256 is 91 bytes and starts with a SEQUENCE.
        assertEquals(91, der.size)
        assertEquals(0x30, der[0].toInt() and 0xFF)
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

        val nonce = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }
        signer.update(UnlockSigning.signedBytes("Mac", nonce))
        val sig = UnlockSigning.signatureBase64(signer.sign())

        fun verify(id: String, n: String) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub); update(UnlockSigning.signedBytes(id, n)); verify(java.util.Base64.getDecoder().decode(sig))
        }
        assertTrue(verify("Mac", nonce))
        assertFalse(verify("Other", nonce))
        assertFalse(verify("Mac", nonce.dropLast(2) + "A="))
    }

    @Test
    fun approveAndEnrollMessages() {
        val a = JsonParser.parseString(RemoteJson.unlockApprove("n0nce", "c2ln")).asJsonObject
        assertEquals("unlockApprove", a.get("type").asString)
        assertEquals("n0nce", a.getAsJsonObject("data").get("nonce").asString)
        assertEquals("c2ln", a.getAsJsonObject("data").get("sig").asString)
        val e = JsonParser.parseString(RemoteJson.unlockEnroll("phone-1", "MFkw")).asJsonObject.getAsJsonObject("data")
        assertEquals("phone-1", e.get("phoneId").asString)
        assertEquals("MFkw", e.get("publicKey").asString)
        assertEquals("unlockRequest", RemoteJson.type(RemoteJson.unlockRequest()))
    }

    // MARK: - state machine

    @Test
    fun happyPath() {
        val f = UnlockFlowState("Mac")
        assertTrue(f.request(0))
        assertEquals(UnlockFlowState.Step.REQUESTING, f.step)
        assertTrue(f.onChallenge("abc", 100))
        f.promptShown(200)
        assertEquals(UnlockFlowState.Step.PROMPTING, f.step)
        assertEquals("abc", f.onSigned(5_000))
        assertEquals(UnlockFlowState.Step.APPROVING, f.step)
        f.onResult(UnlockResult(true, null))
        assertEquals(UnlockFlowState.Step.DONE, f.step)
        assertNull(f.error)
        assertNull(f.nonce)
    }

    @Test
    fun aSlowPromptExpiresInsteadOfSending() {
        val f = UnlockFlowState("Mac")
        f.request(0); f.onChallenge("abc", 0); f.promptShown(10)
        assertNull(f.onSigned(UnlockFlowState.CHALLENGE_MS + 1))
        assertEquals(UnlockFlowState.Step.FAILED, f.step)
        assertEquals(UnlockFlowState.ERR_EXPIRED, f.error)
        // A late signature after that is not sent either.
        assertNull(f.onSigned(UnlockFlowState.CHALLENGE_MS + 2))
    }

    @Test
    fun oneAttemptAtATime() {
        val f = UnlockFlowState("Mac")
        assertTrue(f.request(0))
        assertFalse(f.request(1_000))
        assertTrue(f.busy)
        // No challenge in time: the attempt can start over.
        assertTrue(f.expireIfStale(UnlockFlowState.REPLY_MS + 1))
        assertEquals(UnlockFlowState.ERR_NO_ANSWER, f.error)
        assertFalse(f.busy)
        assertTrue(f.request(UnlockFlowState.REPLY_MS + 2))
    }

    @Test
    fun unaskedChallengesAreIgnored() {
        val f = UnlockFlowState("Mac")
        assertFalse(f.onChallenge("abc", 0))
        assertEquals(UnlockFlowState.Step.IDLE, f.step)
        f.request(0); f.onChallenge("one", 1)
        assertFalse(f.onChallenge("two", 2))
        assertEquals("one", f.nonce)
    }

    @Test
    fun computerErrorsEndTheAttempt() {
        for (err in listOf("notSetUp", "wrongPassword", "notLocked", "failed", "expired", "badSignature", "busy")) {
            val f = UnlockFlowState("Mac")
            f.request(0); f.onChallenge("n", 0); f.onSigned(1)
            f.onResult(UnlockResult(false, err))
            assertEquals(UnlockFlowState.Step.FAILED, f.step)
            assertEquals(err, f.error)
        }
        // busy can come straight back for the request itself.
        val f = UnlockFlowState("Mac")
        f.request(0); f.onResult(UnlockResult(false, "busy"))
        assertEquals("busy", f.error)
    }

    @Test
    fun cancelledPromptFailsQuietly() {
        val f = UnlockFlowState("Mac")
        f.request(0); f.onChallenge("n", 0); f.promptShown(1)
        f.onPromptFailed(UnlockFlowState.ERR_CANCELLED)
        assertEquals(UnlockFlowState.Step.FAILED, f.step)
        assertNull(UnlockFlowState.message(f.error, "Mac"))
    }

    @Test
    fun errorTexts() {
        val m = { code: String -> UnlockFlowState.message(code, "PC-INTERN") }
        assertEquals("Set up Unlock from phone on PC-INTERN first", m("notSetUp"))
        assertEquals("Set up unlock again on PC-INTERN", m("keyInvalidated"))
        assertEquals("Set up unlock again on PC-INTERN", m("badSignature"))
        assertEquals("Set up unlock again on PC-INTERN", m("noKey"))
        // SPACE.md §15.5: no more "check the password" for everything.
        assertEquals("PC-INTERN's password changed — set up Unlock from phone again there.", m("wrongPassword"))
        assertEquals("Couldn't unlock PC-INTERN. Try again, or unlock it at the desk.", m("failed"))
        assertEquals("PC-INTERN is already unlocked", m("notLocked"))
        assertEquals("That took too long. Try again.", m("expired"))
        assertEquals("Wait a few seconds, then try again", m("busy"))
        assertNotNull(m("noLockScreen"))
        assertNull(UnlockFlowState.message(null, "PC-INTERN"))
    }
}
