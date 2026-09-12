package com.lumora.cache

import com.lumora.EPG_MIN_COVERAGE_SECONDS
import com.lumora.parser.XtreamClient
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CACHE_SIZE = 500

/** How long a fetched list counts as current whatever it covers - the floor on refetching, so a
 *  provider that only publishes a few hours ahead isn't asked again on every scroll. */
private const val STALE_AFTER_MS = 30 * 60 * 1000L

/**
 * Whether a channel's cached programmes need fetching again: older than [STALE_AFTER_MS] and
 * covering less than [EPG_MIN_COVERAGE_SECONDS] ahead - the disk cache's own rule. Lists used to
 * be kept for the whole session, so a stick left on the guide from 9:00 still drew 9:00's list at
 * 14:00: a channel of short shows ran out, and a failed fetch stayed "no programme info".
 * Only the rows being drawn ask, so this refetches what's on screen, never the whole category.
 */
internal fun epgListStale(programs: List<XtreamClient.EpgProgram>, fetchedAtMs: Long, nowMs: Long): Boolean {
    if (nowMs - fetchedAtMs < STALE_AFTER_MS) return false
    // Lists are in air order, so the last entry is the furthest ahead - no scan on the bind path.
    val lastStop = programs.lastOrNull()?.stopTimestamp ?: 0L
    return lastStop - nowMs / 1000 < EPG_MIN_COVERAGE_SECONDS
}

/** In-memory cache of upcoming EPG entries per live channel, used by the guide grid. */
object EpgListCache {
    // ConcurrentHashMap: guide fetches can put/remove from background scopes, and a plain
    // HashMap mutated off the main thread would throw ConcurrentModificationException or lose
    // entries. Iteration here is weakly consistent, so the eviction scan never races a write.
    // Values are non-null: a channel known to have no EPG is stored as an empty list, not as
    // a null. ConcurrentHashMap rejects null values outright, so `cache[id] = null` - which is
    // exactly what put() did for a channel whose fetch came back empty or threw - raised an
    // NPE from putVal() on the main thread and took the process down. Every reader already
    // treats empty and absent-data the same way (see renderPrograms/renderNow), and an empty
    // list still makes has() true for STALE_AFTER_MS, which is what stops the guide refetching
    // it on every bind.
    private val cache = ConcurrentHashMap<String, List<XtreamClient.EpgProgram>>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** The last list fetched for [channelId], stale or not - see [has] for whether to refetch. */
    fun get(channelId: String): List<XtreamClient.EpgProgram>? {
        val programs = cache[channelId] ?: return null
        lastAccess[channelId] = System.currentTimeMillis()
        return programs
    }

    /** True while the cached list is current ([epgListStale]); every caller refetches on false. */
    fun has(channelId: String): Boolean {
        val programs = cache[channelId] ?: return false
        return !epgListStale(programs, fetchedAt[channelId] ?: 0L, System.currentTimeMillis())
    }

    fun put(channelId: String, programs: List<XtreamClient.EpgProgram>?) {
        val now = System.currentTimeMillis()
        cache[channelId] = programs ?: emptyList()
        lastAccess[channelId] = now
        fetchedAt[channelId] = now
        inFlight.remove(channelId)
        evictIfNeeded()
    }

    /** Least-recently-used eviction: drops the entry that has gone longest without a get/put.
     *  Evicting by next-program start was evicting exactly the live channel the guide needs
     *  most. Entries still being fetched are skipped so an in-flight fetch isn't evicted out
     *  from under its own put(). */
    private fun evictIfNeeded() {
        while (cache.size > MAX_CACHE_SIZE) {
            val lru = cache.keys
                .asSequence()
                .filterNot { inFlight.contains(it) }
                .minByOrNull { lastAccess[it] ?: 0L }
            if (lru != null) {
                cache.remove(lru)
                lastAccess.remove(lru)
                fetchedAt.remove(lru)
            } else break
        }
    }

    fun markInFlight(channelId: String): Boolean {
        synchronized(inFlight) {
            if (channelId in inFlight) return false
            inFlight.add(channelId)
            return true
        }
    }

    /** Releases an in-flight claim without caching a result - for fetches that were
     *  cancelled mid-request, so the channel stays fetchable instead of stuck. */
    fun clearInFlight(channelId: String) {
        inFlight.remove(channelId)
    }
}
