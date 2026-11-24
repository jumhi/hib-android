package com.sameerasw.airsync.multidesk

import com.sameerasw.airsync.utils.BundleWords
import com.sameerasw.airsync.utils.FlickBundle

/**
 * H.I.B — bundles a computer flicks to the phone (SPACE.md §14), as one notification instead of one per file.
 *
 * Each `flickStart` of a bundle registers its item here ([onStart]); text and links are handled on arrival as
 * before and only lower the number of files to wait for ([onNonFile]). As files finish ([onFinished]) the
 * bundle's [Snapshot] says how far it is and when every file is in. Pure Kotlin, unit-tested.
 */
class BundleCollector(private val now: () -> Long = { System.currentTimeMillis() }) {

    data class Item(
        val index: Int,
        val flickId: String,
        val kind: String,
        val name: String,
        val mime: String,
        val size: Long,
        val received: Long = 0L,
        /** null while it's still coming; true/false once it finished. */
        val ok: Boolean? = null,
        /** Where the phone saved it (a content URI string) when [ok]. */
        val uri: String? = null
    )

    data class Snapshot(
        val bundleId: String,
        val from: String,
        /** Files this bundle will bring (the count minus its text/link items). */
        val expected: Int,
        /** Every file of the bundle, in bundle order. */
        val items: List<Item>,
        val receivedBytes: Long,
        val totalBytes: Long,
        val complete: Boolean
    ) {
        val arrived: List<Item> get() = items.filter { it.ok == true }
        val failed: Int get() = items.count { it.ok == false }
        val finishedCount: Int get() = items.count { it.ok != null }
        val fraction: Float get() = if (totalBytes <= 0) 0f else (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

        /** "5 photos from Office PC" (what arrived), or while receiving "5 photos from Office PC" for all. */
        val title: String
            get() {
                val shown = if (complete) arrived else items
                return "${BundleWords.noun(shown.map { it.kind }, if (complete) arrived.size else expected)} from $from"
            }

        /** The line under the title. */
        val detail: String
            get() = when {
                !complete -> "$finishedCount of $expected"
                failed > 0 -> "$failed couldn't be received · tap to see the rest"
                else -> "Tap to see them all"
            }
    }

    private class Entry(val id: String, val count: Int, var from: String, val startedAt: Long) {
        val items = sortedMapOf<Int, Item>()
        var nonFiles = 0
        var posted = false
        val expected get() = (count - nonFiles).coerceAtLeast(0)
    }

    private val bundles = HashMap<String, Entry>()
    private val byFlick = HashMap<String, String>()

    @Synchronized
    fun onStart(bundle: FlickBundle, from: String, flickId: String, kind: String, name: String, mime: String, size: Long) {
        prune()
        val e = bundles.getOrPut(bundle.id) { Entry(bundle.id, bundle.count, from, now()) }
        if (e.from.isBlank()) e.from = from
        e.items[bundle.index] = Item(bundle.index, flickId, kind, name, mime, size)
        byFlick[flickId] = bundle.id
    }

    /** A text or link item of [bundle]: it is shown on its own, so the bundle waits for one file fewer. */
    @Synchronized
    fun onNonFile(bundle: FlickBundle, from: String) {
        prune()
        val e = bundles.getOrPut(bundle.id) { Entry(bundle.id, bundle.count, from, now()) }
        e.nonFiles++
    }

    /** The bundle a flick belongs to, or null for a single flick. */
    @Synchronized
    fun bundleOf(flickId: String?): String? = flickId?.let { byFlick[it] }

    @Synchronized
    fun onProgress(flickId: String, received: Long): Snapshot? {
        val e = entryFor(flickId) ?: return null
        val idx = e.items.entries.firstOrNull { it.value.flickId == flickId }?.key ?: return null
        e.items[idx] = e.items.getValue(idx).copy(received = received)
        return snap(e)
    }

    /** A file of a bundle finished ([ok] = saved at [uri]). The snapshot is [Snapshot.complete] once all are in. */
    @Synchronized
    fun onFinished(flickId: String, ok: Boolean, uri: String?): Snapshot? {
        val e = entryFor(flickId) ?: return null
        val idx = e.items.entries.firstOrNull { it.value.flickId == flickId }?.key ?: return null
        val item = e.items.getValue(idx)
        e.items[idx] = item.copy(ok = ok, uri = if (ok) uri else null, received = if (ok) item.size else item.received)
        return snap(e)
    }

    /** Marks a complete bundle as shown (the first caller wins) and forgets it. */
    @Synchronized
    fun claim(bundleId: String): Boolean {
        val e = bundles[bundleId] ?: return false
        if (e.posted) return false
        e.posted = true
        bundles.remove(bundleId)
        e.items.values.forEach { byFlick.remove(it.flickId) }
        return true
    }

    /** Bundles still waiting on files from [from] (it went away): finish them with what arrived. */
    @Synchronized
    fun abandon(from: String): List<Snapshot> {
        val gone = bundles.values.filter { PairedDesktops.sameName(it.from, from) && !it.posted }
        return gone.map { e ->
            e.items.keys.toList().forEach { k ->
                val it = e.items.getValue(k)
                if (it.ok == null) e.items[k] = it.copy(ok = false)
            }
            snap(e, force = true)
        }
    }

    @Synchronized
    fun snapshot(bundleId: String): Snapshot? = bundles[bundleId]?.let { snap(it) }

    private fun entryFor(flickId: String): Entry? = byFlick[flickId]?.let { bundles[it] }

    private fun snap(e: Entry, force: Boolean = false): Snapshot {
        val items = e.items.values.toList()
        val total = items.sumOf { it.size.coerceAtLeast(0L) }
        val received = items.sumOf { it.received.coerceIn(0L, it.size.coerceAtLeast(0L)) }
        val allKnown = items.size >= e.expected
        val allDone = items.all { it.ok != null }
        return Snapshot(
            bundleId = e.id,
            from = e.from,
            expected = e.expected,
            items = items,
            receivedBytes = received,
            totalBytes = total,
            complete = force || (allKnown && allDone && items.isNotEmpty())
        )
    }

    private fun prune() {
        val cutoff = now() - KEEP_MS
        val old = bundles.values.filter { it.startedAt < cutoff }
        old.forEach { e ->
            bundles.remove(e.id)
            e.items.values.forEach { byFlick.remove(it.flickId) }
        }
    }

    companion object {
        const val KEEP_MS = 60 * 60 * 1000L
    }
}
