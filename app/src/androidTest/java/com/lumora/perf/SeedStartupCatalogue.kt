package com.lumora.perf

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.PREF_CATALOG_REFRESHED_AT
import com.lumora.PREF_LIVE_CONTAINER_REFETCHED
import androidx.room.withTransaction
import com.lumora.cache.ChannelCache
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.EpgProgramEntity
import com.lumora.model.MediaType
import kotlinx.coroutines.runBlocking
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
        val catalogue = Fixtures.catalogue()
        ChannelCache.save(context, catalogue)
        // A schedule for every live channel, fresh enough to be served from disk: the M5
        // scroll then binds rows of 16 programme blocks, as a real guide does, rather than
        // the single "No programme info" block an EPG-less catalogue gets. Every channel,
        // because the category the scroll lands in is whichever sorts first.
        val now = System.currentTimeMillis()
        val nowSeconds = now / 1000
        val rows = ArrayList<EpgProgramEntity>(Fixtures.LIVE * 16)
        for (channel in catalogue.asSequence().filter { it.mediaType == MediaType.LIVE }) {
            var start = nowSeconds - 600
            repeat(16) { i ->
                val stop = start + 1800L * (1 + i % 3)
                rows.add(EpgProgramEntity(channel.id, start, stop, "Programme $i on ${channel.name}", now))
                start = stop
            }
        }
        val db = LumoraDatabase.getInstance(context)
        runBlocking { db.withTransaction { db.epgProgramDao().pruneEndedBefore(Long.MAX_VALUE); db.epgProgramDao().upsertAll(rows) } }
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
