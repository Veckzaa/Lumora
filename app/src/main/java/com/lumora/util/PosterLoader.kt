package com.lumora.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.lumora.BaseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TARGET_POSTER_WIDTH_PX = 220
private const val TARGET_POSTER_HEIGHT_PX = 330

/**
 * Shared downsampled poster cache for every poster grid/shelf in the app, so
 * scrolling through Series/Films never decodes a full-resolution (600x900+)
 * TMDB image just to show it in a small tile - that was the main source of
 * the jank/freeze reported when browsing large VOD catalogs.
 */
object PosterLoader {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        // Deliberately no recycle() on eviction. An evicted bitmap is very often still set
        // on an on-screen ImageView (the same poster is shown by a shelf row and the grid,
        // and eviction runs constantly on big catalogs) - recycling it out from under the
        // view drew a blank/black tile, or threw "Canvas: trying to use a recycled bitmap".
        // GC reclaims them once nothing references them, which is the only safe point.
    }

    /** In-flight fetches keyed by URL, so the same poster requested by several holders at
     *  once (shelf + grid + a rebind mid-scroll) issues one HTTP call instead of N racing
     *  ones that each decode the same image. */
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Posters get a disk cache of their own. They shared the app's 50 MB HTTP cache with the
     *  catalogue and Jellyfin item lists (21 MB of it on a 14k-item test catalogue), which
     *  left room for ~200 posters, so scrolling Films re-downloaded nearly every one on each
     *  launch. newBuilder() keeps the shared connection pool and auth interceptors. */
    private val client: OkHttpClient by lazy {
        val app = BaseApplication.instance
        app.okHttpClient.newBuilder()
            .cache(Cache(File(app.cacheDir, "poster_cache"), 80L * 1024 * 1024))
            .build()
    }

    /** Panels hand out TMDB posters at 600x900 (~110 KB) for a ~220 px tile; w342 is about a
     *  third of the bytes and of the decode work, and still downsamples to the tile size. */
    private val tmdbSize = Regex("""^(https?://image\.tmdb\.org/t/p/)[^/]+/""")

    fun getCached(url: String): Bitmap? = cache.get(url)

    suspend fun fetch(url: String): Bitmap? {
        getCached(url)?.let { return it }
        val job = inFlight.computeIfAbsent(url) {
            scope.async {
                try {
                    val bitmap = download(url)
                    if (bitmap != null) cache.put(url, bitmap)
                    bitmap
                } finally {
                    inFlight.remove(url)
                }
            }
        }
        // await() on the shared job, not the caller's own coroutine doing the work: a caller
        // being cancelled (holder recycled, adapter detached) must not cancel the download
        // everyone else is waiting on, hence the separate scope above.
        return runCatching { job.await() }.getOrNull()
    }

    /** Reads the whole response into memory before decoding. Posters are small (tens of KB),
     *  and decoding straight off the socket needed mark()/reset() to rewind between the
     *  bounds pass and the real one - a BufferedInputStream mark is invalidated as soon as
     *  the bounds decode reads past the read-limit, and the reset() then threw, which showed
     *  up as posters that silently never appeared. */
    private fun download(url: String): Bitmap? = runCatching {
        val request = Request.Builder().url(tmdbSize.replaceFirst(url, "$1w342/")).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            if (bytes.isEmpty()) return@runCatching null

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, TARGET_POSTER_WIDTH_PX, TARGET_POSTER_HEIGHT_PX)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }.getOrNull()

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
