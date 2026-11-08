package com.kich.launcher.motivation

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.kich.launcher.R
import kotlin.math.ceil
import kotlin.math.floor

/** A built-in colour theme: card colour, corner glow and accent (label + bar). All opaque ARGB. */
data class ThemePreset(
    val id: String,
    @StringRes val nameRes: Int,
    val fill: Int,
    val glow: Int,
    val accent: Int,
)

/** Everything the widget needs to draw, resolved from the user's settings. */
data class ResolvedStyle(
    val fill: Int,
    val glow: Int,
    val accent: Int,
    val text: Int,
    val lightText: Boolean,
    val corner: Int,
    val font: Int,
    val maxSp: Float,
    val opacity: Int,
    val showLabel: Boolean,
)

/** Largest text size that fits ([sp]) and how many lines to allow. */
data class TextFit(val sp: Float, val maxLines: Int)

object MotivationStyle {
    const val THEME_DYNAMIC = "dynamic"
    const val THEME_CUSTOM = "custom"
    const val DEFAULT_THEME = "midnight"
    const val DEFAULT_CUSTOM_FILL = 0xFF1E2A3A.toInt()
    const val DEFAULT_CUSTOM_ACCENT = 0xFFFFC371.toInt()

    const val FONT_SERIF = 0
    const val FONT_SANS = 1
    const val FONT_MONO = 2

    /** Corner radius (dp) for sharp / soft / round. Keep in step with the widget_motivation_fill_* drawables. */
    val CORNER_DP = listOf(12, 28, 44)

    /** Biggest quote text size (sp) for the small / medium / large text setting. */
    private val MAX_SP = listOf(16f, 22f, 30f)

    private const val MIN_SP = 11f
    private const val LINE_FACTOR = 1.4f

    val PRESETS = listOf(
        ThemePreset("midnight", R.string.motivation_theme_midnight, 0xFF151B3F.toInt(), 0xFFFF8A5B.toInt(), 0xFFFFC371.toInt()),
        ThemePreset("sunset", R.string.motivation_theme_sunset, 0xFF3B1D4A.toInt(), 0xFFFF6F61.toInt(), 0xFFFFD27D.toInt()),
        ThemePreset("forest", R.string.motivation_theme_forest, 0xFF10281F.toInt(), 0xFF3DDC97.toInt(), 0xFFB9F6CA.toInt()),
        ThemePreset("ocean", R.string.motivation_theme_ocean, 0xFF0B2B40.toInt(), 0xFF29B6F6.toInt(), 0xFF81D4FA.toInt()),
        ThemePreset("paper", R.string.motivation_theme_paper, 0xFFF6F1E7.toInt(), 0xFFFFB74D.toInt(), 0xFFB5541B.toInt()),
        ThemePreset("amoled", R.string.motivation_theme_amoled, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
    )

    fun isKnownTheme(id: String): Boolean =
        id == THEME_DYNAMIC || id == THEME_CUSTOM || PRESETS.any { it.id == id }

    @StringRes
    fun themeNameRes(id: String): Int = when (id) {
        THEME_DYNAMIC -> R.string.motivation_theme_dynamic
        THEME_CUSTOM -> R.string.motivation_theme_custom
        else -> (PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()).nameRes
    }

    /** (fill, glow, accent) for [themeId]. Dynamic follows the system (Material You) colours on Android 12+. */
    fun colorsFor(context: Context, themeId: String, customFill: Int, customAccent: Int): Triple<Int, Int, Int> {
        if (themeId == THEME_CUSTOM) return Triple(customFill, customAccent, customAccent)
        if (themeId == THEME_DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return dynamicColors(context)
        val preset = PRESETS.firstOrNull { it.id == themeId } ?: PRESETS.first()
        return Triple(preset.fill, preset.glow, preset.accent)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dynamicColors(context: Context): Triple<Int, Int, Int> {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Triple(
                context.getColor(android.R.color.system_neutral1_900),
                context.getColor(android.R.color.system_accent1_300),
                context.getColor(android.R.color.system_accent1_200),
            )
        } else {
            Triple(
                context.getColor(android.R.color.system_accent1_100),
                context.getColor(android.R.color.system_accent2_200),
                context.getColor(android.R.color.system_accent1_700),
            )
        }
    }

    fun resolve(context: Context, store: MotivationStore): ResolvedStyle {
        val (fill, glow, accent) = colorsFor(context, store.themeId, store.customFill, store.customAccent)
        val light = when (store.textMode) {
            1 -> true
            2 -> false
            else -> ColorUtils.calculateLuminance(fill) < 0.5
        }
        return ResolvedStyle(
            fill = fill,
            glow = glow,
            accent = accent,
            text = if (light) 0xFFFFFFFF.toInt() else 0xFF1B1B1F.toInt(),
            lightText = light,
            corner = store.corner,
            font = store.font,
            maxSp = MAX_SP[store.textScale.coerceIn(0, 2)],
            opacity = store.opacity,
            showLabel = store.showLabel,
        )
    }

    /**
     * Picks the biggest text size (down to [MIN_SP]) at which [text] should fit a box of
     * [widthDp] x [heightDp]. It is an estimate from average glyph width, not a real measurement,
     * so it can be a little off for unusual words; the TextView still ellipsizes if it is.
     */
    fun fitText(text: String, widthDp: Float, heightDp: Float, maxSp: Float, font: Int): TextFit {
        val charWidth = when (font) {
            FONT_MONO -> 0.62f
            FONT_SANS -> 0.52f
            else -> 0.50f
        }
        val w = widthDp.coerceAtLeast(40f)
        val h = heightDp.coerceAtLeast(MIN_SP * LINE_FACTOR)
        var sp = maxSp
        while (sp >= MIN_SP) {
            val perLine = floor(w / (sp * charWidth)).coerceAtLeast(1f)
            val lines = ceil(text.length * 1.08f / perLine).toInt().coerceAtLeast(1)
            if (lines * sp * LINE_FACTOR <= h) {
                val room = floor(h / (sp * LINE_FACTOR)).toInt()
                return TextFit(sp, minOf(lines + 1, room).coerceAtLeast(1))
            }
            sp -= 1f
        }
        return TextFit(MIN_SP, floor(h / (MIN_SP * LINE_FACTOR)).toInt().coerceAtLeast(1))
    }

    /** "#RRGGBB" or "RRGGBB" to opaque ARGB, or null when it is not a valid colour. */
    fun parseHex(input: String): Int? {
        val s = input.trim().removePrefix("#")
        if (s.length != 6 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000 or s.toLong(16)).toInt()
    }

    fun toHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
}
