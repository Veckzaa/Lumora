package com.lumora.perf

import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.R
import com.lumora.adapter.LiveGuideAdapter
import com.lumora.cache.EpgListCache
import com.lumora.parser.XtreamClient
import org.junit.Test
import org.junit.runner.RunWith

/** Under EpgListCache's 500-entry cap, so no row's schedule is evicted mid-pass. */
private const val ROWS = 400
/** About a screen of guide rows plus RecyclerView's view cache - what a long scroll recycles. */
private const val POOL = 12
/** The programme area's width at 1080p with the sidebar open. */
private const val ROW_WIDTH_PX = 1450

/**
 * B9: the main-thread work of a D-pad scroll through the live guide - rebinding recycled rows
 * (16 programme blocks each, schedule already cached) and laying them out.
 *
 * Stands in for M5 on the emulator, whose host CPU and GPU render every guide frame in ~5 ms
 * whatever the rows cost. Drawing isn't covered here; that still needs M5 on a stick.
 */
@RunWith(AndroidJUnit4::class)
class GuideBench {

    @Test
    fun scroll400Rows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // No logos: an uncached one queues a network fetch that would fire after the pass.
        val channels = Fixtures.catalogue().take(ROWS).map { it.copy(logoUrl = null) }
        val now = System.currentTimeMillis() / 1000
        for (channel in channels) {
            EpgListCache.put(channel.id, List(16) { i ->
                XtreamClient.EpgProgram("Programme $i on ${channel.name}", now - 600 + i * 1800L, now + 1200 + i * 1800L)
            })
        }
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Lumora)
            val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
            val adapter = LiveGuideAdapter(onChannelClick = {}, fetchPrograms = { null })
            adapter.submitList(channels) // first list: applied synchronously, no diff
            val widthSpec = View.MeasureSpec.makeMeasureSpec(ROW_WIDTH_PX, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            Bench.run("B9 guide scroll 400 rows", prepare = { List(POOL) { adapter.onCreateViewHolder(parent, 0) } }) { holders ->
                for (position in 0 until ROWS) {
                    val holder = holders[position % POOL]
                    adapter.onBindViewHolder(holder, position)
                    val row = holder.itemView
                    row.measure(widthSpec, heightSpec)
                    row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                }
            }
        }
    }
}
