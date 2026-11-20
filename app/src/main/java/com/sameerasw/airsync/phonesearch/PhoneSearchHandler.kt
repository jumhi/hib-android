package com.sameerasw.airsync.phonesearch

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import com.sameerasw.airsync.utils.FlickSender
import com.sameerasw.airsync.utils.PermissionUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * H.I.B — "Search your phone" from the Mac's Search Phone & Memory panel.
 *
 * Mac→phone `phoneSearch` {id, query, kinds, limit}: searches photos/videos (MediaStore), files
 * (MediaStore.Files, complete with MANAGE_EXTERNAL_STORAGE), launchable apps and — only if
 * READ_CONTACTS is granted — contacts. Each kind is streamed back as soon as it's ready as
 * `phoneSearchResults` {id, results, done:false}; a final {done:true} closes the search. The whole
 * search is capped at ~2 s.
 *
 * Mac→phone `phoneFetchFile` {id, path}: streams that file back with the existing phone→Mac file
 * transfer (FlickSender), using the Mac's id as the transfer id and tagging it `searchFetchId`.
 */
object PhoneSearchHandler {
    private const val TAG = "PhoneSearch"
    private const val TIME_BUDGET_MS = 2000L
    private const val MAX_THUMBNAILS = 12
    private const val MAX_THUMB_BYTES = 20 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ParsedQuery(val text: String, val from: Long?, val to: Long?, val wantVideos: Boolean?)

    fun handleSearch(context: Context, data: JSONObject) {
        val appContext = context.applicationContext
        val id = data.optString("id")
        if (id.isEmpty()) return
        val query = data.optString("query").trim()
        val limit = data.optInt("limit", 30).coerceIn(1, 100)
        val kindsArr = data.optJSONArray("kinds")
        val kinds = if (kindsArr == null) setOf("files", "photos", "apps", "contacts")
        else (0 until kindsArr.length()).map { kindsArr.optString(it) }.toSet()

        scope.launch {
            val started = System.currentTimeMillis()
            val parsed = parse(query)
            val thumbs = AtomicInteger(0)
            val jobs = mutableListOf<Deferred<JSONArray>>()
            if ("photos" in kinds) jobs += async { safe { searchMedia(appContext, parsed, limit, thumbs) } }
            if ("apps" in kinds && parsed.text.isNotEmpty()) jobs += async { safe { searchApps(appContext, parsed.text, limit) } }
            if ("contacts" in kinds && parsed.text.isNotEmpty()) jobs += async { safe { searchContacts(appContext, parsed.text, limit) } }
            if ("files" in kinds) jobs += async { safe { searchFiles(appContext, parsed, limit) } }

            for (job in jobs) {
                val remaining = TIME_BUDGET_MS - (System.currentTimeMillis() - started)
                val results = if (remaining > 0) withTimeoutOrNull(remaining) { job.await() } else null
                if (results == null) {
                    job.cancel()
                    continue
                }
                if (results.length() > 0) send(id, results, done = false)
            }
            send(id, JSONArray(), done = true)
        }
    }

    fun handleFetch(context: Context, data: JSONObject) {
        val appContext = context.applicationContext
        val id = data.optString("id")
        val path = data.optString("path")
        if (id.isEmpty() || path.isEmpty()) return
        // H.I.B multi-desktop: send the file back to the computer that asked for it.
        val requester = com.sameerasw.airsync.multidesk.DesktopHub.currentSourceId()
        scope.launch {
            val uri: Uri
            val name: String
            if (path.startsWith("content://")) {
                uri = Uri.parse(path)
                name = displayName(appContext, uri) ?: "file"
            } else {
                val file = File(path)
                if (!file.isFile || !file.canRead()) {
                    Log.w(TAG, "Can't read $path")
                    sendCancel(id, requester)
                    return@launch
                }
                uri = Uri.fromFile(file)
                name = file.name
            }
            val mime = appContext.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val ok = FlickSender.startFileTransfer(appContext, id, uri, name, mime, tagKey = "searchFetchId", to = requester)
            if (!ok) sendCancel(id, requester)
        }
    }

    // MARK: - Query parsing ("photos from yesterday", "screenshot today", "pdf last week")

