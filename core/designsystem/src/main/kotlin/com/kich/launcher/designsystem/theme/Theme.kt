package com.kich.launcher.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/**
 * The launcher theme. Defaults to the branded Arkikeskus color scheme; Material You dynamic color
 * is opt-in via [dynamicColor] (off by default so the brand identity holds out of the box).
 */
@Composable
fun LauncherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    /** The user's accent color; null keeps the scheme's own primary colors. */
    accentColor: Color? = null,
    /** The user's font for every text the launcher draws itself ([withFontFamily]). */
    fontFamily: FontFamily = FontFamily.Default,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val themed = if (accentColor != null) colorScheme.withAccent(accentColor, darkTheme) else colorScheme
    val launcherColors = if (darkTheme) DarkLauncherColors else LightLauncherColors

    CompositionLocalProvider(LocalLauncherColors provides launcherColors) {
        val typography = remember(fontFamily) { LauncherTypography.withFontFamily(fontFamily) }
        MaterialTheme(
            colorScheme = themed,
            typography = typography,
            content = content,
        )
    }
}

/** [this] with its primary color family derived from the user's [accent]. */
private fun ColorScheme.withAccent(accent: Color, dark: Boolean): ColorScheme {
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val container = if (dark) lerp(accent, Color.Black, 0.55f) else lerp(accent, Color.White, 0.8f)
    val onContainer = if (dark) lerp(accent, Color.White, 0.8f) else lerp(accent, Color.Black, 0.65f)
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        surfaceTint = accent,
    )
}
