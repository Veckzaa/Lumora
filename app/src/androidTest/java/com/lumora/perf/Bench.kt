package com.lumora.perf

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Minimal timing harness for the baseline benchmarks (docs/perf/OPTIMIZATION_PLAN.md, B1-B8).
 *
 * Deliberately not androidx.benchmark: that wants its own module and refuses to run on an
 * emulator, and the numbers wanted here are coarse - whole passes over 30-50k items taking tens
 * of ms to seconds, where nanosecond loop calibration adds nothing.
 *
 * Each benchmark runs once **cold** (first call in the process: JIT not warmed, memo tables
 * empty - what a real cold start sees; baseline.sh runs every benchmark in its own process),
 * then [WARM_RUNS] more times, reported as the median-time run. One logcat line per phase
 * (tag LumoraBench), which tools/perf/baseline.sh collects:
 *
 *     BENCH<TAB>name<TAB>phase<TAB>ms<TAB>allocMB<TAB>gcCount<TAB>gcMs
 */
object Bench {
    private const val TAG = "LumoraBench"
    private const val WARM_RUNS = 5

    private class Sample(val ms: Double, val allocBytes: Long, val gcCount: Long, val gcMs: Long)

    /** [prepare] builds run N's input (0 = cold) outside the timed region. */
    fun <T> run(name: String, prepare: (run: Int) -> T, block: (T) -> Unit) {
        val warm = ArrayList<Sample>(WARM_RUNS)
        for (i in 0..WARM_RUNS) {
            val input = prepare(i)
            // Start every run from a collected heap, so one run's garbage isn't billed to the next.
            Runtime.getRuntime().gc()
            val sample = measure { block(input) }
            if (i == 0) log(name, "cold", sample) else warm.add(sample)
        }
        log(name, "warm", warm.sortedBy { it.ms }[warm.size / 2])
    }

    private inline fun measure(block: () -> Unit): Sample {
        val alloc = stat("art.gc.bytes-allocated")
        val gcCount = stat("art.gc.gc-count")
        val gcTime = stat("art.gc.gc-time")
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        val elapsed = SystemClock.elapsedRealtimeNanos() - start
        return Sample(
            ms = elapsed / 1e6,
            allocBytes = stat("art.gc.bytes-allocated") - alloc,
            gcCount = stat("art.gc.gc-count") - gcCount,
            gcMs = stat("art.gc.gc-time") - gcTime
        )
    }

    private fun stat(key: String): Long = Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L

    private fun log(name: String, phase: String, s: Sample) {
        Log.i(
            TAG,
            String.format(Locale.US, "BENCH\t%s\t%s\t%.1f\t%.1f\t%d\t%d", name, phase, s.ms, s.allocBytes / 1048576.0, s.gcCount, s.gcMs)
        )
    }
}
