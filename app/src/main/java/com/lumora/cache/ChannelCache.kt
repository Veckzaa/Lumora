package com.lumora.cache

import android.content.Context
import android.util.Log
import com.lumora.model.Channel
import com.lumora.model.MediaType
import java.io.BufferedReader
import java.io.File
import java.io.Writer

/** Regex for characters that would corrupt the line/field format; compiled once at class load. */
private val FIELD_CLEAN_REGEX = Regex("[\n\r\u0001]")

private const val TAG = "ChannelCache"
private const val CACHE_FILE = "channels_cache.txt"
private const val LEGACY_JSON_CACHE_FILE = "channels_cache.json"
private const val FIELD_SEP = ''
/** Fields written per line. Append-only: new fields go at the end so older files stay
 *  readable. */
private const val FIELD_COUNT = 27
/** Fields a line must have to be usable. Held at the pre-Catch-Up count on purpose - a
 *  cache written by an older build is still perfectly good, and every field added since
 *  reads as empty when missing. Raising this to FIELD_COUNT dropped every line of an existing
 *  13MB cache on first launch after update, turning an instant cold start into a full
 *  network refetch. */
private const val FIELD_COUNT_MIN = 22

/**
 * Persists the last successfully loaded channel list to disk so re-opening the
 * app shows content instantly instead of re-fetching the whole catalog every time.
 *
 * Plain delimited text, not JSON: org.json's tree parser was the dominant cost in
 * "instant" cached loads once the catalog grew into the tens of thousands of items
 * (a ~20MB JSON file took many seconds just to parse). A flat one-line-per-channel
 * format needs only a field scan per line, no parser/DOM overhead.
 */
object ChannelCache {