    private fun parse(raw: String): ParsedQuery {
        var text = " " + raw.lowercase() + " "
        var from: Long? = null
        var to: Long? = null
        fun dayStart(offsetDays: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, offsetDays)
        }.timeInMillis

        val rules: List<Pair<Regex, () -> Unit>> = listOf(
            Regex("\\byesterday\\b|أمس|امس") to { from = dayStart(-1); to = dayStart(0) },
            Regex("\\btoday\\b|اليوم") to { from = dayStart(0); to = null },
            Regex("\\b(last|this|past) week\\b|الأسبوع|الاسبوع") to { from = dayStart(-7); to = null },
            Regex("\\b(last|this|past) month\\b|الشهر") to { from = dayStart(-30); to = null },
        )
        for ((re, apply) in rules) {
            if (re.containsMatchIn(text)) {
                apply()
                text = re.replace(text, " ")
            }
        }
        var wantVideos: Boolean? = null
        if (Regex("\\b(videos?|clips?)\\b").containsMatchIn(text)) {
            wantVideos = true
            text = Regex("\\b(videos?|clips?)\\b").replace(text, " ")
        } else if (Regex("\\b(photos?|pictures?|pics?|images?)\\b").containsMatchIn(text)) {
            wantVideos = false
            text = Regex("\\b(photos?|pictures?|pics?|images?)\\b").replace(text, " ")
        }
        text = Regex("\\b(from|of|on|in)\\b").replace(text, " ")
        return ParsedQuery(text.trim().replace(Regex("\\s+"), " "), from, to, wantVideos)
    }

    // MARK: - Photos & videos

    private fun searchMedia(context: Context, q: ParsedQuery, limit: Int, thumbs: AtomicInteger): JSONArray {
        val out = JSONArray()
        if (q.text.isEmpty() && q.from == null && q.wantVideos == null) return out
        val targets = buildList {
            if (q.wantVideos != true) add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (q.wantVideos != false) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }
        val rows = mutableListOf<JSONObject>()
        for (base in targets) {
            val (selection, args) = mediaSelection(q, extra = null)
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH
            )
            context.contentResolver.query(base, projection, queryArgs(selection, args, limit), null)?.use { c ->
                while (c.moveToNext() && rows.size < limit * 2) {
                    val itemId = c.getLong(0)
                    val uri = ContentUris.withAppendedId(base, itemId)
                    val taken = if (c.isNull(4)) 0L else c.getLong(4)
                    val modified = if (c.isNull(5)) 0L else c.getLong(5) * 1000
                    rows += JSONObject().apply {
                        put("kind", "photos")
                        put("title", c.getString(1) ?: "")
                        put("subtitle", (c.getString(7) ?: "").trimEnd('/'))
                        put("mime", c.getString(6) ?: "")
                        c.getString(2)?.let { put("path", it) }
                        put("uri", uri.toString())
                        put("size", c.getLong(3))
                        put("date", if (taken > 0) taken else modified)
                    }
                }
            }
        }
        rows.sortByDescending { it.optLong("date") }
        for (row in rows.take(limit)) {
            if (thumbs.get() < MAX_THUMBNAILS) {
                thumbnail(context, Uri.parse(row.getString("uri")))?.let {
                    if (thumbs.incrementAndGet() <= MAX_THUMBNAILS) row.put("thumbnail", it)
                }
            }
            out.put(row)
        }
        return out
    }

    private fun mediaSelection(q: ParsedQuery, extra: String?): Pair<String, Array<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (q.text.isNotEmpty()) {
            for (word in q.text.split(" ").filter { it.isNotBlank() }.take(5)) {
                clauses += "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
                args += "%$word%"; args += "%$word%"
            }
        }
        q.from?.let {
            clauses += "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
            args += (it / 1000).toString()
        }
        q.to?.let {
            clauses += "${MediaStore.MediaColumns.DATE_MODIFIED} < ?"
            args += (it / 1000).toString()
        }
        extra?.let { clauses += it }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun queryArgs(selection: String, args: Array<String>, limit: Int): Bundle = Bundle().apply {
        if (selection.isNotEmpty()) {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }
        putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
        putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
    }

    private fun thumbnail(context: Context, uri: Uri): String? = try {
        val bmp = context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
        var quality = 70
        var bytes: ByteArray
        var current: Bitmap = bmp
        while (true) {
            val stream = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            bytes = stream.toByteArray()
            if (bytes.size <= MAX_THUMB_BYTES || quality <= 30) break
            quality -= 15
            if (quality <= 40 && current.width > 96) {
                current = Bitmap.createScaledBitmap(current, current.width * 3 / 4, current.height * 3 / 4, true)
            }
        }
        if (bytes.size <= MAX_THUMB_BYTES) Base64.encodeToString(bytes, Base64.NO_WRAP) else null
    } catch (e: Exception) {
        null
    }

    // MARK: - Files

    private fun searchFiles(context: Context, q: ParsedQuery, limit: Int): JSONArray {
        val out = JSONArray()
        if (q.text.isEmpty() && q.from == null) return out
        val base = MediaStore.Files.getContentUri("external")
        val notMedia = "${MediaStore.Files.FileColumns.MEDIA_TYPE} NOT IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
        val textOnly = q.copy(wantVideos = null)
        val (selection, args) = mediaSelection(textOnly, extra = notMedia)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        context.contentResolver.query(base, projection, queryArgs(selection, args, limit), null)?.use { c ->
            while (c.moveToNext() && out.length() < limit) {
                val name = c.getString(1) ?: continue
                out.put(JSONObject().apply {
                    put("kind", "files")
                    put("title", name)
                    put("subtitle", (c.getString(6) ?: "").trimEnd('/'))
                    put("mime", c.getString(5) ?: "")
                    c.getString(2)?.let { put("path", it) }
                    put("uri", ContentUris.withAppendedId(base, c.getLong(0)).toString())
                    put("size", c.getLong(3))
                    put("date", c.getLong(4) * 1000)
                })
            }
        }
        return out
    }

    // MARK: - Apps

    private fun searchApps(context: Context, text: String, limit: Int): JSONArray {
        val out = JSONArray()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val needle = text.lowercase()
        val matches = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { (pkg, label) -> label.lowercase().contains(needle) || pkg.lowercase().contains(needle) }
            .sortedWith(compareBy({ !it.second.lowercase().startsWith(needle) }, { it.second.lowercase() }))
            .take(limit)
        for ((pkg, label) in matches) {
            out.put(JSONObject().apply {
                put("kind", "apps")
                put("title", label)
                put("subtitle", pkg)
                put("path", pkg)
            })
        }
        return out
    }

    // MARK: - Contacts (only with READ_CONTACTS; skipped silently otherwise)

    private fun searchContacts(context: Context, text: String, limit: Int): JSONArray {
        val out = JSONArray()
        if (!PermissionUtil.isContactsPermissionGranted(context)) return out
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val digits = text.filter { it.isDigit() }
        val selection = if (digits.length >= 3)
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ?"
        else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = if (digits.length >= 3) arrayOf("%$text%", "%$digits%") else arrayOf("%$text%")
        val seen = HashSet<String>()
        context.contentResolver.query(phone, projection, selection, args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { c ->
            while (c.moveToNext() && out.length() < limit) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                if (!seen.add(name + number.filter { it.isDigit() })) continue
                out.put(JSONObject().apply {
                    put("kind", "contacts")
                    put("title", name)
                    put("subtitle", number)
                })
            }
        }
        return out
    }

    // MARK: - Helpers

    private inline fun safe(block: () -> JSONArray): JSONArray = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Search part failed: ${e.message}")
        JSONArray()
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun send(id: String, results: JSONArray, done: Boolean) {
        val msg = JSONObject().apply {
            put("type", "phoneSearchResults")
            put("data", JSONObject().apply {
                put("id", id)
                put("results", results)
                put("done", done)
            })
        }
        WebSocketUtil.sendMessage(msg.toString())
    }

    private fun sendCancel(id: String, to: String? = null) {
        val msg = JSONObject().apply {
            put("type", "fileTransferCancel")
            put("data", JSONObject().put("id", id))
        }
        if (to != null) com.sameerasw.airsync.multidesk.DesktopHub.sendTo(to, msg.toString())
        else WebSocketUtil.sendMessage(msg.toString())
    }
}
