package com.sameerasw.airsync.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phone Remote (SPACE.md §15.3): one decrypted binary frame of the `/screen` stream.
 *
 * Plaintext = 22-byte little-endian header, then the payload:
 * `u8 kind · u32 seq · u64 ptsMicros · f32 cursorX · f32 cursorY · u8 cursorVisible`.
 * Pure (no Android types) so it is unit-tested on the JVM.
 */
class ScreenFrame(
    val kind: Int,
    val seq: Long,
    val ptsMicros: Long,
    /** Fractions of the encoded view; may be outside 0…1 when the cursor is off the view. */
    val cursorX: Float,
    val cursorY: Float,
    /** Header flag and a finite position: a NaN cursor (older computers) counts as hidden. */
    val cursorVisible: Boolean,
    val payload: ByteArray
) {
    val isVideo: Boolean get() = kind == KIND_CONFIG || kind == KIND_KEYFRAME || kind == KIND_FRAME

    companion object {
        const val HEADER_SIZE = 22
        const val KIND_CONFIG = 1
        const val KIND_KEYFRAME = 2
        const val KIND_FRAME = 3
        const val KIND_JPEG = 4

        /** Null when the plaintext is shorter than a header or the kind is unknown. */
        fun parse(plain: ByteArray): ScreenFrame? {
            if (plain.size < HEADER_SIZE) return null
            val b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
            val kind = b.get().toInt() and 0xFF
            if (kind !in KIND_CONFIG..KIND_JPEG) return null
            val seq = b.int.toLong() and 0xFFFF_FFFFL
            val pts = b.long
            val cx = b.float
            val cy = b.float
            val visible = (b.get().toInt() and 0xFF) != 0
            val payload = plain.copyOfRange(HEADER_SIZE, plain.size)
            return ScreenFrame(kind, seq, pts, cx, cy, visible && cx.isFinite() && cy.isFinite(), payload)
        }

        /** The computer's side of the framing (tests, and a local loopback if ever needed). */
        fun encode(
            kind: Int, seq: Long, ptsMicros: Long, cursorX: Float, cursorY: Float, cursorVisible: Boolean,
            payload: ByteArray
        ): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put(kind.toByte())
            b.putInt(seq.toInt())
            b.putLong(ptsMicros)
            b.putFloat(cursorX)
            b.putFloat(cursorY)
            b.put(if (cursorVisible) 1 else 0)
            b.put(payload)
            return b.array()
        }
    }
}

/**
 * The `/screen` socket's encryption (SPACE.md §15.3): AES-256-GCM with the pairing key, exactly as `/socket`.
 * Text frames are base64(`nonce12‖ct‖tag16`); binary frames are the same bytes raw. Never logs the key.
 */
object RemoteCrypto {
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun seal(plain: ByteArray, key: SecretKey): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    /** Null when the frame is too short or doesn't authenticate with [key]. */
    fun open(combined: ByteArray, key: SecretKey): ByteArray? {
        if (combined.size < NONCE + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, combined, 0, NONCE))
            cipher.doFinal(combined, NONCE, combined.size - NONCE)
        } catch (_: Exception) {
            null
        }
    }

    fun sealText(json: String, key: SecretKey): String =
        java.util.Base64.getEncoder().encodeToString(seal(json.toByteArray(StandardCharsets.UTF_8), key))

    fun openText(text: String, key: SecretKey): String? {
        val bytes = try {
            java.util.Base64.getMimeDecoder().decode(text.trim())
        } catch (_: IllegalArgumentException) {
            return null
        }
        return open(bytes, key)?.toString(StandardCharsets.UTF_8)
    }
}
