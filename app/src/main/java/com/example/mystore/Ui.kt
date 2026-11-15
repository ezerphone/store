package com.example.mystore

import android.app.Activity
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

enum class BtnStyle { FILLED, TONAL, DANGER, TEXT }

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density

fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

fun Context.roundedBg(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

fun rippleBg(base: Drawable, rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), base, null)

fun Context.tv(t: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = t
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.icon(res: Int, color: Int): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

fun Context.button(p: Palette, label: String, style: BtnStyle = BtnStyle.FILLED, onClick: () -> Unit): TextView {
    val (bgColor, fgColor) = when (style) {
        BtnStyle.FILLED -> p.accent to p.onAccent
        BtnStyle.TONAL -> p.accentSoft to p.accent
        BtnStyle.DANGER -> p.danger to Color.WHITE
        BtnStyle.TEXT -> Color.TRANSPARENT to p.textDim
    }
    return tv(label, 14f, fgColor, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = rippleBg(roundedBg(bgColor, 14), withAlpha(fgColor, 0x33))
        isClickable = true
        setOnClickListener { onClick() }
    }
}

fun Context.cardView(p: Palette, padDp: Int = 16, radiusDp: Int = 20): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(p.surface, radiusDp, p.border, 1)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

fun Activity.showCardDialog(content: View): AlertDialog {
    val dlg = AlertDialog.Builder(this).setView(content).create()
    dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dlg.show()
    dlg.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(360)), WRAP)
    return dlg
}

fun Activity.confirmDialog(
    p: Palette,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit
) {
    var dlg: AlertDialog? = null
    val card = cardView(p, 22, 24)
    card.addView(tv(title, 18f, p.text, true))
    card.addView(tv(message, 14f, p.textDim).apply { setPadding(0, dp(8), 0, dp(22)) })
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    row.addView(button(p, "ביטול", BtnStyle.TEXT) { dlg?.dismiss() })
    row.addView(
        button(p, confirmLabel, BtnStyle.DANGER) { dlg?.dismiss(); onConfirm() },
        LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) }
    )
    card.addView(row)
    dlg = showCardDialog(card)
}

/** Dialog for entering a PIN. [verify] may reject the input and keep the dialog open. */
fun Activity.pinDialog(
    p: Palette,
    title: String,
    subtitle: String,
    verify: ((String) -> Boolean)? = null,
    errorText: String = "קוד שגוי",
    onOk: (String) -> Unit
) {
    var dlg: AlertDialog? = null
    lateinit var pad: PinPad
    pad = PinPad(this, p) { pin ->
        if (verify != null && !verify(pin)) {
            pad.clear()
            pad.message(errorText, true)
            pad.shake()
        } else {
            dlg?.dismiss()
            onOk(pin)
        }
    }
    val card = cardView(p, 22, 24).apply { gravity = Gravity.CENTER_HORIZONTAL }
    card.addView(tv(title, 18f, p.text, true))
    card.addView(tv(subtitle, 13f, p.textDim).apply { setPadding(0, dp(4), 0, dp(8)) })
    card.addView(pad.view, LinearLayout.LayoutParams(MATCH, WRAP))
    card.addView(button(p, "ביטול", BtnStyle.TEXT) { dlg?.dismiss() })
    val scroll = ScrollView(this).apply { addView(card) }
    dlg = showCardDialog(scroll)
}

/**
 * Keeps the text on a single line: the font shrinks (down to [minSp]) until the text fits in
 * [maxWidthPx]. If it still does not fit at [minSp], the end is cut with "...".
 */
fun TextView.fitToWidth(maxWidthPx: Int, maxSp: Float, minSp: Float) {
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    var size = maxSp
    if (maxWidthPx > 0) {
        val probe = TextPaint(paint)
        val str = text.toString()
        while (size > minSp) {
            probe.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
            if (probe.measureText(str) <= maxWidthPx) break
            size -= 0.5f
        }
    }
    setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(size, minSp))
}
