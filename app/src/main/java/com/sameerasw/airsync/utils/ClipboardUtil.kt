package com.sameerasw.airsync.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object ClipboardUtil {
    private const val TAG = "ClipboardUtil"

    /**
     * Get the current text from clipboard
     */
    fun getClipboardText(context: Context): String? {
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboardManager.primaryClip

            if (clipData != null && clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                // Use coerceToText to handle non-text items if needed, or stick to text
                item.text?.toString() ?: item.coerceToText(context)?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            // Keep error logging for exceptions
            Log.e(TAG, "Error getting clipboard text: ${e.message}")
            null
        }
    }

    /**
     * Set text to clipboard. [tag] (a clip id) goes into the description's extras so the sync
     * watcher knows this write was H.I.B applying a clip, not the user copying (SPACE.md §16.2).
     */
    fun setClipboardText(context: Context, text: String, tag: String? = null): Boolean {
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("H.I.B", text)
            tagClip(clipData, tag)
            clipboardManager.setPrimaryClip(clipData)
            Log.d(TAG, "Text set to clipboard (${text.length} characters)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting clipboard text: ${e.message}")
            false
        }
    }

    private fun tagClip(clip: ClipData, tag: String?) {
        if (tag == null) return
        runCatching {
            clip.description.extras = android.os.PersistableBundle().apply {
                putString(com.sameerasw.airsync.clip.ClipReader.TAG_EXTRA, tag)
            }
        }
    }

    /**
     * Register clipboard change listener
     * Returns a listener that can be used to unregister
     */
    fun registerClipboardListener(
        context: Context,
        onClipboardChanged: (String) -> Unit
    ): ClipboardManager.OnPrimaryClipChangedListener {
        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                // Only forward real text. Images we placed on the clipboard are content://
                // URIs, and coerceToText() would turn them into a URI string on Android 11.
                val clipText = clipboardManager.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                if (!clipText.isNullOrEmpty()) {
                    onClipboardChanged(clipText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in clipboard listener: ${e.message}")
            }
        }

        clipboardManager.addPrimaryClipChangedListener(listener)
        Log.d(TAG, "Clipboard listener registered")
        return listener
    }

    /**
     * Unregister clipboard change listener
     */
    fun unregisterClipboardListener(
        context: Context,
        listener: ClipboardManager.OnPrimaryClipChangedListener
    ) {
        try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.removePrimaryClipChangedListener(listener)
            Log.d(TAG, "Clipboard listener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering clipboard listener: ${e.message}")
        }
    }

    /**
     * Set a content URI to clipboard (useful for images)
     */
    fun copyUriToClipboard(context: Context, uri: android.net.Uri): Boolean {
        return try {
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData =
                ClipData.newUri(context.contentResolver, "H.I.B Image", uri)
            clipboardManager.setPrimaryClip(clipData)
            Log.d(TAG, "URI set to clipboard: $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting clipboard URI: ${e.message}")
            false
        }
    }

    fun setClipboardImage(
        context: Context,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        tag: String? = null
    ): Boolean {
        return try {
            val extension = when (mimeType.lowercase()) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            val safeBase = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val directory = File(context.cacheDir, "universal_clipboard").apply { mkdirs() }
            directory.listFiles()?.forEach { old ->
                if (System.currentTimeMillis() - old.lastModified() > 24 * 60 * 60 * 1000L) {
                    old.delete()
                }
            }
            val file = File(directory, "${safeBase.ifBlank { "HIB-Clipboard" }}.$extension")
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.clipboard-files",
                file
            )
            val clipboardManager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(context.contentResolver, "H.I.B Screenshot", uri)
            tagClip(clip, tag)
            clipboardManager.setPrimaryClip(clip)
            Log.d(TAG, "Image set on universal clipboard: ${bytes.size} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting clipboard image", e)
            false
        }
    }
}
