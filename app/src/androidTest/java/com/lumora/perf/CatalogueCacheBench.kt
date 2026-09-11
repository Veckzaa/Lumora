package com.lumora.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.cache.ChannelCache
import org.junit.Test
import org.junit.runner.RunWith

/** B1/B2: the flat catalogue cache every cached cold start begins with. */
@RunWith(AndroidJUnit4::class)
class CatalogueCacheBench {

    @Test
    fun saveThenLoad50k() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Bench.run("B2 cache save 50k", prepare = { run -> Fixtures.catalogue(salt = run) }) {
            ChannelCache.save(context, it)
        }
        Bench.run("B1 cache load 50k", prepare = { }) {
            check(ChannelCache.load(context)?.size == Fixtures.CATALOGUE)
        }
        // B10: what EpgSyncWorker needs from the cache every 6 h - tvg-id -> channel ids.
        Bench.run("B10 tvg-id mapping 50k", prepare = { }) {
            check(ChannelCache.loadTvgIdMapping(context).size > 1000)
        }
    }
}
