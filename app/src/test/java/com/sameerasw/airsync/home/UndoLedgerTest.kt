package com.sameerasw.airsync.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No accidental disconnects: every pause / forget can be undone from the snackbar for a short while. */
class UndoLedgerTest {

    @Test
    fun undoWithinTheWindowReturnsTheTicketOnce() {
        val ledger = UndoLedger(windowMs = 10_000)
        val t = ledger.record(UndoKind.PAUSE_COMPUTER, "Mac", viaPrimaryLink = true, now = 1_000)
        assertEquals("Paused Mac", t.message)
        val back = ledger.undo(t.id, now = 5_000)
        assertNotNull(back)
        assertEquals("Mac", back!!.computer)
        // A second tap does nothing.
        assertNull(ledger.undo(t.id, now = 5_100))
    }

    @Test
    fun undoTooLateDoesNothing() {
        val ledger = UndoLedger(windowMs = 10_000)
        val t = ledger.record(UndoKind.PAUSE_COMPUTER, "PC", viaPrimaryLink = false, now = 0)
        assertNull(ledger.undo(t.id, now = 10_001))
    }

    @Test
    fun dismissedTicketCannotBeUndone() {
        val ledger = UndoLedger()
        val t = ledger.record(UndoKind.FORGET, "PC", viaPrimaryLink = false, now = 0)
        ledger.dismiss(t.id)
        assertNull(ledger.undo(t.id, now = 1))
    }

    @Test
    fun ticketsAreIndependentAndOldOnesExpire() {
        val ledger = UndoLedger(windowMs = 10_000)
        val a = ledger.record(UndoKind.PAUSE_COMPUTER, "Mac", true, now = 0)
        val b = ledger.record(UndoKind.PAUSE_COMPUTER, "PC", false, now = 2_000)
        assertEquals(2, ledger.pending.size)
        assertEquals("PC", ledger.undo(b.id, now = 3_000)!!.computer)
        ledger.record(UndoKind.PAUSE_ALL, null, false, now = 11_000) // expires a
        assertTrue(ledger.pending.none { it.id == a.id })
        assertNull(ledger.undo(a.id, now = 11_000))
    }

    @Test
    fun undoPlanReconnectsTheRightWay() {
        val ledger = UndoLedger()
        val primary = ledger.record(UndoKind.PAUSE_COMPUTER, "Mac", viaPrimaryLink = true, now = 0)
        assertEquals(listOf(UndoStep.CLEAR_MANUAL_DISCONNECT, UndoStep.CONNECT_PRIMARY), UndoPlan.steps(primary))

        val other = ledger.record(UndoKind.PAUSE_COMPUTER, "PC", viaPrimaryLink = false, now = 0)
        assertEquals(listOf(UndoStep.ENABLE_COMPUTER, UndoStep.RECONNECT_COMPUTER), UndoPlan.steps(other))

        val all = ledger.record(UndoKind.PAUSE_ALL, null, viaPrimaryLink = false, now = 0)
        assertEquals(listOf(UndoStep.RESUME_ALL), UndoPlan.steps(all))
        assertEquals("H.I.B paused on every computer", all.message)
    }

    @Test
    fun undoForgetRestoresThePairing() {
        val ledger = UndoLedger()
        val pc = ledger.record(UndoKind.FORGET, "PC", viaPrimaryLink = false, now = 0)
        assertEquals(listOf(UndoStep.RESTORE_PAIRING), UndoPlan.steps(pc))
        assertEquals("Forgot PC", pc.message)
        val mac = ledger.record(UndoKind.FORGET, "Mac", viaPrimaryLink = true, now = 0)
        assertEquals(UndoStep.RESTORE_PRIMARY_RECORD, UndoPlan.steps(mac).first())
        assertTrue(UndoStep.CONNECT_PRIMARY in UndoPlan.steps(mac))
    }

    @Test
    fun pauseSheetSaysWhatHappensWithoutSayingMac() {
        val body = PauseCopy.body("Office PC")
        assertTrue(body.contains("Office PC"))
        assertTrue(body.contains("pairing stays"))
        assertTrue(!PauseCopy.title("Office PC").contains("Mac"))
    }
}
