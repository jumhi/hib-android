package com.sameerasw.airsync.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.sameerasw.airsync.utils.PermissionUtil
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/**
 * Performs one narrowly-scoped action: attaching a file that the user explicitly dropped on the
 * paired Mac mirror. It only works in the supported compose screens, expires quickly, and never
 * presses a send button. Android does not expose a standard API for inserting an attachment into
 * another app's currently open conversation, so this optional service drives the visible UI.
 */
class MirrorAttachmentAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var request: AttachmentRequest? = null

    private val retry = object : Runnable {
        override fun run() {
            advance()
            if (request != null) handler.postDelayed(this, RETRY_DELAY_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (request != null) advance()
    }

    override fun onInterrupt() {
        finish(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        finish(null)
        return super.onUnbind(intent)
    }

    private fun begin(data: JSONObject): Boolean {
        val targetPackage = data.optString("targetPackage")
        val fileName = data.optString("fileName")
        val path = data.optString("path")
        if (targetPackage !in SUPPORTED_TARGETS || fileName.isBlank() || path.isBlank()) return false

        val activePackage = rootInActiveWindow?.packageName?.toString()
        if (activePackage != targetPackage) {
            toast("Keep ${appName(targetPackage)} open, then drop the file again")
            return false
        }

        request = AttachmentRequest(
            targetPackage = targetPackage,
            fileName = fileName,
            path = path,
            expiresAt = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        )
        handler.removeCallbacks(retry)
        handler.post(retry)
        MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
        return true
    }

    private fun advance() {
        val current = request ?: return
        if (System.currentTimeMillis() >= current.expiresAt) {
            finish("Couldn't attach automatically — use the Android share sheet")
            return
        }

        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString().orEmpty()
        when (current.stage) {
            Stage.OPEN_ATTACH_MENU -> {
                if (packageName != current.targetPackage) return
                val ids = if (current.targetPackage == WHATSAPP_PACKAGE) {
                    listOf(
                        "com.whatsapp:id/input_attach_button",
                        "com.whatsapp:id/attach_button"
                    )
                } else {
                    listOf(
                        "com.google.android.gm:id/add_attachment",
                        "com.google.android.gm:id/attach_file"
                    )
                }
                val labels = if (current.targetPackage == WHATSAPP_PACKAGE) {
                    listOf("attach", "attachment")
                } else {
                    listOf("attach file", "attach")
                }
                if (clickFirst(root, ids, labels)) current.stage = Stage.CHOOSE_FILE_SOURCE
            }

            Stage.CHOOSE_FILE_SOURCE -> {
                if (packageName in FILE_PICKER_PACKAGES) {
                    current.stage = Stage.PICK_FILE
                    return
                }
                if (clickFirst(root, emptyList(), listOf("document", "attach file", "files", "file"))) {
                    current.stage = Stage.PICK_FILE
                }
            }

            Stage.PICK_FILE -> {
                if (packageName !in FILE_PICKER_PACKAGES) return
                val fileNode = allNodes(root).firstOrNull { node ->
                    nodeText(node).equals(current.fileName, ignoreCase = true)
                }
                if (fileNode != null && click(fileNode)) {
                    finish("Attached in ${appName(current.targetPackage)} — review before sending")
                    return
                }

                if (!current.searchOpened &&
                    clickFirst(root, emptyList(), listOf("search", "search files", "search in files"))
                ) {
                    current.searchOpened = true
                    return
                }

                if (current.searchOpened && !current.searchEntered) {
                    val searchField = allNodes(root).firstOrNull {
                        it.isEditable && nodeText(it).contains("search", ignoreCase = true)
                    } ?: allNodes(root).firstOrNull { it.isEditable }
                    if (searchField != null) {
                        val arguments = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                current.fileName
                            )
                        }
                        if (searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                            current.searchEntered = true
                        }
                    }
                }
            }
        }
    }

    private fun clickFirst(
        root: AccessibilityNodeInfo,
        viewIds: List<String>,
        labels: List<String>
    ): Boolean {
        viewIds.forEach { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { if (click(it)) return true }
        }
        val wanted = labels.map { it.lowercase() }.toSet()
        val node = allNodes(root).firstOrNull {
            val text = nodeText(it).trim().lowercase()
            text in wanted
        }
        return node != null && click(node)
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isClickable) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = candidate.parent
        }
        return false
    }

    private fun allNodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            yield(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()
            ?: node.contentDescription?.toString()
            ?: ""
    }

    private fun finish(message: String?) {
        request = null
        handler.removeCallbacks(retry)
        message?.let(::toast)
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun appName(packageName: String): String = when (packageName) {
        WHATSAPP_PACKAGE -> "WhatsApp"
        GMAIL_PACKAGE -> "Gmail"
        else -> "the app"
    }

    private data class AttachmentRequest(
        val targetPackage: String,
        val fileName: String,
        val path: String,
        val expiresAt: Long,
        var stage: Stage = Stage.OPEN_ATTACH_MENU,
        var searchOpened: Boolean = false,
        var searchEntered: Boolean = false
    )

    private enum class Stage { OPEN_ATTACH_MENU, CHOOSE_FILE_SOURCE, PICK_FILE }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        private val SUPPORTED_TARGETS = setOf(WHATSAPP_PACKAGE, GMAIL_PACKAGE)
        private val FILE_PICKER_PACKAGES = setOf(
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.sec.android.app.myfiles"
        )
        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val RETRY_DELAY_MS = 250L
        private const val MAX_NODES = 2_500

        @Volatile
        private var instance: MirrorAttachmentAccessibilityService? = null

        /** H.I.B Find My: lock the screen now. False if the accessibility service isn't running. */
        fun lockScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        fun requestAttachment(context: Context, data: JSONObject): Boolean {
            val service = instance
            if (service == null) {
                val message = if (PermissionUtil.isMirrorAttachmentAccessibilityEnabled(context)) {
                    "Attachment automation is starting — drop the file again"
                } else {
                    "Enable H.I.B Attachment Automation in Accessibility settings"
                }
                val appContext = context.applicationContext
                val needsSetup = !PermissionUtil.isMirrorAttachmentAccessibilityEnabled(context)
                // Called from the WebSocket reader thread: Toast and startActivity need the main looper.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    if (needsSetup) {
                        runCatching {
                            appContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        }
                    }
                }
                return false
            }
            service.handler.post { service.begin(data) }
            return true
        }
    }
}
