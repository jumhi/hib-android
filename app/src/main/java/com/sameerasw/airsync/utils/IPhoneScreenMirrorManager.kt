package com.sameerasw.airsync.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** In-memory state for the currently connected iPhone screen stream. */
object IPhoneScreenMirrorManager {
    private const val TAG = "IPhoneScreenMirror"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frameLock = Any()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame = _frame.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    private var newestTimestamp = 0L
    private var generation = 0L

    fun setActive(active: Boolean) {
        _isActive.value = active
        if (!active) clear()
    }

    fun submitFrame(bytes: ByteArray, timestamp: Long) {
        val frameGeneration = synchronized(frameLock) { generation }
        scope.launch {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
            synchronized(frameLock) {
                // Drop decodes that finish after clear(), so a closed viewer doesn't reappear.
                if (frameGeneration == generation && timestamp >= newestTimestamp) {
                    newestTimestamp = timestamp
                    _frame.value = bitmap
                    _isActive.value = true
                } else {
                    bitmap.recycle()
                }
            }
        }
    }

    fun clear() {
        synchronized(frameLock) {
            generation++
            newestTimestamp = 0L
            _frame.value = null
            _isActive.value = false
        }
        Log.d(TAG, "iPhone screen viewer cleared")
    }
}
