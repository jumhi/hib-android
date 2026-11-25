package com.sameerasw.airsync.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** macOS virtual keycodes the Remote sends (`keypress` and `key` stay in them; Windows maps them). */
object MacKey {
    const val RETURN = 36
    const val TAB = 48
    const val SPACE = 49
    const val DELETE = 51
    const val ESCAPE = 53
    const val COMMAND = 55
    const val SHIFT = 56
    const val OPTION = 58
    const val CONTROL = 59
    const val F4 = 118
    const val FORWARD_DELETE = 117
    const val LEFT = 123
    const val RIGHT = 124
    const val DOWN = 125
    const val UP = 126
}

/**
 * The four modifier keys of the key strip, by role, with each computer's words (SPACE.md §15.5): on a Mac
 * ⌘ ⌥ ⇧ ⌃, on a PC Ctrl Alt Shift Win. [wire] is the `keypress` modifier name.
 */
enum class ModKey(private val mac: String, private val win: String, private val macLabel: String, private val winLabel: String) {
    PRIMARY("command", "ctrl", "⌘", "Ctrl"),
    ALT("option", "alt", "⌥", "Alt"),
    SHIFT("shift", "shift", "⇧", "Shift"),
    SECONDARY("ctrl", "win", "⌃", "Win");

    fun wire(isWindows: Boolean) = if (isWindows) win else mac
    fun label(isWindows: Boolean) = if (isWindows) winLabel else macLabel

    companion object {
        /** Wire name → label, in each platform's usual reading order (⌃⌥⇧⌘ on a Mac; Ctrl+Alt+Shift+Win on a PC). */
        fun labels(mods: List<String>, isWindows: Boolean): List<String> {
            val order = if (isWindows) listOf("ctrl", "alt", "shift", "win") else listOf("ctrl", "option", "shift", "command")
            val names = mapOf(
                "ctrl" to if (isWindows) "Ctrl" else "⌃", "control" to if (isWindows) "Ctrl" else "⌃",
                "option" to if (isWindows) "Alt" else "⌥", "alt" to if (isWindows) "Alt" else "⌥",
                "shift" to if (isWindows) "Shift" else "⇧",
                "command" to if (isWindows) "Ctrl" else "⌘", "cmd" to if (isWindows) "Ctrl" else "⌘",
                "win" to "Win"
            )
            val norm = mods.map { m -> when (m) { "control" -> "ctrl"; "alt" -> if (isWindows) "alt" else "option"; "cmd" -> "command"; else -> m } }
            return norm.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }.mapNotNull { names[it] }
        }
    }
}

/** A character on a US keyboard: its key (macOS keycode) and whether Shift is needed. */
object UsKeyboard {
    data class Stroke(val keycode: Int, val shift: Boolean)

    private val plain: Map<Char, Int> = buildMap {
        "asdfhgzxcv".forEachIndexed { i, c -> put(c, i) }
        "bqweryt".forEachIndexed { i, c -> put(c, 11 + i) }
        putAll(
            mapOf(
                '1' to 18, '2' to 19, '3' to 20, '4' to 21, '6' to 22, '5' to 23, '=' to 24, '9' to 25, '7' to 26,
                '-' to 27, '8' to 28, '0' to 29, ']' to 30, 'o' to 31, 'u' to 32, '[' to 33, 'i' to 34, 'p' to 35,
                'l' to 37, 'j' to 38, '\'' to 39, 'k' to 40, ';' to 41, '\\' to 42, ',' to 43, '/' to 44, 'n' to 45,
                'm' to 46, '.' to 47, '`' to 50, ' ' to MacKey.SPACE, '\n' to MacKey.RETURN, '\r' to MacKey.RETURN,
                '\t' to MacKey.TAB
            )
        )
    }

    private val shifted: Map<Char, Int> = mapOf(
        '!' to 18, '@' to 19, '#' to 20, '$' to 21, '%' to 23, '^' to 22, '&' to 26, '*' to 28, '(' to 25, ')' to 29,
        '_' to 27, '+' to 24, '{' to 33, '}' to 30, '|' to 42, ':' to 41, '"' to 39, '<' to 43, '>' to 47, '?' to 44,
        '~' to 50
    )

    fun stroke(c: Char): Stroke? {
        plain[c]?.let { return Stroke(it, false) }
        shifted[c]?.let { return Stroke(it, true) }
        if (c in 'A'..'Z') return plain[c.lowercaseChar()]?.let { Stroke(it, true) }
        return null
    }
}

/**
 * Sticky modifiers on the key strip (SPACE.md §15.5): tap = for the next key only, double-tap = locked
 * (highlighted) until tapped again. Immutable, keyed by the `keypress` wire name, in the order they were turned on.
 */
