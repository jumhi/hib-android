package com.sameerasw.airsync.utils

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * H.I.B — the phone's latest photos, videos and screenshots, for the Flick widget and the Flick tile.
 * The query runs on MediaStore (newest first); [map] turns one row into a [RecentMedia] and is pure so it
 * is unit-tested.
 */
data class RecentMedia(
    val id: Long,
    /** "image" or "video" (Flick's card kinds). */
    val kind: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedSec: Long,
    val isScreenshot: Boolean,
    /** content://media/external/images/media/<id> (or …/video/media/<id>). */
    val uri: String
) {
    companion object {
        const val MEDIA_TYPE_IMAGE = 1 // MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        const val MEDIA_TYPE_VIDEO = 3 // MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        private const val IMAGES = "content://media/external/images/media/"
        private const val VIDEOS = "content://media/external/video/media/"

        /** One MediaStore row → a [RecentMedia], or null for anything that isn't a photo or a video. */
        fun map(
            id: Long,
            mediaType: Int,
            mime: String?,
            name: String?,
            size: Long,
            addedSec: Long,
            relativePath: String?,
            bucket: String?
        ): RecentMedia? {
            if (id <= 0) return null
            val type = mime?.lowercase()?.takeIf { it.contains('/') }
            val kind = when {
                mediaType == MEDIA_TYPE_IMAGE -> "image"
                mediaType == MEDIA_TYPE_VIDEO -> "video"
                type?.startsWith("image/") == true -> "image"
                type?.startsWith("video/") == true -> "video"
                else -> return null
            }
            val path = relativePath.orEmpty()
            val screenshot = kind == "image" && (
                bucket.equals("Screenshots", ignoreCase = true) ||
                    path.split('/').any { it.equals("Screenshots", ignoreCase = true) }
                )
            val finalMime = type ?: if (kind == "image") "image/jpeg" else "video/mp4"
            val finalName = name?.takeIf { it.isNotBlank() } ?: (if (kind == "image") "photo_$id.jpg" else "video_$id.mp4")
            return RecentMedia(
                id = id,
                kind = kind,
                name = finalName,
                mime = finalMime,
                size = size.coerceAtLeast(0L),
                addedSec = addedSec,
                isScreenshot = screenshot,
                uri = (if (kind == "image") IMAGES else VIDEOS) + id
            )
        }

        /** The media permissions Flick asks for to show the latest photos (Android 13+ split them). */
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        /** True when Flick can read at least some photos (full, or the ones the user picked on Android 14+). */
        fun canRead(context: Context): Boolean {
            fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 33 -> has(Manifest.permission.READ_MEDIA_IMAGES) ||
                    has(Manifest.permission.READ_MEDIA_VIDEO) ||
                    (Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
                else -> has(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        /** The newest [limit] photos and videos (screenshots included), newest first. Empty without access. */
        fun latest(context: Context, limit: Int): List<RecentMedia> {
            if (limit <= 0 || !canRead(context)) return emptyList()
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            )
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($MEDIA_TYPE_IMAGE, $MEDIA_TYPE_VIDEO)"
                )
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC")
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            val out = ArrayList<RecentMedia>(limit)
            try {
                context.contentResolver.query(collection, projection, args, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val iAdded = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val iPath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext() && out.size < limit) {
                        map(
                            id = c.getLong(iId),
                            mediaType = c.getInt(iType),
                            mime = if (iMime >= 0) c.getString(iMime) else null,
                            name = if (iName >= 0) c.getString(iName) else null,
                            size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L,
                            addedSec = if (iAdded >= 0 && !c.isNull(iAdded)) c.getLong(iAdded) else 0L,
                            relativePath = if (iPath >= 0) c.getString(iPath) else null,
                            bucket = if (iBucket >= 0) c.getString(iBucket) else null
                        )?.let { out += it }
                    }
                }
            } catch (e: Exception) {
                Log.w("RecentMedia", "Query failed: ${e.javaClass.simpleName}")
            }
            return out
        }

        fun uriOf(m: RecentMedia): Uri = Uri.parse(m.uri)
    }
}
