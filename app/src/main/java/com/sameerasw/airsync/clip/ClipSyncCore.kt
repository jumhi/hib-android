package com.sameerasw.airsync.clip

/**
 * The phone's one clipboard sync component (SPACE.md §16.2). Every path that copies, applies, relays
 * or catches up a clip goes through here, so the rules always hold:
 *  - seen ids: the last 64 ids applied or sent; a clip whose id was seen is ignored (kills echo loops);
 *  - newest wins: apply only if `ts > localTs - 1500`;
 *  - applying is not copying: applied clips carry their id in the ClipDescription extras (and the
 *    description timestamp is the change marker), so the watcher never sends them out again;
 *  - copying the same text again is a new clip (new id);
 *  - relays forward id/ts/origin unchanged, never back to the origin or the link it came from;
 *  - catch-up: a link that becomes ready gets the current clip if it is younger than 10 minutes.
 * Pure Kotlin (no Android types) so the rules are unit-tested; [ClipPort] does the I/O.
 */
class ClipSyncCore(
    private val port: ClipPort,
    private val phoneId: String = "phone",
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = ClipIds::random,
    private val sameDevice: (String, String) -> Boolean = { a, b -> a.trim().equals(b.trim(), ignoreCase = true) },
    private val log: (String) -> Unit = {}
) {
    enum class Trigger {
        /** The clipboard changed (listener callback, or the focus read after the log watcher saw a denial). */
        CALLBACK,
        /** A look at the clipboard without knowing it changed (H.I.B came to the front). */
        POLL,
        /** The user asked: notification "Send clipboard", Quick Settings tile, share sheet, widget. */
        EXPLICIT
    }

    enum class Outcome { COPIED, APPLIED, SEEN, OLDER, OFF, SIZE, UNCHANGED, OWN_APPLY, EMPTY }

    companion object {
        const val SEEN_MAX = 64
        const val SKEW_MS = 1_500L
        const val CATCH_UP_MAX_AGE_MS = 10 * 60_000L
        const val MAX_TEXT_BYTES = 1_000_000L
        const val MAX_PNG_BYTES = 8_000_000L
        /** Without a tag or a description timestamp, a change this soon after our write is our write. */
        const val OWN_WRITE_WINDOW_MS = 2_000L
        /** Without a description timestamp, the same content this soon is the same change seen twice. */
        const val DUPLICATE_WINDOW_MS = 1_000L
        const val VIA_PHONE = "phone"
        const val VIA_BLE = "ble"
    }

    /** "Clipboard sync" on the phone: off means neither send nor apply (explicit sends still go). */
    @Volatile var enabled: Boolean = true

    private val lock = Any()
    private val seen = LinkedHashSet<String>()
    private var current: Clip? = null
    private var currentHash: String? = null
    private var currentSource: String? = null
    private var currentAt = 0L
    private var localTs = Long.MIN_VALUE
    private var lastStamp: Long? = null
    private var appliedHash: String? = null
    private var appliedAt = Long.MIN_VALUE / 2

    fun current(): Clip? = synchronized(lock) { current }

    /** True for [windowMs] after H.I.B wrote a clip (the log watcher ignores denials it caused itself). */
    fun recentlyApplied(windowMs: Long): Boolean = synchronized(lock) { clock() - appliedAt < windowMs }

    /** True when [tag] (from a ClipDescription's extras) marks a clip H.I.B itself wrote. */
    fun isOwn(tag: String?): Boolean = tag != null && synchronized(lock) { tag in seen || tag == current?.id }

    /** True when [stamp] (a ClipDescription timestamp) is the change the phone already handled. */
    fun isKnownStamp(stamp: Long?): Boolean = stamp != null && synchronized(lock) { stamp == lastStamp }

    /**
     * H.I.B wrote the clipboard outside a sync (the widget's "Get", a computer's "Send clipboard" file):
     * the watcher must not send that write out as a copy.
     */
    fun noteOwnWrite(tag: String) = synchronized(lock) {
        remember(tag)
        appliedAt = clock()
    }

    /** A clip from a computer, over its link ([via] = phone) or over BLE. */
    fun onInbound(fromLink: String, inc: ClipIn, via: String): Outcome {
        val content = contentOf(inc.text, inc.png) ?: return Outcome.EMPTY
        val legacy = inc.id.isNullOrBlank()
        val now = clock()
        val clip = Clip(
            text = inc.text?.takeIf { inc.png == null },
            png = inc.png,
            id = if (legacy) ClipIds.derived(content.bytes()) else inc.id!!,
            ts = inc.ts?.takeIf { it > 0 } ?: now,
            origin = inc.origin?.takeIf { it.isNotBlank() } ?: fromLink,
            mime = inc.mime,
            name = inc.name,
            legacyImage = inc.legacyImage
        )
        val relayTo: List<String>
        synchronized(lock) {
            if (!enabled) return ignored("off", clip, via, Outcome.OFF)
            if (content.size > content.limit) return ignored("size", clip, via, Outcome.SIZE)
            val hash = if (legacy) clip.id else ClipIds.derived(content.bytes())
            // An old-format clip has no id of its own: it is "seen" while it is what the phone holds,
            // so the same words copied again later on that computer still come through.
            val seenBefore = if (legacy) hash == currentHash else clip.id in seen
            if (seenBefore) return ignored("seen", clip, via, Outcome.SEEN)
            if (current != null && clip.ts <= localTs - SKEW_MS) return ignored("older", clip, via, Outcome.OLDER)

            val ok = runCatching { port.apply(clip) }.getOrDefault(false)
            remember(clip.id)
            current = clip
            currentHash = hash
            currentSource = fromLink
            currentAt = now
            localTs = clip.ts
            appliedHash = hash
            appliedAt = now
            if (ok) log(line("applied", clip, via))
            relayTo = port.readyLinks().filter { !sameDevice(it, fromLink) && !sameDevice(it, clip.origin) }
        }
        relayTo.forEach { send(it, clip) }
        return Outcome.APPLIED
    }

    /** The phone's clipboard as the watcher read it. */
    fun onLocal(read: LocalClip, trigger: Trigger): Outcome = local(read, trigger, null).first

    /**
     * The user asked to send this clip (notification, tile, share sheet, widget): a new clip even if it
     * equals the last one, sent with sync on or off. [only] limits it to one computer.
     * Returns how many computers got it.
     */
    fun explicit(read: LocalClip, only: String? = null): Int = local(read, Trigger.EXPLICIT, only).second

    private fun local(read: LocalClip, trigger: Trigger, only: String?): Pair<Outcome, Int> {
        val content = contentOf(read.text, read.png) ?: return Outcome.EMPTY to 0
        val now = clock()
        val hash = ClipIds.derived(content.bytes())
        val clip: Clip
        synchronized(lock) {
            if (trigger != Trigger.EXPLICIT) {
                val tag = read.appliedTag
                if (tag != null && (tag in seen || tag == current?.id)) {
                    if (read.stamp != null) lastStamp = read.stamp
                    return Outcome.OWN_APPLY to 0
                }
                if (tag == null && read.stamp == null && hash == appliedHash && now - appliedAt < OWN_WRITE_WINDOW_MS) {
                    return Outcome.OWN_APPLY to 0
                }
                if (read.stamp != null && read.stamp == lastStamp) return Outcome.UNCHANGED to 0
                if (read.stamp == null && hash == currentHash &&
                    (trigger == Trigger.POLL || now - currentAt < DUPLICATE_WINDOW_MS)
                ) return Outcome.UNCHANGED to 0
            }
            val ts = if (trigger == Trigger.EXPLICIT) now else (read.stamp?.takeIf { it in 1..now } ?: now)
            val fresh = Clip(
                read.text?.takeIf { read.png == null }, read.png, newId(), ts, phoneId,
                mime = read.mime, name = read.name, legacyImage = read.legacyImage
            )
            if (trigger == Trigger.POLL && current == null && now - ts > CATCH_UP_MAX_AGE_MS) {
                // Already on the phone long before H.I.B looked: remember it, don't push it as news.
                lastStamp = read.stamp
                currentHash = hash
                return Outcome.UNCHANGED to 0
            }
            if (trigger != Trigger.EXPLICIT && !enabled) {
                lastStamp = read.stamp
                return ignored("off", fresh, VIA_PHONE, Outcome.OFF) to 0
            }
            if (content.size > content.limit) {
                lastStamp = read.stamp
                return ignored("size", fresh, VIA_PHONE, Outcome.SIZE) to 0
            }
            clip = fresh
            remember(clip.id)
            current = clip
            currentHash = hash
            currentSource = null
            currentAt = now
            localTs = clip.ts
            if (read.stamp != null) lastStamp = read.stamp
            log(line("copied", clip, VIA_PHONE))
        }
        val targets = if (only != null) listOf(only) else port.readyLinks()
        val reached = targets.count { send(it, clip) }
        return Outcome.COPIED to reached
    }

    /** A link to a computer became ready: send it the current clip if it is real and recent (§16.2). */
    fun onLinkReady(linkId: String): Boolean {
        val clip = synchronized(lock) {
            if (!enabled) return false
            val c = current ?: return false
            if (clock() - c.ts > CATCH_UP_MAX_AGE_MS) return false
            if (sameDevice(linkId, c.origin)) return false
            currentSource?.let { if (sameDevice(linkId, it)) return false }
            c
        }
        return send(linkId, clip)
    }

    private fun send(linkId: String, clip: Clip): Boolean {
        val ok = runCatching { port.send(linkId, clip) }.getOrDefault(false)
        if (ok) log(line("sent", clip, VIA_PHONE))
        return ok
    }

    private fun remember(id: String) {
        seen.remove(id)
        seen.add(id)
        while (seen.size > SEEN_MAX) seen.remove(seen.first())
    }

    private fun ignored(why: String, clip: Clip, via: String, outcome: Outcome): Outcome {
        log(line("ignored($why)", clip, via))
        return outcome
    }

    private fun line(event: String, clip: Clip, via: String) =
        "[clip] $event id=${ClipIds.short(clip.id)} from=${clip.origin.replace(' ', '_')} via=$via"

    private class Content(val text: String?, val png: String?) {
        val size: Long = text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: ClipIds.base64Size(png!!)
        val limit: Long = if (text != null) MAX_TEXT_BYTES else MAX_PNG_BYTES
        fun bytes(): ByteArray = text?.toByteArray(Charsets.UTF_8) ?: (ClipIds.decodeBase64(png!!) ?: png.toByteArray())
    }

    private fun contentOf(text: String?, png: String?): Content? = when {
        !png.isNullOrEmpty() -> Content(null, png)
        !text.isNullOrEmpty() -> Content(text, null)
        else -> null
    }
}
