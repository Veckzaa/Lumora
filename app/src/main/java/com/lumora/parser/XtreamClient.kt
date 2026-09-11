package com.lumora.parser

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import android.util.MalformedJsonException
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.util.normalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

private const val TAG = "XtreamClient"

/** Whitespace runs in category names - compiled once, it used to be built per category. */
private val WHITESPACE_RUNS = Regex("\\s+")

/**
 * Minimal Xtream Codes API client using OkHttp.
 * Fetches live TV, VOD, and series from an Xtream server.
 */
/** Per-provider EPG clock-shift estimate (seconds), keyed by server URL - survives the
 *  per-call XtreamClient instances so the guide stays consistent across channels. */
private val epgShiftSecondsCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

internal const val LIVE_CONTAINER_HLS = "m3u8"
internal const val LIVE_CONTAINER_TS = "ts"

/**
 * The extension to build live stream URLs with, from the account's `allowed_output_formats`.
 *
 * Live streams carry no container_extension on the bulk list, and every live URL used to be
 * built as .m3u8 - but HLS output is a per-account panel switch, and a panel with it off
 * answers every .m3u8 request with HTTP 405 while serving the same stream as .ts. So HLS
 * only when the account lists it; MPEG-TS otherwise, including when the panel sends no list,
 * since TS is the output every Xtream panel serves.
 */
internal fun liveContainerFor(allowedOutputFormats: List<String>): String =
    if (LIVE_CONTAINER_HLS in allowedOutputFormats) LIVE_CONTAINER_HLS else LIVE_CONTAINER_TS

class XtreamClient(private val client: OkHttpClient) {

    // fetchJson() swallows exceptions into a null return so most callers can just treat
    // "no data" as empty - but that turned real errors (bad URL, DNS failure, timeout)
    // into a useless generic "Empty response" message for authenticate(). Stash the real
    // cause here so authenticate() can surface it instead.
    private var lastFetchError: String? = null

    data class ServerInfo(
        val version: String? = null,
        val url: String? = null,
        val port: String? = null,
        val httpsPort: String? = null,
        val serverProtocol: String? = null,
        val valid: Boolean = false,
        val expDateSeconds: Long? = null,
        val isTrial: Boolean = false,
        /** `user_info.allowed_output_formats`, lowercased - e.g. ["m3u8", "ts"]. Empty when
         *  the panel doesn't say. See [liveContainerFor]. */
        val allowedOutputFormats: List<String> = emptyList()
    )

    data class EpgProgram(
        val title: String,
        val startTimestamp: Long,
        val stopTimestamp: Long
    ) {
        fun isNowAiring(nowSeconds: Long): Boolean = nowSeconds in startTimestamp until stopTimestamp
    }

    data class ContentDetails(
        val plot: String? = null,
        val cast: String? = null,
        val director: String? = null,
        val genre: String? = null,
        val backdropUrl: String? = null,
        val rating: String? = null,
        val releaseDate: String? = null
    )