data class StickyMods(
    private val states: Map<String, State> = emptyMap(),
    private val lastTap: Map<String, Long> = emptyMap()
) {
    enum class State { OFF, ONCE, LOCKED }

    fun state(mod: String): State = states[mod] ?: State.OFF
    fun locked(mod: String) = state(mod) == State.LOCKED
    val active: List<String> get() = states.filterValues { it != State.OFF }.keys.toList()

    fun tap(mod: String, now: Long): StickyMods {
        val next = when (state(mod)) {
            State.OFF -> State.ONCE
            State.ONCE -> if (now - (lastTap[mod] ?: Long.MIN_VALUE / 2) <= DOUBLE_TAP_MS) State.LOCKED else State.OFF
            State.LOCKED -> State.OFF
        }
        val s = LinkedHashMap(states).apply { if (next == State.OFF) remove(mod) else put(mod, next) }
        return StickyMods(s, lastTap + (mod to now))
    }

    /** The modifiers for the key going out now; one-shot ones turn off, locked ones stay. */
    fun consume(): Pair<List<String>, StickyMods> =
        active to copy(states = states.filterValues { it == State.LOCKED })

    fun clear() = StickyMods()

    companion object {
        const val DOUBLE_TAP_MS = 350L
    }
}

/** Keys and phone-keyboard text with the sticky modifiers applied (SPACE.md §15.5). */
object KeyCompose {
    /** A strip key ([keycode]) with whatever modifiers are on. */
    fun key(keycode: Int, sticky: StickyMods): Pair<String, StickyMods> {
        val (mods, next) = sticky.consume()
        return RemoteInput.key(keycode, mods) to next
    }

    /**
     * Text typed on the phone keyboard. With no modifier on it goes out as `type`. While a modifier is on, each
     * character goes as `keypress` with the modifiers (+ Shift when the US layout needs it) — ⌘ then "s" is ⌘S —
     * and characters after a one-shot modifier is used go back to `type`. A character with no US key goes as text.
     */
    fun typed(text: String, sticky: StickyMods): Pair<List<String>, StickyMods> {
        val out = mutableListOf<String>()
        var s = sticky
        val plain = StringBuilder()
        fun flushPlain() { if (plain.isNotEmpty()) { out += RemoteInput.type(plain.toString()); plain.clear() } }
        for (c in text) {
            if (s.active.isEmpty()) { plain.append(c); continue }
            flushPlain()
            val (mods, next) = s.consume()
            s = next
            val stroke = UsKeyboard.stroke(c)
            out += if (stroke == null) RemoteInput.type(c.toString())
            else RemoteInput.key(stroke.keycode, if (stroke.shift && "shift" !in mods) mods + "shift" else mods)
        }
        flushPlain()
        return out to s
    }

    /** The key to hold (`key`) for a modifier's wire name, or null (the Windows key has no macOS keycode). */
    fun holdKeycode(wire: String): Int? = when (wire) {
        "command", "cmd" -> MacKey.COMMAND
        "option", "alt" -> MacKey.OPTION
        "shift" -> MacKey.SHIFT
        "ctrl", "control" -> MacKey.CONTROL
        else -> null
    }

    /** Sticky modifiers around a click (⌘-click, ⇧-click): `key` downs before, ups after (reverse order). */
    fun holdAround(mods: List<String>): Pair<List<String>, List<String>> {
        val codes = mods.mapNotNull { holdKeycode(it) }.distinct()
        return codes.map { RemoteInput.hold(it, true) } to codes.reversed().map { RemoteInput.hold(it, false) }
    }
}

/** One key with modifiers (`keypress`). */
data class Combo(val keycode: Int, val mods: List<String>)

/** A chip on the shortcut row: [title] says what it does, [keys] what it presses. */
data class ShortcutItem(
    val id: String,
    val title: String,
    val keys: String,
    val combo: Combo?,
    /** The app switcher: press-and-hold keeps ⌘/Alt held. */
    val switcher: Boolean = false,
    val arrow: Boolean = false
)