    /**
     * Written a channel at a time straight to the file, not built up in memory first.
     *
     * The whole catalogue used to go into one StringBuilder and then `toString()` - so at the
     * moment of writing, the text existed twice: once in the builder's char[] and once in the
     * copy handed to writeText, each ~2 bytes per character. A merged multi-provider catalogue
     * reached tens of MB of text, which is where a TV stick died with
     * `Failed to allocate a 175786432 byte allocation` while the builder was still doubling.
     * A BufferedWriter holds only its buffer, whatever the catalogue's size.
     */
    fun save(context: Context, channels: List<Channel>) {
        try {
            val target = File(context.filesDir, CACHE_FILE)
            val tempFile = File(target.absolutePath + ".tmp")
            tempFile.bufferedWriter().use { out -> write(out, channels) }
            // Same atomic swap writeToFile did: a half-written cache must never replace a
            // good one, and a torn file would fail FIELD_COUNT on the next load anyway.
            tempFile.renameTo(target)
            runCatching { File(context.filesDir, LEGACY_JSON_CACHE_FILE).delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache: ${e.message}")
        }
    }

    /** One line per channel, [FIELD_COUNT] fields. Split out of [save] so the format can be
     *  round-trip tested without a Context. */
    internal fun write(out: Writer, channels: List<Channel>) {
        val sb = StringBuilder(256)
        for (ch in channels) {
            sb.setLength(0)
            sb.append(clean(ch.id)).append(FIELD_SEP)
            sb.append(clean(ch.name)).append(FIELD_SEP)
            sb.append(clean(ch.url)).append(FIELD_SEP)
            sb.append(clean(ch.logoUrl)).append(FIELD_SEP)
            sb.append(clean(ch.posterUrl)).append(FIELD_SEP)
            sb.append(clean(ch.backdropUrl)).append(FIELD_SEP)
            sb.append(clean(ch.group)).append(FIELD_SEP)
            sb.append(clean(ch.tvgId)).append(FIELD_SEP)
            sb.append(clean(ch.tvgName)).append(FIELD_SEP)
            sb.append(clean(ch.tvgChno)).append(FIELD_SEP)
            sb.append(ch.mediaType.name).append(FIELD_SEP)
            sb.append(clean(ch.categoryId)).append(FIELD_SEP)
            sb.append(clean(ch.categoryName)).append(FIELD_SEP)
            sb.append(clean(ch.description)).append(FIELD_SEP)
            sb.append(clean(ch.year)).append(FIELD_SEP)
            sb.append(clean(ch.rating)).append(FIELD_SEP)
            sb.append(clean(ch.releaseDate)).append(FIELD_SEP)
            sb.append(if (ch.isJellyfin) "1" else "0").append(FIELD_SEP)
            sb.append(clean(ch.streamUserAgent)).append(FIELD_SEP)
            sb.append(clean(ch.sourceProviderId)).append(FIELD_SEP)
            sb.append(ch.avOffsetMs.toString()).append(FIELD_SEP)
            // Stalker VOD/series play command - without it a cached film reloads with a
            // blank url and nothing to create_link, so playback opens "" as a local file
            // and dies with ENOENT.
            sb.append(clean(ch.stalkerCmd)).append(FIELD_SEP)
            // Catch Up needs these on cold start - the tab is built from the cached
            // catalogue, before any provider refetch has run.
            sb.append(if (ch.tvArchive) "1" else "0").append(FIELD_SEP)
            sb.append(ch.tvArchiveDays.toString()).append(FIELD_SEP)
            // TMDB id / trailer key from the panel - the detail screen's TMDB fill and the
            // Trailer button both run off the cached catalogue on a cold start, and without
            // these they fall back to guessing the title.
            sb.append(clean(ch.tmdbId)).append(FIELD_SEP)
            sb.append(clean(ch.trailerKey)).append(FIELD_SEP)
            // Plex, like Jellyfin, needs its provenance on cold start: the catalogue is
            // browsable from cache before any provider fetch runs, and a Plex item played
            // without this flag would take the plain-URL path instead of negotiating a
            // stream with the server.
            sb.append(if (ch.isPlex) "1" else "0").append('\n')
            out.append(sb)
        }
    }

    /** True when the file just loaded was written before the archive fields existed, so
     *  every channel came back with tvArchive=false regardless of what the panel reports.
     *  The catalogue is still perfectly usable - it just cannot answer "does this channel
     *  have catch-up" until a refetch, which the caller schedules off the back of this. */
    @Volatile
    var lastLoadWasLegacyFormat: Boolean = false
        private set

    /**
     * [keep] filters while reading: a rejected channel is dropped as soon as its line is
     * parsed, so callers that want a subset (the car service wants live channels only, the
     * gated-VOD merge in persistCatalog wants a few hundred) never hold the whole catalogue -
     * on a 1 GB stick that is the difference between a background read and a low-memory
     * kill while something is playing (perf plan C2).
     */
    fun load(context: Context, keep: ((Channel) -> Boolean)? = null): List<Channel>? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        val startedAt = System.currentTimeMillis()
        return try {
            val (result, sawLegacyLine) = file.bufferedReader().use { read(it, keep) }
            // Cold-start budget line: this parse and the derive passes that follow it are
            // what "instant cached start" is spent on - keep them measurable on-device
            // (adb logcat -s LumoraPerf) rather than guessed at.
            lastLoadWasLegacyFormat = sawLegacyLine
            Log.i(
                "LumoraPerf",
                "cache load: ${result.size} items in ${System.currentTimeMillis() - startedAt}ms " +
                    "(${file.length() / 1024}KB)" + if (sawLegacyLine) " [legacy format]" else ""
            )
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cache: ${e.message}")
            null
        }
    }

