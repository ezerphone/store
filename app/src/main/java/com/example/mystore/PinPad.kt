package com.example.mystore

import android.animation.ObjectAnimator
import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Numeric keypad with PIN dots. Accepts 4-8 digits, submitted with the check key. */
class PinPad(
    private val ctx: Context,
    private val p: Palette,
    private val onSubmit: (String) -> Unit
) {
    private val pin = StringBuilder()

    private val dots = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private val msgView: TextView = ctx.tv(" ", 13f, p.textDim).apply { gravity = Gravity.CENTER }

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    init {
        view.addView(msgView, LinearLayout.LayoutParams(MATCH, WRAP))
        view.addView(
            dots,
            LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = ctx.dp(12)
                bottomMargin = ctx.dp(18)
            }
        )
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("DEL", "0", "OK")
        )
        for (r in rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (k in r) {
                row.addView(
                    key(k),
                    LinearLayout.LayoutParams(ctx.dp(60), ctx.dp(60)).apply {
                        setMargins(ctx.dp(8), ctx.dp(5), ctx.dp(8), ctx.dp(5))
                    }
                )
            }
            view.addView(row)
        }
        renderDots()
    }

    fun clear() {
        pin.setLength(0)
        renderDots()
    }

    fun message(m: String, error: Boolean) {
        msgView.text = m
        msgView.setTextColor(if (error) p.danger else p.textDim)
    }

    fun shake() {
        val d = ctx.dpf(10)
        ObjectAnimator.ofFloat(dots, View.TRANSLATION_X, 0f, d, -d, d * 0.6f, -d * 0.6f, 0f)
            .setDuration(320)
            .start()
    }

    private fun renderDots() {
        dots.removeAllViews()
        val count = maxOf(4, pin.length)
        for (i in 0 until count) {
            val filled = i < pin.length
            val dot = View(ctx).apply {
                background = ctx.roundedBg(if (filled) p.accent else p.border, 6)
            }
            dots.addView(
                dot,
                LinearLayout.LayoutParams(ctx.dp(12), ctx.dp(12)).apply {
                    setMargins(ctx.dp(6), 0, ctx.dp(6), 0)
                }
            )
        }
    }

    private fun key(k: String): View {
        val f = FrameLayout(ctx)
        f.isClickable = true
        f.setOnClickListener {
            f.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onKey(k)
        }
        f.background = rippleBg(ctx.roundedBg(p.surface, 30, p.border, 1), withAlpha(p.text, 0x22))
        when (k) {
            "DEL" -> f.addView(
                ctx.icon(R.drawable.ic_backspace, p.textDim),
                FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER)
            )
            "OK" -> {
                f.background = rippleBg(ctx.roundedBg(p.accent, 30), withAlpha(p.onAccent, 0x33))
                f.addView(
                    ctx.icon(R.drawable.ic_check, p.onAccent),
                    FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.CENTER)
                )
            }
            else -> f.addView(
                ctx.tv(k, 24f, p.text).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH, MATCH)
            )
        }
        return f
    }

    private fun onKey(k: String) {
        when (k) {
            "DEL" -> if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1)
            "OK" -> {
                submit()
                return
            }
            else -> if (pin.length < MAX_LEN) pin.append(k)
        }
        message(" ", false)
        renderDots()
    }

    private fun submit() {
        if (pin.length < MIN_LEN) {
            message("הקוד חייב להכיל לפחות $MIN_LEN ספרות", true)
            shake()
        } else {
            onSubmit(pin.toString())
        }
    }

    companion object {
        const val MIN_LEN = 4
        const val MAX_LEN = 8
    }
}
