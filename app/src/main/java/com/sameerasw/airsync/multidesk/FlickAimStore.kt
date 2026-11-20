package com.sameerasw.airsync.multidesk

import android.content.Context
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** "Teach aim" (SPACE.md §4): the direction the phone pointed at each computer, stored per computer id. */
object FlickAimStore {
    fun flow(context: Context): Flow<Map<String, AimMatcher.Aim>> =
        DataStoreManager.getInstance(context.applicationContext).getFlickAimJson().map { AimMatcher.decode(it) }

    suspend fun teach(context: Context, id: String, aim: AimMatcher.Aim) {
        DataStoreManager.getInstance(context.applicationContext).updateFlickAimJson { json ->
            val map = AimMatcher.decode(json).filterKeys { !PairedDesktops.sameName(it, id) }
            AimMatcher.encode(map + (id to aim))
        }
    }

    suspend fun forget(context: Context, id: String) {
        DataStoreManager.getInstance(context.applicationContext).updateFlickAimJson { json ->
            AimMatcher.encode(AimMatcher.decode(json).filterKeys { !PairedDesktops.sameName(it, id) })
        }
    }

    suspend fun forgetAll(context: Context) {
        DataStoreManager.getInstance(context.applicationContext).updateFlickAimJson { AimMatcher.encode(emptyMap()) }
    }
}
