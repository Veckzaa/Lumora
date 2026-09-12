package com.lumora

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

// Easter egg: up, left, down, right, twice on the remote, and a "6 7" emote does the meme's
// hand seesaw in the middle of the screen.

private val SIX_SEVEN_CODE = intArrayOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
)

// ponytail: process-wide state - there is one MainActivity, and a stale count only means one extra press.
private var sixSevenProgress = 0
private var sixSevenShowing = false

/** Follows key presses toward the code. Never consumes them: the D-pad keeps moving focus as usual. */
internal fun MainActivity.trackSixSevenCode(event: KeyEvent) {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
    sixSevenProgress = when (event.keyCode) {
        SIX_SEVEN_CODE[sixSevenProgress] -> sixSevenProgress + 1
        SIX_SEVEN_CODE[0] -> 1
        else -> 0
    }
    if (sixSevenProgress == SIX_SEVEN_CODE.size) {
        sixSevenProgress = 0
        showSixSeven()
    }
}

private fun MainActivity.showSixSeven() {
    if (sixSevenShowing) return
    val root = findViewById<FrameLayout>(android.R.id.content) ?: return
    fun px(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    val bold = ResourcesCompat.getFont(this, R.font.inter_bold)
    // ✋ rather than the palm-up hand the meme uses: that one is Unicode 14, and Fire OS 7.1's
    // emoji font would draw it as an empty box.
    fun hand(digit: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(context).apply {
            text = digit
            typeface = bold
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 120f)
            setShadowLayer(px(10f), 0f, px(3f), Color.BLACK)
        })
        addView(TextView(context).apply {
            text = "✋"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
        })
    }
    val swing = px(36f)
    val six = hand("6").apply { translationY = swing }
    val seven = hand("7").apply { translationY = -swing }
    val emote = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        // Above the player overlay and the side menu, which carry elevation of their own.
        elevation = px(100f)
        addView(six)
        addView(seven, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = px(56f).toInt() })
    }
    root.addView(emote, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    sixSevenShowing = true

    // Six up-and-downs, the two hands always going opposite ways.
    fun seesaw(view: View) = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, -view.translationY).apply {
        duration = 220
        repeatCount = 11
        repeatMode = ObjectAnimator.REVERSE
    }
    val popIn = AnimatorSet().apply {
        playTogether(ObjectAnimator.ofFloat(emote, View.SCALE_X, 0f, 1f), ObjectAnimator.ofFloat(emote, View.SCALE_Y, 0f, 1f))
        duration = 300
        interpolator = OvershootInterpolator()
    }
    val bounce = AnimatorSet().apply { playTogether(seesaw(six), seesaw(seven)) }
    val fadeOut = ObjectAnimator.ofFloat(emote, View.ALPHA, 1f, 0f).setDuration(300)
    AnimatorSet().apply {
        playSequentially(popIn, bounce, fadeOut)
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                root.removeView(emote)
                sixSevenShowing = false
            }
        })
        start()
    }
}