    /** Authenticate and get server info. */
    suspend fun authenticate(provider: Provider): Result<ServerInfo> = withContext(Dispatchers.IO) {
        try {
            val url = buildApiUrl(provider, "")
            Log.d(TAG, "Auth URL: ${redact(url)}")
            lastFetchError = null
            val json = fetchJson(url)
                ?: return@withContext Result.failure(Exception(lastFetchError ?: "Empty response from server"))

            val userInfo = json.optJSONObject("user_info")
            if (userInfo != null) {
                val auth = if (userInfo.has("auth")) {
                    val raw = userInfo.get("auth")
                    when (raw) {
                        is Boolean -> if (raw) "1" else "0"
                        else -> raw.toString()
                    }
                } else "0"
                Result.success(ServerInfo(
                    version = json.optString("server_info", ""),
                    url = userInfo.optString("url"),
                    port = userInfo.optString("port"),
                    httpsPort = userInfo.optString("https_port"),
                    serverProtocol = userInfo.optString("server_protocol"),
                    valid = auth == "1",
                    expDateSeconds = userInfo.optString("exp_date", "").toLongOrNull(),
                    isTrial = userInfo.optString("is_trial", "0") == "1",
                    allowedOutputFormats = userInfo.optJSONArray("allowed_output_formats")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim().lowercase() } }
                        .orEmpty()
                ))
            } else {
                Result.failure(Exception("Invalid server response - check URL and credentials"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auth failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Fetch live categories. Returns list of (id, name) pairs. */
    suspend fun getLiveCategories(provider: Provider): List<Pair<String, String>> =
        fetchCategoryList(provider, "live")

    /** Fetch VOD categories. */
    suspend fun getVodCategories(provider: Provider): List<Pair<String, String>> =
        fetchCategoryList(provider, "vod")

    /** Fetch series categories. */
    suspend fun getSeriesCategories(provider: Provider): List<Pair<String, String>> =
        fetchCategoryList(provider, "series")

    /** Fetch live streams. [liveContainer] is the extension live URLs are built with - pick it
     *  with [liveContainerFor] from the account's allowed output formats. */
    suspend fun getLiveStreams(provider: Provider, liveContainer: String, categoryId: String? = null): List<Channel> =
        fetchStreamList(provider, "get_live_streams", categoryId, MediaType.LIVE, liveContainer)

    /** Fetch VOD streams. */
    suspend fun getVodStreams(provider: Provider, categoryId: String? = null): List<Channel> =
        fetchStreamList(provider, "get_vod_streams", categoryId, MediaType.MOVIE)

    /** Fetch series list. */
    suspend fun getSeries(provider: Provider, categoryId: String? = null): List<Channel> =
        fetchSeriesList(provider, categoryId)

    /** Fetch the next few EPG entries for a live channel. Not every channel has EPG data. */
    suspend fun getShortEpg(provider: Provider, streamId: String, limit: Int = 2): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_short_epg&stream_id=$streamId&limit=$limit")
            val json = fetchJson(url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("epg_listings") ?: return@withContext emptyList()
            val listings = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = decodeEpgText(obj.optString("title", "")) ?: return@mapNotNull null
                val start = obj.optString("start_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                val stop = obj.optString("stop_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                EpgProgram(title, start, stop)
            }
            // Some panels store their local wall-clock as the UTC epoch, so their listings
            // read hours in the future on a UTC-reckoning device. Shift them so the guide
            // lines up with the device clock. Computed once per provider and cached.
            val shift = epgEpochShiftSeconds(provider.serverUrl, listings)
            if (shift == 0L) listings
            else listings.map { it.copy(startTimestamp = it.startTimestamp - shift, stopTimestamp = it.stopTimestamp - shift) }
        }

    /**
     * The channel's whole stored guide (`get_simple_data_table`), not just the next few
     * entries - Catch Up needs a full day's listings for a day that has already passed,
     * which get_short_epg (forward-looking, limited) can't answer.
     *
     * Same panel quirks as [getShortEpg]: base64 titles and the local-clock-as-UTC epoch
     * offset, corrected through the same shared shift so a catch-up listing lines up with
     * the guide the user saw live.
     */
    suspend fun getEpgTable(provider: Provider, streamId: String): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_simple_data_table&stream_id=$streamId")
            val json = fetchJson(url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("epg_listings") ?: json.optJSONArray("items")
                ?: return@withContext emptyList()
            val listings = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = decodeEpgText(obj.optString("title", "")) ?: return@mapNotNull null
                val start = obj.optString("start_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                val stop = obj.optString("stop_timestamp", "0").toLongOrNull() ?: return@mapNotNull null
                if (start <= 0L || stop <= start) return@mapNotNull null
                EpgProgram(title, start, stop)
            }.sortedBy { it.startTimestamp }
            val shift = epgEpochShiftSeconds(provider.serverUrl, listings)
            if (shift == 0L) listings
            else listings.map { it.copy(startTimestamp = it.startTimestamp - shift, stopTimestamp = it.stopTimestamp - shift) }
        }

    /** Estimated per-provider EPG timezone shift, seconds. 0 = panel is spec-compliant. */
    private fun epgEpochShiftSeconds(serverUrl: String?, listings: List<EpgProgram>): Long {
        if (serverUrl == null) return 0L
        epgShiftSecondsCache[serverUrl]?.let { return it }
        val firstStart = listings.minOfOrNull { it.startTimestamp } ?: return 0L
        val now = System.currentTimeMillis() / 1000
        val gap = firstStart - now
        // A spec-compliant panel lists the current programme first (it starts at or before
        // now); at worst the first entry is the next one, still within the hour. A gap over
        // an hour means the panel is clock-shifted - estimate the shift to the nearest
        // half-hour and cache it, so every channel's guide agrees with the device clock.
        val shift = if (gap > 3600L) {
            (Math.round(gap / 1800.0).toLong() * 1800L).coerceIn(3600L, 14 * 3600L)
        } else 0L
        epgShiftSecondsCache[serverUrl] = shift
        return shift
    }

    private fun decodeEpgText(value: String): String? {
        if (value.isBlank()) return null
        val decoded = runCatching {
            String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
        return decoded?.ifBlank { null } ?: value
    }

    /** Fetch a movie's plot/cast/director/genre for the detail screen. */
    suspend fun getVodInfo(provider: Provider, vodId: String): ContentDetails? = withContext(Dispatchers.IO) {
        val url = buildApiUrl(provider, "action=get_vod_info&vod_id=${URLEncoder.encode(vodId, "UTF-8")}")
        val json = fetchJson(url) ?: return@withContext null
        json.optJSONObject("info")?.let { parseDetails(it) }
    }

    data class SeriesFullInfo(
        val details: ContentDetails?,
        val seasons: List<Pair<String, List<Channel>>>
    )

    /** Fetch a series' details plus its episodes grouped by season, in one call. */
    suspend fun getSeriesFull(provider: Provider, seriesId: String): SeriesFullInfo =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_series_info&series_id=${URLEncoder.encode(seriesId, "UTF-8")}")
            val json = fetchJson(url) ?: return@withContext SeriesFullInfo(null, emptyList())
            val details = json.optJSONObject("info")?.let { parseDetails(it) }

            val episodes = json.optJSONObject("episodes")
            val seasons = mutableListOf<Pair<String, List<Channel>>>()
            if (episodes != null) {
                val seasonKeys = episodes.keys().asSequence().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                for (seasonKey in seasonKeys) {
                    val seasonArr = episodes.optJSONArray(seasonKey) ?: continue
                    val eps = (0 until seasonArr.length()).map { i ->
                        parseEpisode(seasonArr.getJSONObject(i), seriesId, seasonKey, provider)
                    }
                    if (eps.isNotEmpty()) seasons.add("Season $seasonKey" to eps)
                }
            }
            SeriesFullInfo(details, seasons)
        }

    private fun parseDetails(info: JSONObject): ContentDetails {
        val backdrop = info.optJSONArray("backdrop_path")?.takeIf { it.length() > 0 }?.optString(0)
        return ContentDetails(
            plot = info.optString("plot", info.optString("description", "")).ifBlank { null },
            cast = info.optString("cast", "").ifBlank { null },
            director = info.optString("director", "").ifBlank { null },
            genre = info.optString("genre", "").ifBlank { null },
            backdropUrl = backdrop?.ifBlank { null },
            rating = info.optString("rating", "").ifBlank { null },
            // Confirmed against a live provider: movies actually use "releasedate" (all
            // lowercase, no separator) - "release_date"/"releaseDate" never matched
            // anything there. Kept as fallbacks in case another provider spells it
            // differently.
            releaseDate = info.optString("releasedate", info.optString("release_date", info.optString("releaseDate", ""))).ifBlank { null }
        )
    }

    // ── Internal helpers ──────────────────────────

    private suspend fun fetchCategoryList(provider: Provider, type: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val url = buildApiUrl(provider, "action=get_${type}_categories")
            val json = fetchJson(url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("categories")
                ?: json.optJSONArray("")  // Some servers return array as root
                ?: json.optJSONArray("items")  // Wrapped bare array
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("category_id", "")
                // Some panels pad category names with non-breaking spaces (U+00A0) that render as
                // odd gaps and break keyword grouping; fold them to plain spaces and collapse runs.
                val name = obj.optString("category_name", "")
                    .replace(' ', ' ')
                    .replace(WHITESPACE_RUNS, " ")
                    .trim()
                if (id.isNotBlank()) id to name else null
            }
        }

    private suspend fun fetchStreamList(
        provider: Provider,
        action: String,
        categoryId: String?,
        mediaType: MediaType,
        liveContainer: String = LIVE_CONTAINER_HLS
    ): List<Channel> = withContext(Dispatchers.IO) {
        val params = StringBuilder("action=$action")
        if (!categoryId.isNullOrBlank()) params.append("&category_id=$categoryId")
        val url = buildApiUrl(provider, params.toString())
        // Many Xtream servers return a bare JSON array at the root instead of
        // {"live_streams": [...]}; fetchItems() reads both shapes.
        val key = when (action) {
            "get_live_streams" -> "live_streams"
            "get_vod_streams" -> "vod_streams"
            else -> ""
        }
        // Once per list, not per stream: every stream shares the host and credentials, and
        // normalizeServerUrl plus the whole URL template used to run for each of 30k items.
        val base = provider.serverUrl?.let { normalizeServerUrl(it) }
        val urlPrefix = when (mediaType) {
            MediaType.MOVIE -> "$base/movie/${provider.username}/${provider.password}/"
            MediaType.LIVE -> "$base/live/${provider.username}/${provider.password}/"
            else -> "$base/${provider.username}/${provider.password}/"
        }
        val defaultContainer = if (mediaType == MediaType.LIVE) liveContainer else "mp4"
        fetchItems(url, key) { parseStream(it, mediaType, urlPrefix, defaultContainer) }.orEmpty()
    }

    private suspend fun fetchSeriesList(provider: Provider, categoryId: String?): List<Channel> =
        withContext(Dispatchers.IO) {
            val params = StringBuilder("action=get_series")
            if (!categoryId.isNullOrBlank()) params.append("&category_id=$categoryId")
            val url = buildApiUrl(provider, params.toString())
            fetchItems(url, "series") { parseSeriesItem(it) }.orEmpty()
        }

    /**
     * The bare YouTube video id out of whatever a panel put in its trailer field. Most send the
     * id alone, but a full watch/youtu.be/embed URL turns up too, and that can't be pasted into
     * the embed player as-is. Anything else (an id-looking string of the wrong length, a Vimeo
     * link) is passed through only when it has no "/" - a URL we can't read a key out of is
     * dropped rather than played as a broken embed.
     */
    private fun youtubeKey(raw: String): String? {
        val s = raw.trim()
        if (s.isBlank()) return null
        if ("/" !in s) return s.takeIf { "." !in it }
        val afterHost = s.substringAfter("youtu.be/", "")
            .ifBlank { s.substringAfter("/embed/", "") }
            .ifBlank { s.substringAfter("v=", "") }
        return afterHost.takeWhile { it != '&' && it != '?' && it != '/' }.takeIf { it.isNotBlank() }
    }

    /**
     * A stream's category id. Prefers the singular `category_id`, but some panels leave it null and
     * only populate a plural `category_ids` array; fall back to the first entry there. Only the
     * first is used because across the live providers surveyed no stream ever belonged to more than
     * one category - the plural field is redundant, not true multi-membership.
     */
    private fun resolveCategoryId(f: ItemFields): String {
        val single = f.categoryId.orEmpty()
        if (single.isNotBlank() && single != "null") return single
        return f.firstCategoryIdsEntry.orEmpty()
    }

    /** A JSON null value arrives as the literal "null" (org.json's optString did the same and the
     *  rules below were written against it) - treat that as absent so it doesn't become a
     *  category row named "null". */
    private fun categoryNameOf(f: ItemFields): String = f.categoryName.let { if (it == null || it == "null") "" else it }

    private fun parseStream(f: ItemFields, mediaType: MediaType, urlPrefix: String, defaultContainer: String): Channel? {
        val streamId = f.streamId.orEmpty()
        if (streamId.isBlank()) return null
        val streamIcon = f.streamIcon.orEmpty()
        // Archive/catch-up availability, live only. Panels are inconsistent about the JSON
        // type here - some send tv_archive as a number, some as the string "1" - and
        // both read as the same text.
        val archiveFlag = f.tvArchive?.trim()
        val hasArchive = mediaType == MediaType.LIVE && (archiveFlag == "1" || archiveFlag == "true")
        val archiveDays = if (hasArchive) f.tvArchiveDuration?.trim()?.toIntOrNull() ?: 0 else 0
        return Channel(
            id = streamId,
            name = f.name ?: "Unknown",
            url = "$urlPrefix$streamId.${f.container ?: defaultContainer}",
            logoUrl = streamIcon.ifBlank { null },
            posterUrl = if (mediaType != MediaType.LIVE) streamIcon.ifBlank { null } else null,
            categoryId = resolveCategoryId(f),
            categoryName = categoryNameOf(f),
            mediaType = mediaType,
            rating = f.rating?.ifBlank { null },
            year = f.year?.ifBlank { null },
            // A panel that advertises the archive but reports 0 days kept has nothing to
            // play back, so it doesn't count as catch-up capable.
            tvArchive = hasArchive && archiveDays > 0,
            tvArchiveDays = archiveDays,
            // Panels send the TMDB id and a YouTube trailer key on the bulk VOD list itself.
            // Both were being thrown away and then guessed back from the title via a TMDB
            // search - see Channel.tmdbId. "0" is how a panel spells "I don't have one".
            tmdbId = f.tmdb?.takeIf { it.isNotBlank() && it != "0" },
            trailerKey = youtubeKey(f.trailer.orEmpty())
        )
    }

    private fun parseSeriesItem(f: ItemFields): Channel? {
        val seriesId = f.seriesId.orEmpty()
        if (seriesId.isBlank()) return null
        val cover = f.cover.orEmpty()
        return Channel(
            id = seriesId,
            name = f.name ?: "Unknown",
            url = "",
            logoUrl = cover.ifBlank { null },
            posterUrl = cover.ifBlank { null },
            categoryId = resolveCategoryId(f),
            categoryName = categoryNameOf(f),
            mediaType = MediaType.SERIES,
            rating = f.rating?.ifBlank { null },
            year = f.year?.ifBlank { null },
            // Bulk get_series actually carries a real release date (unlike movies, which
            // only expose one per-item) - confirmed against a live provider.
            releaseDate = (f.releaseDate ?: f.releaseDateSnake ?: "").ifBlank { null },
            // Same as VOD, except the series list spells the trailer field `youtube_trailer`.
            tmdbId = f.tmdb?.takeIf { it.isNotBlank() && it != "0" },
            trailerKey = youtubeKey(f.youtubeTrailer.orEmpty().ifBlank { f.trailer.orEmpty() })
        )
    }

    private fun parseEpisode(obj: JSONObject, seriesId: String, seasonKey: String, provider: Provider): Channel {
        val id = obj.optString("id", "")
        val episodeNum = obj.optInt("episode_num", 0)
        val title = obj.optString("title", "Episode")
        val info = obj.optJSONObject("info")
        val container = obj.optString("container_extension", "mp4")
        // get_series_info episodes carry no "url" field - the stream must be built manually.
        val base = provider.serverUrl?.let { normalizeServerUrl(it) }
        val streamUrl = "$base/series/${provider.username}/${provider.password}/$id.$container"
        return Channel(
            id = id,
            name = "S${seasonKey}E${episodeNum.toString().padStart(2, '0')} · $title",
            url = streamUrl,
            posterUrl = info?.optString("movie_image", null),
            description = info?.optString("plot", null),
            episodeNum = episodeNum.takeIf { it > 0 },
            mediaType = MediaType.SERIES,
            categoryId = seriesId,
            // Panels disagree on the key even within one API - "air_date" on some,
            // "releasedate"/"release_date" on others - so all three are read before the
            // episode is left without a date (TMDB fills that gap where it can).
            releaseDate = listOf("air_date", "releasedate", "release_date", "releaseDate")
                .firstNotNullOfOrNull { key -> info?.optString(key, "")?.takeIf { it.isNotBlank() } }
        )
    }

    /** A player_api URL with its credentials blanked, for logging: the raw URL carries the
     *  provider's username and password, and logcat is readable by anything with adb. */
    private fun redact(url: String): String =
        url.replace(Regex("(username|password)=[^&]*"), "$1=…")

    private fun buildApiUrl(provider: Provider, params: String): String {
        val base = provider.serverUrl?.let { normalizeServerUrl(it) } ?: ""
        val user = URLEncoder.encode(provider.username.orEmpty(), "UTF-8")
        val pass = URLEncoder.encode(provider.password.orEmpty(), "UTF-8")
        val sep = if (params.isNotBlank()) "&$params" else ""
        return "$base/player_api.php?username=$user&password=$pass$sep"
    }

    /** Fetch JSON from URL using OkHttp. Returns null on failure. */
    private fun fetchJson(url: String): JSONObject? {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/1.0")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            // use{}, because the early returns below left the response unread and unclosed -
            // a leaked connection that never returns to the pool. Repeated 401/403 polls (a
            // stale/expired provider polled every guide refresh) starved the pool into fresh
            // TCP+TLS handshakes - same fix as JellyfinProvider.fetchItems/PlexProvider.getJson.
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastFetchError = "Server returned HTTP ${response.code}"
                    Log.w(TAG, "HTTP ${response.code} for ${redact(url)}")
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                if (body.isBlank()) {
                    lastFetchError = "Server returned an empty response"
                    Log.w(TAG, "Empty response body")
                    return@use null
                }
                // Xtream's get_live_streams/get_vod_streams/get_series return a bare JSON array
                // at the root on most panels - large ones can be tens of MB of channels. Peeking
                // the first non-whitespace char picks the right parser up front: JSONObject(body)
                // on array text doesn't fail cheaply, it fully parses the array and THEN throws,
                // with org.json's mismatch message serializing that whole parsed array back to a
                // string just to describe the error - an OOM-sized allocation nobody ever reads.
                val firstToken = body.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }?.let { body[it] }
                try {
                    if (firstToken == '[') {
                        JSONObject().apply { put("items", JSONArray(body)) }
                    } else {
                        JSONObject(body)
                    }
                } catch (e: JSONException) {
                    Log.w(TAG, "Invalid JSON response: ${body.take(200)}")
                    null
                }
            }
        } catch (e: Exception) {
            lastFetchError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Network error fetching ${redact(url)}: ${e.message}")
            null
        }
    }

