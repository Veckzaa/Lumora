package com.lumora

import android.app.Application
import androidx.car.app.connection.CarConnection
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.remote.jellyfin.JellyfinAuthInterceptor
import com.lumora.data.remote.plex.PlexAuthInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class BaseApplication : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set

    /** See [onCreate] - epoch millis at process start. */
    var processStartedAt: Long = 0L
        private set

    lateinit var database: LumoraDatabase
        private set

    /** Latest [CarConnection] type, or -1 before the first emission. Read from the UI thread
     *  and written from the observer, so volatile rather than plain. Whoever reads it must
     *  treat -1 as "not projecting" - it is also what a phone with no Android Auto reports. */
    @Volatile
    var carConnectionType: Int = -1
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Wall-clock zero for the cold-start budget: the first line of app code that runs in
        // a fresh process. Everything MainActivity reports as "time to first content" is
        // measured from here, so the number matches what someone counting out loud sees.
        processStartedAt = System.currentTimeMillis()

        // No Google Cast init here. CastManager.init() creates the CastContext before the player
        // wires the Cast button, and on TVs Cast is never touched at all. The call that used to sit
        // here ran on every launch before the first frame: a full Cast setup where Play Services
        // exist, and on Fire TV (no Play Services) a probe that threw and was swallowed (perf plan S2).

        // The QuickJS native library is no longer loaded here: JsPluginEngine loads it before
        // its first context (perf plan S3), so a launch that never runs a plugin never pays
        // for the dlopen.

        // Application context for the ported site-scraper stack (com.lumora.scraper) - its
        // network layer reads a bundled root certificate out of raw resources and shares the
        // WebView cookie store, both of which need a Context that no scraper call carries.
        com.lumora.scraper.ScraperApp.init(this)

        // Whether this phone is projecting to a car right now. Android Auto suppresses
        // permission dialogs while projecting, so MainActivity reads this before asking for
        // anything - see requestNotificationPermissionIfNeeded.
        // Not on a TV: there is no Android Auto to observe, and the probe's failure was
        // being paid for and swallowed on every Fire TV launch (perf plan S3).
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            try {
                CarConnection(this).type.observeForever { carConnectionType = it }
            } catch (_: Throwable) {
                // No Android Auto on this device; -1 stays, meaning "not projecting".
            }
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
            .dns(okhttp3.internal.platform.Platform.get().let { okhttp3.Dns.SYSTEM })
            .addInterceptor(JellyfinAuthInterceptor())
            .addInterceptor(PlexAuthInterceptor())
            .cache(Cache(File(cacheDir, "okhttp_cache"), 50L * 1024 * 1024))  // 50MB disk cache
            .build()

        database = LumoraDatabase.getInstance(this)
    }

    companion object {
        lateinit var instance: BaseApplication
            private set
    }
}
