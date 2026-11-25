package com.sameerasw.airsync.clip

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One clip on the shared clipboard (SPACE.md §16.1). Exactly one of [text] / [png] is set.
 * [png] is base64 (standard alphabet) as it travels on the wire, so a relay forwards it untouched.
 */
data class Clip(
    val text: String?,
    val png: String?,
    val id: String,
    val ts: Long,
    val origin: String,
    /** Legacy `clipboardImageUpdate` only: its mime / file name (the image may be a JPEG). */
    val mime: String? = null,
    val name: String? = null,
    /** Came as (or is sent as) the older `clipboardImageUpdate` message rather than `clipboardUpdate{png}`. */
    val legacyImage: Boolean = false
) {
    val isImage: Boolean get() = png != null
}

/** A clip as it arrived from a computer: older versions leave out [id], [ts] and [origin] (§16.1). */
data class ClipIn(
    val text: String?,
    val png: String?,
    val id: String? = null,
    val ts: Long? = null,
    val origin: String? = null,
    val mime: String? = null,
    val name: String? = null,
    val legacyImage: Boolean = false
)

/**
 * What the phone's own clipboard holds, as read by the watcher.
 * [appliedTag] is the clip id H.I.B stamped into the ClipDescription extras when it applied a clip;
 * [stamp] is the ClipDescription timestamp (System.currentTimeMillis base) — Android's change marker.
 */
data class LocalClip(
    val text: String?,
    val png: String?,
    val appliedTag: String? = null,
    val stamp: Long? = null,
    /** Screenshots go out as the older `clipboardImageUpdate` (any image type), see ScreenshotClipboardMonitor. */
    val mime: String? = null,
    val name: String? = null,
    val legacyImage: Boolean = false
)

/** Where clips go: the phone's links to computers, and its own clipboard. */
interface ClipPort {
    /** Ids (computer names) of the links that can take a message right now. */
    fun readyLinks(): List<String>

    /** Sends [clip] to one link with its id/ts/origin unchanged. */
    fun send(linkId: String, clip: Clip): Boolean

    /** Writes [clip] to the phone's clipboard, tagged with its id. False when the write failed. */
    fun apply(clip: Clip): Boolean
}

object ClipIds {
    private val random = SecureRandom()
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    /** A fresh id: 16 random bytes, base64url. */
    fun random(): String = ByteArray(16).also { random.nextBytes(it) }.let(b64url::encodeToString)

    /** The id of a clip that came without one: sha256 of the content, first 16 bytes, base64url. */
    fun derived(content: ByteArray): String =
        b64url.encodeToString(MessageDigest.getInstance("SHA-256").digest(content).copyOf(16))

    /** First 6 characters, for log lines. */
    fun short(id: String): String = id.take(6)

    /** Decoded size of a base64 string without decoding it. */
    fun base64Size(b64: String): Long {
        var len = 0L
        var pad = 0
        for (c in b64) {
            when {
                c == '=' -> { pad++; len++ }
                c == '\n' || c == '\r' || c == ' ' -> Unit
                else -> len++
            }
        }
        return len * 3 / 4 - pad
    }

    fun decodeBase64(b64: String): ByteArray? = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
}

/** Matches Android's ClipboardService line that says a background read of our clipboard was refused (§16.3). */
object ClipLogLine {
    const val PHRASE = "Denying clipboard access to "

    fun isDenialFor(line: String, pkg: String): Boolean {
        val i = line.indexOf(PHRASE)
        if (i < 0 || pkg.isEmpty()) return false
        val rest = line.substring(i + PHRASE.length)
        if (!rest.startsWith(pkg)) return false
        val next = rest.getOrNull(pkg.length)
        return next == null || next == ',' || next.isWhitespace()
    }
}