    /**
     * A bulk list (`get_live_streams`, `get_vod_streams`, `get_series`), streamed item by item
     * instead of going through [fetchJson] (perf plan C3). A big panel's list is tens of MB, and
     * fetchJson held it three times over at its peak - the bytes, a UTF-16 String twice their
     * size, and an org.json tree of maps and boxed values - which is where the OOMs on large
     * panels came from. Here only the finished channels accumulate.
     *
     * Reads the same shapes fetchJson + optJSONArray did: a bare array at the root, or an object
     * holding the list under [arrayKey] (preferred) or "items"; entries that aren't objects are
     * skipped. Lenient, like org.json's tokenizer. Null on any failure with the same logging -
     * a malformed body yields no channels, never a partial list.
     */
    private fun fetchItems(url: String, arrayKey: String, parse: (ItemFields) -> Channel?): List<Channel>? {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/1.0")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastFetchError = "Server returned HTTP ${response.code}"
                    Log.w(TAG, "HTTP ${response.code} for ${redact(url)}")
                    return@use null
                }
                // charStream() honours a BOM and the declared charset, as body.string() did.
                val text = response.body?.charStream()?.buffered() ?: return@use null
                if (!skipToContent(text)) {
                    lastFetchError = "Server returned an empty response"
                    Log.w(TAG, "Empty response body")
                    return@use null
                }
                try {
                    readItemList(JsonReader(text).apply { isLenient = true }, arrayKey, parse)
                } catch (e: MalformedJsonException) {
                    Log.w(TAG, "Invalid JSON response: ${e.message}")
                    null
                } catch (e: RuntimeException) {
                    // IllegalStateException / NumberFormatException: valid tokens, wrong shape.
                    Log.w(TAG, "Invalid JSON response: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            lastFetchError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Network error fetching ${redact(url)}: ${e.message}")
            null
        }
    }

    /** Skips leading whitespace; false when nothing follows it (a blank body). */
    private fun skipToContent(reader: java.io.BufferedReader): Boolean {
        while (true) {
            reader.mark(1)
            val c = reader.read()
            if (c < 0) return false
            if (!c.toChar().isWhitespace()) {
                reader.reset()
                return true
            }
        }
    }

    private fun readItemList(reader: JsonReader, arrayKey: String, parse: (ItemFields) -> Channel?): List<Channel>? {
        val fields = ItemFields()
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> readItems(reader, fields, parse)
            JsonToken.BEGIN_OBJECT -> {
                var keyed: List<Channel>? = null
                var items: List<Channel>? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    // A repeated key replaces the earlier value, array or not, as org.json's put did.
                    when (reader.nextName()) {
                        arrayKey -> keyed = readItemsOrNull(reader, fields, parse)
                        "items" -> items = readItemsOrNull(reader, fields, parse)
                        else -> reader.skipValue()
                    }
                }
                keyed ?: items
            }
            else -> throw IllegalStateException("Expected an object or array, got ${reader.peek()}")
        }
    }

    private fun readItemsOrNull(reader: JsonReader, fields: ItemFields, parse: (ItemFields) -> Channel?): List<Channel>? =
        if (reader.peek() == JsonToken.BEGIN_ARRAY) readItems(reader, fields, parse) else { reader.skipValue(); null }

    private fun readItems(reader: JsonReader, fields: ItemFields, parse: (ItemFields) -> Channel?): List<Channel> {
        val out = ArrayList<Channel>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            fields.read(reader)
            parse(fields)?.let { out.add(it) }
        }
        reader.endArray()
        return out
    }

    /**
     * The fields of one list entry, reused for every entry. Null means the key was absent. The
     * values that repeat across a whole list (category id and name, rating, year, container) go
     * through a per-list pool, so 30k channels share a few hundred strings instead of each
     * holding its own copy for as long as the catalogue is in memory.
     */
    private class ItemFields {
        var streamId: String? = null
        var seriesId: String? = null
        var name: String? = null
        var streamIcon: String? = null
        var cover: String? = null
        var categoryId: String? = null
        var firstCategoryIdsEntry: String? = null
        var categoryName: String? = null
        var rating: String? = null
        var year: String? = null
        var container: String? = null
        var tvArchive: String? = null
        var tvArchiveDuration: String? = null
        var tmdb: String? = null
        var trailer: String? = null
        var youtubeTrailer: String? = null
        var releaseDate: String? = null
        var releaseDateSnake: String? = null
        private val pool = HashMap<String, String>()

        private fun pooled(value: String?): String? = value?.let { pool.getOrPut(it) { it } }

        fun read(reader: JsonReader) {
            streamId = null; seriesId = null; name = null; streamIcon = null; cover = null
            categoryId = null; firstCategoryIdsEntry = null; categoryName = null; rating = null
            year = null; container = null; tvArchive = null; tvArchiveDuration = null; tmdb = null
            trailer = null; youtubeTrailer = null; releaseDate = null; releaseDateSnake = null
            reader.beginObject()
            while (reader.hasNext()) {
                // Assigned on every occurrence, so a repeated key's last value wins, as in org.json.
                when (reader.nextName()) {
                    "stream_id" -> streamId = reader.scalar()
                    "series_id" -> seriesId = reader.scalar()
                    "name" -> name = reader.scalar()
                    "stream_icon" -> streamIcon = reader.scalar()
                    "cover" -> cover = reader.scalar()
                    "category_id" -> categoryId = pooled(reader.scalar())
                    "category_ids" -> firstCategoryIdsEntry = pooled(reader.firstCategoryId())
                    "category_name" -> categoryName = pooled(reader.scalar())
                    "rating" -> rating = pooled(reader.scalar())
                    "year" -> year = pooled(reader.scalar())
                    "container_extension" -> container = pooled(reader.scalar())
                    "tv_archive" -> tvArchive = reader.scalar()
                    "tv_archive_duration" -> tvArchiveDuration = reader.scalar()
                    "tmdb" -> tmdb = reader.scalar()
                    "trailer" -> trailer = reader.scalar()
                    "youtube_trailer" -> youtubeTrailer = reader.scalar()
                    "releaseDate" -> releaseDate = reader.scalar()
                    "release_date" -> releaseDateSnake = reader.scalar()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
    }
}

/**
 * A value as org.json's optString rendered it, which the parse rules were written against:
 * strings as-is, numbers as their text, booleans as "true"/"false", JSON null as "null". A
 * nested object or array reads as absent - org.json serialised it back to JSON text, which no
 * field here can use.
 */
private fun JsonReader.scalar(): String? = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.NULL -> { nextNull(); "null" }
    else -> { skipValue(); null }
}

/** First usable entry of a `category_ids` array; null when the value isn't an array or has none. */
private fun JsonReader.firstCategoryId(): String? {
    if (peek() != JsonToken.BEGIN_ARRAY) {
        skipValue()
        return null
    }
    var first: String? = null
    beginArray()
    while (hasNext()) {
        val v = scalar()
        if (first == null && v != null && v.isNotBlank() && v != "null") first = v
    }
    endArray()
    return first
}
