package com.sameerasw.airsync.home

/*
 * H.I.B Home — no accidental disconnects. Pausing a computer, pausing H.I.B or forgetting a computer is
 * never one tap: it sits in a ⋯ menu behind a confirmation, and afterwards a snackbar offers Undo for a
 * few seconds. This ledger is the Undo bookkeeping; [UndoPlan] says what Undo has to do.
 *
 * Pure logic (no Android types) so it runs in JVM unit tests.
 */

enum class UndoKind {
    /** One computer's connection was paused. */
    PAUSE_COMPUTER,
    /** H.I.B as a whole was paused (every computer). */
    PAUSE_ALL,
    /** A computer was forgotten (its pairing removed). */
    FORGET
}

data class UndoTicket(
    val id: Long,
    val kind: UndoKind,
    /** The computer it was about (null for [UndoKind.PAUSE_ALL]). */
    val computer: String?,
    /** That computer rode the primary connection (decides how to bring it back). */
    val viaPrimaryLink: Boolean,
    val at: Long
) {
    /** "Paused MacBook Pro", "H.I.B paused", "Forgot Office PC". */
    val message: String
        get() = when (kind) {
            UndoKind.PAUSE_COMPUTER -> "Paused ${computer ?: "computer"}"
            UndoKind.PAUSE_ALL -> "H.I.B paused on every computer"
            UndoKind.FORGET -> "Forgot ${computer ?: "computer"}"
        }
}

class UndoLedger(private val windowMs: Long = WINDOW_MS) {
    private var nextId = 1L
    private val open = LinkedHashMap<Long, UndoTicket>()

    /** Something reversible just happened: returns the ticket the snackbar's Undo refers to. */
    fun record(kind: UndoKind, computer: String?, viaPrimaryLink: Boolean, now: Long): UndoTicket {
        expire(now)
        val t = UndoTicket(nextId++, kind, computer, viaPrimaryLink, now)
        open[t.id] = t
        return t
    }

    /** Undo pressed: the ticket to reverse, once, while it is still fresh; null when too late or used. */
    fun undo(id: Long, now: Long): UndoTicket? {
        val t = open.remove(id) ?: return null
        return t.takeIf { now - it.at <= windowMs }
    }

    /** The snackbar went away without Undo. */
    fun dismiss(id: Long) {
        open.remove(id)
    }

    fun expire(now: Long) {
        open.values.removeAll { now - it.at > windowMs }
    }

    val pending: List<UndoTicket> get() = open.values.toList()

    companion object {
        /** A bit longer than a long snackbar (10 s) so a late tap still counts. */
        const val WINDOW_MS = 12_000L
    }
}

/** What Undo does, step by step. */
enum class UndoStep {
    /** Let the primary connection auto-reconnect again (clear "the user disconnected"). */
    CLEAR_MANUAL_DISCONNECT,
    /** Connect the computer on the primary connection to its last known address. */
    CONNECT_PRIMARY,
    /** Turn the other computer's session back on. */
    ENABLE_COMPUTER,
    /** Ask that session to try right away. */
    RECONNECT_COMPUTER,
    /** Resume H.I.B as a whole. */
    RESUME_ALL,
    /** Put the forgotten pairing back. */
    RESTORE_PAIRING,
    /** Put the forgotten primary pairing (its last-device record) back. */
    RESTORE_PRIMARY_RECORD
}

object UndoPlan {
    fun steps(t: UndoTicket): List<UndoStep> = when (t.kind) {
        UndoKind.PAUSE_COMPUTER ->
            if (t.viaPrimaryLink) listOf(UndoStep.CLEAR_MANUAL_DISCONNECT, UndoStep.CONNECT_PRIMARY)
            else listOf(UndoStep.ENABLE_COMPUTER, UndoStep.RECONNECT_COMPUTER)
        UndoKind.PAUSE_ALL -> listOf(UndoStep.RESUME_ALL)
        UndoKind.FORGET ->
            if (t.viaPrimaryLink) listOf(UndoStep.RESTORE_PRIMARY_RECORD, UndoStep.RESTORE_PAIRING, UndoStep.CLEAR_MANUAL_DISCONNECT, UndoStep.CONNECT_PRIMARY)
            else listOf(UndoStep.RESTORE_PAIRING)
    }
}

/** The words of the "Pause connection" sheet: what pausing does, said plainly. */
object PauseCopy {
    fun title(computer: String) = "Pause connection to $computer?"

    fun body(computer: String) =
        "$computer stops getting this phone's notifications, clipboard, calls and battery, and Flick, Play and " +
            "files can't go there. It won't reconnect by itself. Your pairing stays — tap Resume on its card " +
            "any time."

    const val CONFIRM = "Pause connection"

    fun forgetTitle(computer: String) = "Forget $computer?"

    fun forgetBody(computer: String) =
        "The phone removes this computer's pairing and disconnects. To use it again, scan the QR code in H.I.B " +
            "on $computer. Your other computers stay paired."

    const val FORGET_CONFIRM = "Forget"

    const val PAUSE_ALL_TITLE = "Pause H.I.B?"
    const val PAUSE_ALL_BODY =
        "Every computer disconnects and H.I.B stops syncing until you resume. Nothing is forgotten."
    const val PAUSE_ALL_CONFIRM = "Pause H.I.B"
}
