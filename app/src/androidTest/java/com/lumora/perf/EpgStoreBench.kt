package com.lumora.perf

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.EpgProgramEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** B6: persisting one XMLTV source's guide, in the same shape EpgSyncWorker.doWork writes it. */
@RunWith(AndroidJUnit4::class)
class EpgStoreBench {

    @Test
    fun replace50kRows() {
        val db = LumoraDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val dao = db.epgProgramDao()
        try {
            Bench.run("B6 epg store replace 50k", prepare = { run ->
                List(Fixtures.EPG_PROGRAMMES) { i ->
                    val start = 1_788_000_000L + i * 1800L
                    EpgProgramEntity(
                        channelId = "ch${i % Fixtures.EPG_CHANNELS}",
                        startTimestamp = start,
                        stopTimestamp = start + 1800L,
                        title = "Programme $i ($run)"
                    )
                }
            }) { rows ->
                // One transaction: delete per touched channel, then upsert - EpgSyncWorker's replace.
                val touched = rows.mapTo(HashSet()) { it.channelId }
                runBlocking {
                    db.withTransaction {
                        for (id in touched) dao.deleteForChannel(id)
                        dao.upsertAll(rows)
                    }
                }
            }
        } finally {
            // Leave no guide behind: the app's startup prune would otherwise delete these rows
            // during the timed cold starts that follow.
            runBlocking { dao.pruneEndedBefore(Long.MAX_VALUE) }
        }
    }
}
