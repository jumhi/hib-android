package com.sameerasw.airsync.clip

import org.json.JSONObject

/** `clipboardUpdate` / `clipboardImageUpdate` on the phone link and BLE clips (SPACE.md §16.1). */
object ClipJson {
    const val TYPE_TEXT = "clipboardUpdate"
    const val TYPE_IMAGE = "clipboardImageUpdate"

    fun isClipType(type: String?): Boolean = type == TYPE_TEXT || type == TYPE_IMAGE

    /** Reads a clip message's `data`. Older versions leave out id/ts/origin; those stay null. */
    fun decode(type: String, data: JSONObject?): ClipIn? {
        if (data == null) return null
        val id = data.optString("id").takeIf { it.isNotBlank() }
        val ts = data.optLong("ts", 0L).takeIf { it > 0 }
        val origin = data.optString("origin").takeIf { it.isNotBlank() }
        return if (type == TYPE_IMAGE) {
            val b64 = data.optString("base64").takeIf { it.isNotBlank() } ?: return null
            ClipIn(
                text = null, png = b64, id = id, ts = ts, origin = origin,
                mime = data.optString("mime").ifBlank { "image/png" },
                name = data.optString("name").ifBlank { "HIB-Clipboard.png" },
                legacyImage = true
            )
        } else {
            // Text wins when a clip carries both (SPACE.md §12.6).
            val text = data.optString("text").takeIf { it.isNotEmpty() }
            val png = if (text == null) data.optString("png").takeIf { it.isNotBlank() } else null
            if (text == null && png == null) return null
            ClipIn(text = text, png = png, id = id, ts = ts, origin = origin)
        }
    }

    /**
     * A BLE clipboard write: plain text from older Macs, or a JSON object with id/ts/origin (§16.1).
     * A copied text that merely looks like JSON stays text unless it carries an id and text/png.
     */
    fun decodeBle(payload: String): ClipIn? {
        if (payload.isEmpty()) return null
        val trimmed = payload.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val o = runCatching { JSONObject(trimmed) }.getOrNull()
            if (o != null && o.optString("id").isNotBlank() && (o.has("text") || o.has("png"))) {
                return decode(TYPE_TEXT, o)
            }
        }
        return ClipIn(text = payload, png = null)
    }

    /** The message for one clip, id/ts/origin as they are (a relay never changes them). */
    fun encode(clip: Clip): String {
        val data = JSONObject()
        val type: String
        if (clip.png != null && clip.legacyImage) {
            type = TYPE_IMAGE
            data.put("base64", clip.png)
                .put("mime", clip.mime ?: "image/png")
                .put("name", clip.name ?: "HIB-Clipboard.png")
        } else {
            type = TYPE_TEXT
            if (clip.png != null) data.put("png", clip.png) else data.put("text", clip.text ?: "")
        }
        data.put("id", clip.id).put("ts", clip.ts).put("origin", clip.origin)
        // "type" first: WebSocketUtil reads it from the head of the message for its log line.
        return JSONObject().put("type", type).put("data", data).toString()
    }
}
