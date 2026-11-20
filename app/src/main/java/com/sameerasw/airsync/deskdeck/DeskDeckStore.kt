package com.sameerasw.airsync.deskdeck

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** What a tile asks for when it needs a user-supplied argument. */
enum class DeckArgKind { NONE, MAC_APP, SHORTCUT }

enum class DeckAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val argKind: DeckArgKind = DeckArgKind.NONE
) {
    LOCK("lock", "Lock computer", Icons.Rounded.Lock),
    SCREENSAVER("screensaver", "Screensaver", Icons.Rounded.Wallpaper),
    PLAY_PAUSE("play_pause", "Play / Pause", Icons.Rounded.PlayArrow),
    PREVIOUS("previous", "Previous", Icons.Rounded.SkipPrevious),
    NEXT("next", "Next", Icons.Rounded.SkipNext),
    VOL_DOWN("vol_down", "Volume −", Icons.Rounded.VolumeDown),
    VOL_UP("vol_up", "Volume +", Icons.Rounded.VolumeUp),
    MUTE("mute", "Mute", Icons.Rounded.VolumeOff),
    MIC_MUTE("mic_mute", "Mic mute", Icons.Rounded.MicOff),
    PHONE_AUDIO("phone_audio", "Phone audio on computer", Icons.Rounded.Speaker),
    OPEN_APP("open_app", "Open app", Icons.Rounded.Apps, DeckArgKind.MAC_APP),
    SHORTCUT("shortcut", "Run Shortcut", Icons.Rounded.AutoAwesome, DeckArgKind.SHORTCUT),
    MISSION_CONTROL("mission_control", "Mission Control", Icons.Rounded.ViewQuilt),
    SHOW_DESKTOP("show_desktop", "Show Desktop", Icons.Rounded.DesktopMac),
    SCREENSHOT("screenshot", "Screenshot", Icons.Rounded.PhotoCamera),
    DND("dnd", "Do Not Disturb", Icons.Rounded.DarkMode, DeckArgKind.SHORTCUT),
    BRIGHTNESS_DOWN("brightness_down", "Brightness −", Icons.Rounded.BrightnessLow),
    BRIGHTNESS_UP("brightness_up", "Brightness +", Icons.Rounded.BrightnessHigh);

    companion object {
        fun fromId(id: String): DeckAction? = entries.firstOrNull { it.id == id }
    }
}

data class DeckTile(val action: DeckAction, val arg: String? = null) {
    val title: String
        get() = when {
            action == DeckAction.OPEN_APP && !arg.isNullOrBlank() -> arg
            action == DeckAction.SHORTCUT && !arg.isNullOrBlank() -> arg
            else -> action.label
        }
}

private val Context.deskDeckStore by preferencesDataStore(name = "hib_desk_deck")

object DeskDeckStore {
    const val MIN_TILES = 6
    const val MAX_TILES = 12

    private val KEY_TILES = stringPreferencesKey("tiles_json")
    private val KEY_COUNT = intPreferencesKey("tile_count")

    val defaultTiles = listOf(
        DeckTile(DeckAction.LOCK),
        DeckTile(DeckAction.PLAY_PAUSE),
        DeckTile(DeckAction.MUTE),
        DeckTile(DeckAction.VOL_DOWN),
        DeckTile(DeckAction.VOL_UP),
        DeckTile(DeckAction.SCREENSHOT),
        DeckTile(DeckAction.MISSION_CONTROL),
        DeckTile(DeckAction.SHOW_DESKTOP),
        DeckTile(DeckAction.PHONE_AUDIO),
        DeckTile(DeckAction.BRIGHTNESS_DOWN),
        DeckTile(DeckAction.BRIGHTNESS_UP),
        DeckTile(DeckAction.SCREENSAVER)
    )

    /** Always returns MAX_TILES tiles; the UI shows the first [tileCount]. */
    fun tiles(context: Context): Flow<List<DeckTile>> =
        context.deskDeckStore.data.map { prefs ->
            val parsed = prefs[KEY_TILES]?.let { decode(it) } ?: emptyList()
            (parsed + defaultTiles.drop(parsed.size)).take(MAX_TILES)
        }

    fun tileCount(context: Context): Flow<Int> =
        context.deskDeckStore.data.map { (it[KEY_COUNT] ?: 9).coerceIn(MIN_TILES, MAX_TILES) }

    suspend fun saveTiles(context: Context, tiles: List<DeckTile>) {
        context.deskDeckStore.edit { it[KEY_TILES] = encode(tiles) }
    }

    suspend fun saveTileCount(context: Context, count: Int) {
        context.deskDeckStore.edit { it[KEY_COUNT] = count.coerceIn(MIN_TILES, MAX_TILES) }
    }

    private fun encode(tiles: List<DeckTile>): String {
        val arr = JSONArray()
        tiles.forEach { t ->
            val o = JSONObject().put("action", t.action.id)
            if (!t.arg.isNullOrBlank()) o.put("arg", t.arg)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decode(json: String): List<DeckTile> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val action = DeckAction.fromId(o.optString("action")) ?: return@mapNotNull null
            DeckTile(action, o.optString("arg").takeIf { it.isNotBlank() })
        }
    } catch (_: Exception) {
        emptyList()
    }
}
