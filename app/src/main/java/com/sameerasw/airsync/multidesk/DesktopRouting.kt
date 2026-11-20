package com.sameerasw.airsync.multidesk

/**
 * H.I.B multi-desktop — decides which connected computer(s) an outgoing phone message goes to.
 * Pure logic (no Android types) so it is unit-tested on the JVM.
 */
object DesktopRouting {

    /** Phone state every computer wants: sent to all connected computers. */
    val BROADCAST_TYPES = setOf(
        "notification",
        "notificationUpdate",
        "status",
        "clipboardUpdate",
        "clipboardImageUpdate",
        "call_event",
        "call_progress",
        "ringState"
    )

    /** Answers to a desktop's request; when sent asynchronously they go back to whoever asked last. */
    val REPLY_TYPES = setOf(
        "pong",
        "dismissalResponse",
        "notificationActionResponse",
        "mediaControlResponse",
        "volumeControlResponse",
        "toggleAppNotifResponse",
        "toggleNowPlayingResponse",
        "callControlResponse",
        "browseData",
        "phoneSearchResults",
        "approvalResponse",
        "phoneActionResult",
        "findLocation",
        "findLockResult",
        "findMessageResult",
        "findRingState",
        "findPong",
        "fileChunkAck",
        "transferVerified",
        "desktopsList"
    )

    /** Inbound types that are background chatter, not a request — they don't make a desktop "the requester". */
    val NON_REQUEST_INBOUND = setOf(
        "ping", "status", "macInfo", "macVolume", "modifierStatus", "deckState", "scenesList",
        "findRelayConfig", "fileChunk", "fileChunkAck", "flickLanded", "transferVerified",
        "screenFrame", "screenShareState", "phoneAudioState", "findMacRingState", "sceneRunResult",
        "deckActionResult", "desktopsList", "desktopActivity", "flickStart"
    )

    const val REQUESTER_WINDOW_MS = 30_000L

    private val typeRegex = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")

    /** Reads the envelope type from the head of the JSON without parsing a possibly multi-MB payload. */
    fun messageType(json: String): String? = typeRegex.find(json.take(160))?.groupValues?.get(1)

    sealed class Route {
        /** The primary (legacy single-desktop) connection, falling back to another computer if it is down. */
        object Primary : Route()
        object All : Route()
        data class Only(val desktopId: String) : Route()
    }

    /**
     * @param sourceId the desktop whose inbound message is being handled on this thread right now, if any
     * @param recentRequesterId the desktop that most recently sent a request (within [REQUESTER_WINDOW_MS])
     */
    fun decide(type: String?, sourceId: String?, recentRequesterId: String?): Route = when {
        type != null && type in BROADCAST_TYPES -> Route.All
        sourceId != null -> Route.Only(sourceId)
        type != null && type in REPLY_TYPES && recentRequesterId != null -> Route.Only(recentRequesterId)
        else -> Route.Primary
    }

    fun countsAsRequest(type: String?): Boolean = type != null && type !in NON_REQUEST_INBOUND
}

/**
 * Bookkeeping for desktop→desktop transfers relayed through the phone. The phone forwards init/chunks/
 * complete from the source to the target, and acks/verification from the target back to the source,
 * so the source's own ack window paces the whole stream and nothing is stored on the phone.
 */
class RelayTable(private val now: () -> Long = System::currentTimeMillis) {
    data class Entry(
        val id: String,
        val sourceId: String,
        val targetId: String,
        val name: String,
        val size: Long,
        val startedAt: Long,
        var completed: Boolean = false,
        var completedAt: Long = 0L,
        var lastActivity: Long = startedAt
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun start(id: String, sourceId: String, targetId: String, name: String, size: Long): Entry {
        val e = Entry(id, sourceId, targetId, name, size, now())
        entries[id] = e
        return e
    }

    @Synchronized
    fun get(id: String): Entry? = entries[id]?.also { it.lastActivity = now() }

    @Synchronized
    fun markComplete(id: String) {
        entries[id]?.let { it.completed = true; it.completedAt = now(); it.lastActivity = it.completedAt }
    }

    @Synchronized
    fun remove(id: String): Entry? = entries.remove(id)

    /** Ids whose source or target is [desktopId] (used to cancel relays when a computer drops). */
    @Synchronized
    fun involving(desktopId: String): List<Entry> =
        entries.values.filter { it.sourceId == desktopId || it.targetId == desktopId }

    /** Drops finished relays after [keepMs] and stalled ones after [stallMs]. Returns the dropped entries. */
    @Synchronized
    fun prune(keepMs: Long = 60_000L, stallMs: Long = 120_000L): List<Entry> {
        val t = now()
        val dead = entries.values.filter {
            (it.completed && t - it.completedAt > keepMs) || (t - it.lastActivity > stallMs)
        }
        dead.forEach { entries.remove(it.id) }
        return dead
    }

    @Synchronized
    fun size(): Int = entries.size
}
