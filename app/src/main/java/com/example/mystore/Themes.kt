package com.example.mystore

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

data class Accent(val name: String, val light: Int, val dark: Int)

class Palette(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val text: Int,
    val textDim: Int,
    val border: Int,
    val accent: Int,
    val onAccent: Int,
    val accentSoft: Int,
    val success: Int,
    val warn: Int,
    val danger: Int
)

object Themes {
    val accents = listOf(
        Accent("כחול", 0xFF2563EB.toInt(), 0xFF7CA9FF.toInt()),
        Accent("סגול", 0xFF7C3AED.toInt(), 0xFFB49CFF.toInt()),
        Accent("ירוק", 0xFF059669.toInt(), 0xFF4ADE9B.toInt()),
        Accent("כתום", 0xFFEA580C.toInt(), 0xFFFB923C.toInt()),
        Accent("ורוד", 0xFFDB2777.toInt(), 0xFFF472B6.toInt()),
        Accent("טורקיז", 0xFF0891B2.toInt(), 0xFF22D3EE.toInt())
    )

    /** Text color that is readable on top of [color]. */
    fun onColor(color: Int): Int {
        val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return if (lum > 150) 0xFF0B0F19.toInt() else Color.WHITE
    }

    fun isDark(context: Context, prefs: Prefs): Boolean = when (prefs.displayMode) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(context: Context, prefs: Prefs): Palette {
        val dark = isDark(context, prefs)
        val a = accents[prefs.themeIndex]
        val accent = if (dark) a.dark else a.light
        return if (dark) {
            Palette(
                dark = true,
                bg = 0xFF0B0F19.toInt(),
                surface = 0xFF151B2B.toInt(),
                surfaceAlt = 0xFF1F273B.toInt(),
                text = 0xFFF1F5F9.toInt(),
                textDim = 0xFF94A3B8.toInt(),
                border = 0xFF263049.toInt(),
                accent = accent,
                onAccent = onColor(accent),
                accentSoft = withAlpha(accent, 0x2E),
                success = 0xFF4ADE80.toInt(),
                warn = 0xFFFB923C.toInt(),
                danger = 0xFFEF4444.toInt()
            )
        } else {
            Palette(
                dark = false,
                bg = 0xFFF5F6FA.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                surfaceAlt = 0xFFEBEDF3.toInt(),
                text = 0xFF0F172A.toInt(),
                textDim = 0xFF64748B.toInt(),
                border = 0xFFE4E7EE.toInt(),
                accent = accent,
                onAccent = onColor(accent),
                accentSoft = withAlpha(accent, 0x22),
                success = 0xFF16A34A.toInt(),
                warn = 0xFFEA580C.toInt(),
                danger = 0xFFDC2626.toInt()
            )
        }
    }
}
