package com.sameerasw.airsync.multidesk

import android.content.Context
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The Point & Flick screen map (`hib_flick_screens_v1`, SPACE.md §7.6). */
object FlickScreenStore {
    fun flow(context: Context): Flow<List<SavedScreenAim>> =
        DataStoreManager.getInstance(context.applicationContext).getFlickScreensJson().map { ScreenMap.decode(it) }

    /** Saves captured screens, replacing any earlier aim for the same screens. */
    suspend fun save(context: Context, captured: List<SavedScreenAim>) {
        if (captured.isEmpty()) return
        DataStoreManager.getInstance(context.applicationContext).updateFlickScreensJson { json ->
            ScreenMap.encode(ScreenMap.replace(ScreenMap.decode(json), captured))
        }
    }

    suspend fun forgetAll(context: Context) {
        DataStoreManager.getInstance(context.applicationContext).updateFlickScreensJson { ScreenMap.encode(emptyList()) }
    }
}

/**
 * Lights on the computers while the Flick screen is open (SPACE.md §7.4–7.5): which screen is aimed at
 * (`flickAim`) and the Point setup target (`aimSetup`). The Flick screen feeds it; the activity clears it
 * when it pauses.
 */
object FlickPointing {
    private val aim = AimLight()
    private val setup = SetupLight()
    @Volatile private var setupShowing = false

    /** Only while the Flick screen is in front; nothing is lit from the background. */
    @Volatile private var active = false

    @Synchronized
    fun resume() {
        active = true
    }

    /** The computers the phone is connected to now (screen lists as last reported). */
    fun computers(): List<ScreenComputer> {
        val screens = DesktopHub.screens.value
        return DesktopHub.desktops.value.filter { it.isConnected }.map { d ->
            ScreenComputer(d.name, screens[PairedDesktops.normalizeName(d.name)])
        }
    }

    /** Light [target] (null = nothing). Call often; it only sends on a change and as a keep-alive. */
    @Synchronized
    fun aimAt(target: ScreenTarget?, now: Long = System.currentTimeMillis()): Boolean {
        if (!active) return false
        val sends = aim.update(target, now)
        sends.forEach { DesktopHub.sendToComputer(it.computer, it.json()) }
        return sends.isNotEmpty()
    }

    @Synchronized
    fun setupAt(target: ScreenTarget?, step: Int, total: Int, progress: Float, force: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (!active) return
        setupShowing = target != null
        setup.update(target, step, total, progress, now, force).forEach { DesktopHub.sendToComputer(it.computer, it.json) }
    }

    @Synchronized
    fun endSetup() {
        setupShowing = false
        setup.end(DesktopHub.connectedIds()).forEach { DesktopHub.sendToComputer(it.computer, it.json) }
    }

    /** Flick closes or goes to the background: nothing lit anywhere until [resume]. */
    @Synchronized
    fun clearAll() {
        active = false
        val connected = DesktopHub.connectedIds()
        aim.clear(connected).forEach { DesktopHub.sendToComputer(it.computer, it.json()) }
        if (setupShowing) endSetup()
    }
}
