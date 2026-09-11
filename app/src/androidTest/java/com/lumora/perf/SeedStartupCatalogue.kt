package com.lumora.perf

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.PREF_CATALOG_REFRESHED_AT
import com.lumora.PREF_LIVE_CONTAINER_REFETCHED
import com.lumora.cache.ChannelCache
import com.lumora.data.IptvProviderStore
import com.lumora.model.IptvProviderConfig
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a benchmark - setup for the cold-start measurements (M1-M5) in tools/perf/baseline.sh.
 *
 * Leaves the benchmark app with one enabled M3U provider and a fresh 50k cached catalogue, so a
 * launch serves the cache and never touches the network: the start being timed is the app's
 * own work, not the provider's latency.
 */
@RunWith(AndroidJUnit4::class)
class SeedStartupCatalogue {

    @Test
    fun seed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        IptvProviderStore.save(
            prefs,
            listOf(IptvProviderConfig(id = "bench", type = "m3u", name = "Bench", url = "http://127.0.0.1:9/bench.m3u"))
        )
        ChannelCache.save(context, Fixtures.catalogue())
        // A fresh stamp keeps the cache authoritative (no refetch). commit(), not apply(): the
        // process is killed as soon as the test returns.
        check(
            prefs.edit()
                .putLong(PREF_CATALOG_REFRESHED_AT, System.currentTimeMillis())
                .putBoolean(PREF_LIVE_CONTAINER_REFETCHED, true)
                .commit()
        )
    }
}
