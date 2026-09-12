package com.lumora

import android.app.AlertDialog
import android.widget.Toast
import com.lumora.data.IptvProviderStore
import com.lumora.model.MediaType
import com.lumora.util.SpeedTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/** One thing to measure: its label in the dialog and how to get its URL (null = nothing to test).
 *  [durationMs] is a film's length, which turns its file size into a bitrate - a file carries no
 *  clock of its own for SpeedTest to read. */
private class SpeedTarget(
    val label: String,
    val userAgent: String?,
    val headers: Map<String, String>?,
    val durationMs: Long = 0,
    val url: suspend () -> String?,
)

/**
 * A result a viewer can act on: whether the video arrives as fast as it plays, when the stream's
 * bitrate is known, since Mbit/s alone means nothing for a live channel the server sends at its own
 * pace. A plain download (the Internet test, an M3U playlist) just gets its speed.
 */
private fun MainActivity.describeSpeed(r: SpeedTest.Result, target: SpeedTarget): String = buildString {
    val stream = r.streamMbps
        ?: if (target.durationMs > 0 && r.contentLength > 0) r.contentLength * 8 / 1e6 / (target.durationMs / 1000.0) else null
    if (stream == null || stream <= 0) {
        append(getString(R.string.speed_test_download, r.mbps))
    } else {
        val ratio = r.mbps / stream
        append(when {
            ratio >= 1.3 -> getString(R.string.speed_test_smooth_margin, ratio)
            // A live channel sent at play speed lands around 1.0; a little under is timing jitter.
            ratio >= 0.95 -> getString(R.string.speed_test_smooth)
            else -> getString(R.string.speed_test_too_slow, (ratio * 100).roundToInt())
        })
        append('\n').append(getString(R.string.speed_test_stream, stream, r.mbps))
    }
    append('\n').append(getString(R.string.speed_test_server, r.host, r.responseMs))
}

/**
 * Runs [targets] one after another in a dialog showing the speed while it's measured, then each
 * result. Closing the dialog stops the test; [onDone] runs once the test coroutine has finished and
 * its connection is closed - the channel test relies on that to hand the provider's one connection
 * back to the player.
 */
private fun MainActivity.runSpeedTests(title: String, targets: List<SpeedTarget>, onDone: () -> Unit = {}) {
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(getString(R.string.speed_test_running))
        .setPositiveButton(getString(R.string.close), null)
        .show()
    val done = StringBuilder()
    val job = scope.launch {
        for (target in targets) {
            fun show(status: String) = dialog.setMessage("$done${target.label}\n$status")
            show(getString(R.string.speed_test_running))
            val line = try {
                val url = target.url() ?: throw IOException(getString(R.string.speed_test_nothing))
                val r = withContext(Dispatchers.IO) {
                    SpeedTest.run(BaseApplication.instance.okHttpClient, url, target.userAgent, target.headers) { mbps ->
                        runOnUiThread { show(getString(R.string.speed_test_progress, mbps)) }
                    }
                }
                describeSpeed(r, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The message only, never the URL: Xtream stream paths carry the account's password.
                getString(R.string.speed_test_failed, e.message ?: e.javaClass.simpleName)
            }
            done.append(target.label).append('\n').append(line).append("\n\n")
            dialog.setMessage(done.trimEnd())
        }
    }
    dialog.setOnDismissListener { job.cancel() }
    job.invokeOnCompletion { runOnUiThread { onDone() } }
}

/** Settings > Speed test > Internet speed. */
internal fun MainActivity.runInternetSpeedTest() = runSpeedTests(
    getString(R.string.speed_test_internet),
    listOf(SpeedTarget("Cloudflare", null, null) { SpeedTest.INTERNET_URL })
)

/**
 * Settings > Speed test > Provider servers: each enabled IPTV provider measured through the server
 * its streams really come from - one of its live channels, a Stalker one resolved the way playback
 * does. M3U channels don't record their provider, so an M3U provider downloads its playlist instead.
 */
internal fun MainActivity.runProviderSpeedTest() {
    val providers = IptvProviderStore.load(prefs).filter { it.enabled }
    if (providers.isEmpty()) {
        Toast.makeText(this, getString(R.string.speed_test_no_providers), Toast.LENGTH_SHORT).show()
        return
    }
    runSpeedTests(getString(R.string.speed_test_providers), providers.map { cfg ->
        val channel = allChannels.firstOrNull { it.sourceProviderId == cfg.id && it.mediaType == MediaType.LIVE }
        // Names the channel used, so "Smooth" reads as a result for something the viewer knows.
        val label = if (channel != null) "${cfg.name} · ${channel.name}" else cfg.name
        SpeedTarget(label, channel?.streamUserAgent ?: cfg.userAgent, channel?.streamHeaders) {
            when {
                channel == null -> cfg.url?.takeIf { cfg.type == "m3u" }
                channel.url.isBlank() -> resolveStalkerPlayUrl(channel)
                else -> channel.url
            }
        }
    })
}

/**
 * Player menu > Test this channel. The player lets go of the stream first: most IPTV accounts allow
 * one connection, so a test running beside playback would measure a refusal or knock the player
 * off. Playback comes back as soon as the test ends, behind its result (or as soon as the dialog is
 * closed early) - live at the live edge, a film where it was.
 */
internal fun MainActivity.runChannelSpeedTest() {
    val stream = playerManager.lastResolvedStream?.takeIf { it.url.isNotBlank() } ?: return
    val channel = nowPlayingChannel
    val live = channel?.mediaType == MediaType.LIVE
    val resumeMs = if (live) 0L else playerManager.currentPosition.coerceAtLeast(0L)
    val durationMs = if (live) 0L else playerManager.duration.coerceAtLeast(0L)
    playerManager.stop()
    val target = SpeedTarget(channel?.name ?: getString(R.string.speed_test_channel), stream.userAgent, stream.headers, durationMs) { stream.url }
    runSpeedTests(getString(R.string.speed_test_channel), listOf(target)) { playerManager.replayLast(resumeMs) }
}
