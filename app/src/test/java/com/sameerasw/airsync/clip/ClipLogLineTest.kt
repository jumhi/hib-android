package com.sameerasw.airsync.clip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding 1 / §16.3: the logcat line Android writes when it refuses H.I.B a background clipboard read. */
class ClipLogLineTest {
    private val pkg = "com.sameerasw.airsync"

    @Test
    fun matchesTheLineFromTheUsersPhone() {
        // logcat -v brief
        assertTrue(ClipLogLine.isDenialFor(
            "E/ClipboardService( 1834): Denying clipboard access to com.sameerasw.airsync, application is not in focus nor is it a system service for user 0",
            pkg
        ))
        // logcat -v threadtime
        assertTrue(ClipLogLine.isDenialFor(
            "11-25 14:02:11.532  1834  5120 E ClipboardService: Denying clipboard access to com.sameerasw.airsync, application is not in focus",
            pkg
        ))
        assertTrue(ClipLogLine.isDenialFor("W ClipboardService: Denying clipboard access to com.sameerasw.airsync", pkg))
    }

    @Test
    fun ignoresOtherPackagesAndOtherLines() {
        assertFalse(ClipLogLine.isDenialFor(
            "E/ClipboardService( 1834): Denying clipboard access to com.google.android.gm, application is not in focus", pkg
        ))
        assertFalse(ClipLogLine.isDenialFor(
            "E/ClipboardService( 1834): Denying clipboard access to com.sameerasw.airsync.helper, application is not in focus", pkg
        ))
        assertFalse(ClipLogLine.isDenialFor(
            "E/ClipboardService( 1834): Denying clipboard access to com.sameerasw.airsyncx, application is not in focus", pkg
        ))
        assertFalse(ClipLogLine.isDenialFor("D/ClipboardService( 1834): setPrimaryClip by com.sameerasw.airsync", pkg))
        assertFalse(ClipLogLine.isDenialFor("", pkg))
    }
}