/** The shortcut row (SPACE.md §15.5), with each computer's words and keys. */
object Shortcuts {
    fun row(isWindows: Boolean): List<ShortcutItem> {
        val p = if (isWindows) "ctrl" else "command"
        fun k(c: String) = if (isWindows) "Ctrl+$c" else "⌘$c"
        return listOf(
            ShortcutItem("switch", "Switch apps", if (isWindows) "Alt+Tab" else "⌘Tab", null, switcher = true),
            ShortcutItem("copy", "Copy", k("C"), Combo(8, listOf(p))),
            ShortcutItem("paste", "Paste", k("V"), Combo(9, listOf(p))),
            ShortcutItem("undo", "Undo", k("Z"), Combo(6, listOf(p))),
            ShortcutItem("save", "Save", k("S"), Combo(1, listOf(p))),
            ShortcutItem("find", "Find", k("F"), Combo(3, listOf(p))),
            ShortcutItem("close", "Close", k("W"), Combo(13, listOf(p))),
            if (isWindows) ShortcutItem("quit", "Close app", "Alt+F4", Combo(MacKey.F4, listOf("alt")))
            else ShortcutItem("quit", "Quit", "⌘Q", Combo(12, listOf("command"))),
            if (isWindows) ShortcutItem("search", "Search", "Win+S", Combo(1, listOf("win")))
            else ShortcutItem("search", "Spotlight", "⌘Space", Combo(MacKey.SPACE, listOf("command"))),
            if (isWindows) ShortcutItem("overview", "Task view", "Win+Tab", Combo(MacKey.TAB, listOf("win")))
            else ShortcutItem("overview", "Mission Control", "⌃↑", Combo(MacKey.UP, listOf("ctrl"))),
            ShortcutItem("esc", "Esc", "", Combo(MacKey.ESCAPE, emptyList())),
            ShortcutItem("left", "←", "", Combo(MacKey.LEFT, emptyList()), arrow = true),
            ShortcutItem("up", "↑", "", Combo(MacKey.UP, emptyList()), arrow = true),
            ShortcutItem("down", "↓", "", Combo(MacKey.DOWN, emptyList()), arrow = true),
            ShortcutItem("right", "→", "", Combo(MacKey.RIGHT, emptyList()), arrow = true)
        )
    }

    fun messages(combo: Combo): List<String> = listOf(RemoteInput.key(combo.keycode, combo.mods))
}

/**
 * The app switcher held open from the phone (SPACE.md §15.5): ⌘ (Mac) or Alt (PC) goes down with the first step
 * and stays held (`key`), each step taps Tab (⇧Tab backwards), [end] releases it so the picked app comes forward.
 * Keys tapped while it is open ([tap]: arrows, Tab) go as plain `key` edges so the held modifier isn't released.
 */
class AppSwitcher(private val isWindows: Boolean) {
    var open = false
        private set

    private val mod get() = if (isWindows) MacKey.OPTION else MacKey.COMMAND

    fun step(forward: Boolean): List<String> {
        val out = mutableListOf<String>()
        if (!open) { open = true; out += RemoteInput.hold(mod, true) }
        if (!forward) out += RemoteInput.hold(MacKey.SHIFT, true)
        out += RemoteInput.hold(MacKey.TAB, true)
        out += RemoteInput.hold(MacKey.TAB, false)
        if (!forward) out += RemoteInput.hold(MacKey.SHIFT, false)
        return out
    }

    fun tap(keycode: Int): List<String> =
        if (!open) emptyList() else listOf(RemoteInput.hold(keycode, true), RemoteInput.hold(keycode, false))

    fun end(): List<String> {
        if (!open) return emptyList()
        open = false
        return listOf(RemoteInput.hold(mod, false))
    }
}

/** A shortcut the user added to the row ("+"), saved per computer. [key] is how the key is shown. */
data class CustomCombo(val mods: List<String>, val keycode: Int, val key: String) {
    val combo: Combo get() = Combo(keycode, mods)

    fun label(isWindows: Boolean): String {
        val m = ModKey.labels(mods, isWindows)
        return if (isWindows) (m + key).joinToString("+") else m.joinToString("") + key
    }

    companion object {
        /** Keys offered besides letters, digits and punctuation. */
        val SPECIAL_KEYS: List<Pair<String, Int>> = listOf(
            "Tab" to MacKey.TAB, "Return" to MacKey.RETURN, "Space" to MacKey.SPACE, "Esc" to MacKey.ESCAPE,
            "Delete" to MacKey.DELETE, "Del" to MacKey.FORWARD_DELETE,
            "←" to MacKey.LEFT, "→" to MacKey.RIGHT, "↑" to MacKey.UP, "↓" to MacKey.DOWN,
            "Home" to 115, "End" to 119, "PgUp" to 116, "PgDn" to 121,
            "F1" to 122, "F2" to 120, "F3" to 99, "F4" to 118, "F5" to 96, "F6" to 97,
            "F7" to 98, "F8" to 100, "F9" to 101, "F10" to 109, "F11" to 103, "F12" to 111
        )

        fun encode(list: List<CustomCombo>): String = JsonArray().apply {
            list.forEach { c ->
                add(JsonObject().apply {
                    add("mods", JsonArray().apply { c.mods.forEach { add(it) } })
                    addProperty("keycode", c.keycode)
                    addProperty("key", c.key)
                })
            }
        }.toString()

        fun decode(json: String?): List<CustomCombo> = try {
            if (json.isNullOrBlank()) emptyList()
            else JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val code = o.get("keycode")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
                val mods = o.get("mods")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { m ->
                    m.takeIf { it.isJsonPrimitive }?.asString
                }.orEmpty()
                CustomCombo(mods, code, o.get("key")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