    /**
     * Parses cache lines back into channels, and whether any line predates the newest fields.
     *
     * Fields are located by index instead of `split()`: split built a list plus 27 strings for
     * each of ~50k lines, about 1.35M short-lived objects per cold start, and most of those
     * strings were empty fields turned into null straight away. That GC churn lands during
     * first paint on a stick (perf plan C1). Now only non-empty text fields become strings.
     *
     * The fields that repeat across the catalogue (group, category, year, rating, user agent,
     * provider id) go through a per-load pool, so thousands of channels share a few hundred
     * strings instead of each keeping its own copy for as long as the catalogue is in memory.
     */
    internal fun read(reader: BufferedReader, keep: ((Channel) -> Boolean)? = null): Pair<List<Channel>, Boolean> {
        val result = ArrayList<Channel>(if (keep == null) 50_000 else 1_000)
        val f = LineFields()
        var sawLegacyLine = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val count = f.scan(line)
            if (count < FIELD_COUNT_MIN) continue
            if (count < FIELD_COUNT) sawLegacyLine = true
            val url = f.text(2) ?: ""
            val channel = Channel(
                    // Falls back to the url, matching what M3uParser now assigns.
                    // Caches written before that fix hold a blank id for every M3U
                    // channel, and this file is read on cold start before any
                    // refetch - without the backfill the id-collision bug would
                    // survive the update until the user manually refreshed.
                    id = f.text(0) ?: url,
                    name = f.text(1) ?: "",
                    url = url,
                    logoUrl = f.text(3),
                    posterUrl = f.text(4),
                    backdropUrl = f.text(5),
                    group = f.pooled(6),
                    tvgId = f.text(7),
                    tvgName = f.text(8),
                    tvgChno = f.text(9),
                    mediaType = when {
                        f.textIs(10, "MOVIE") -> MediaType.MOVIE
                        f.textIs(10, "SERIES") -> MediaType.SERIES
                        else -> MediaType.LIVE // "LIVE", and the old valueOf fallback for anything unknown
                    },
                    categoryId = f.pooled(11),
                    categoryName = f.pooled(12),
                    description = f.text(13),
                    year = f.pooled(14),
                    rating = f.pooled(15),
                    releaseDate = f.text(16),
                    isJellyfin = f.flag(17),
                    streamUserAgent = f.pooled(18),
                    sourceProviderId = f.pooled(19),
                    avOffsetMs = f.int(20),
                    stalkerCmd = f.text(21),
                    tvArchive = f.flag(22),
                    tvArchiveDays = f.int(23),
                    tmdbId = f.text(24),
                    trailerKey = f.text(25),
                    isPlex = f.flag(26)
            )
            if (keep == null || keep(channel)) result.add(channel)
        }
        return result to sawLegacyLine
    }

    /**
     * tvg-id -> ids of the channels carrying it, straight from the cache file.
     *
     * The EPG sync worker only needs this to map a feed's programmes onto app channels, and it
     * used to get it by [load]ing the whole catalogue - 50k `Channel` objects with all 27
     * fields, built in the background every 6 h on a 1 GB device, possibly while something is
     * playing. This reads the three fields involved (id, its url fallback, tvg-id) and
     * nothing else (perf plan C2).
     */
    fun loadTvgIdMapping(context: Context): Map<String, Set<String>> {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            file.bufferedReader().use { readTvgIdMapping(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read tvg-ids from cache: ${e.message}")
            emptyMap()
        }
    }

    internal fun readTvgIdMapping(reader: BufferedReader): Map<String, Set<String>> {
        val map = HashMap<String, MutableSet<String>>()
        val f = LineFields()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            if (f.scan(line) < FIELD_COUNT_MIN) continue
            val tvgId = f.text(7)?.takeIf { it.isNotBlank() } ?: continue
            // Same id fallback as read(): a blank id means the url.
            val id = f.text(0) ?: f.text(2) ?: ""
            map.getOrPut(tvgId) { mutableSetOf() }.add(id)
        }
        return map
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, CACHE_FILE).delete() }
        runCatching { File(context.filesDir, LEGACY_JSON_CACHE_FILE).delete() }
    }

    /** Strips characters that would corrupt the line/field format; real channel data never needs them. */
    private fun clean(value: String?): String {
        if (value == null) return ""
        // Fast path: the overwhelming majority of fields are already clean - three char
        // scans are cheaper than a regex pass, and the regex can only match these three.
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf(FIELD_SEP) < 0) return value
        return value.replace(FIELD_CLEAN_REGEX, " ")
    }

    /** Field bounds of the current line, reused across every line of one load. */
    private class LineFields {
        private var line = ""
        private val starts = IntArray(FIELD_COUNT)
        private val ends = IntArray(FIELD_COUNT)
        private val pool = HashMap<String, String>()

        /** Locates the fields of [line] and returns how many it has, capped at [FIELD_COUNT].
         *  Fields a legacy line lacks read as empty, which is exactly what their old defaults
         *  ("" / "0") parsed to. */
        fun scan(line: String): Int {
            this.line = line
            var count = 0
            var start = 0
            while (count < FIELD_COUNT) {
                val sep = line.indexOf(FIELD_SEP, start)
                starts[count] = start
                ends[count] = if (sep < 0) line.length else sep
                count++
                if (sep < 0) break
                start = sep + 1
            }
            for (i in count until FIELD_COUNT) { starts[i] = 0; ends[i] = 0 }
            return count
        }

        fun text(i: Int): String? = if (starts[i] == ends[i]) null else line.substring(starts[i], ends[i])

        fun pooled(i: Int): String? = text(i)?.let { pool.getOrPut(it) { it } }

        fun textIs(i: Int, value: String): Boolean =
            ends[i] - starts[i] == value.length && line.startsWith(value, starts[i])

        fun flag(i: Int): Boolean = ends[i] - starts[i] == 1 && line[starts[i]] == '1'

        fun int(i: Int): Int = when {
            starts[i] == ends[i] -> 0
            ends[i] - starts[i] == 1 && line[starts[i]] == '0' -> 0 // by far the most common value
            else -> line.substring(starts[i], ends[i]).toIntOrNull() ?: 0
        }
    }
}
