package com.lumora.player

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the display to a refresh rate the video's frame rate divides into, so a 24 fps
 * film isn't shown on a 60 Hz mode - there every fifth frame is held an extra refresh, which
 * is the stutter on slow pans that even a stick's owner notices. Fire TV (4K, Cube) and
 * Android TV expose the TV's modes through [Display.getSupportedModes]; the request goes
 * through the window's preferredDisplayModeId, which Fire OS honours only when the user has
 * "Match original frame rate" on in the system display settings. A 1st-gen stick or a TV with
 * a single mode has nothing to switch to and this does nothing.
 *
 * Films and series only: the switch blanks the screen for a second or two, which is fine once
 * per title and unbearable when zapping live channels.
 */
object FrameRateMatcher {
    const val PREF_MATCH_FRAME_RATE = "player_match_frame_rate"
    private const val TAG = "FrameRate"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE).getBoolean(PREF_MATCH_FRAME_RATE, true)

    /** Picks the lowest refresh rate that fits [frameRate] at the current resolution; no-op
     *  when the current mode already fits (29.97 on 60 Hz, 25 on 50 Hz) or nothing does. */
    @Suppress("DEPRECATION")
    fun match(activity: Activity, frameRate: Float) {
        if (frameRate <= 0f) return
        val display = activity.windowManager.defaultDisplay ?: return
        val current = display.mode
        if (fits(current.refreshRate, frameRate)) return
        val target = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && fits(it.refreshRate, frameRate) }
            .minByOrNull { it.refreshRate } ?: return
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId == target.modeId) return
        Log.i(TAG, "$frameRate fps: ${current.refreshRate} Hz -> ${target.refreshRate} Hz")
        attrs.preferredDisplayModeId = target.modeId
        activity.window.attributes = attrs
    }

    /** Back to the system's mode once playback is over. */
    fun reset(activity: Activity) {
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId == 0) return
        attrs.preferredDisplayModeId = 0
        activity.window.attributes = attrs
    }

    // 23.976 on a 24 Hz mode, or 29.97 on 60, is a drift of one frame every 40 s: not a switch
    // worth a blank screen. 24 on 60 (2.5) or 25 on 60 (2.4) is.
    private fun fits(refreshRate: Float, frameRate: Float): Boolean {
        val ratio = refreshRate / frameRate
        val n = ratio.roundToInt()
        return n >= 1 && abs(ratio - n) < 0.01f
    }
}
