package com.kich.launcher.feature.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kich.launcher.model.LauncherSettings

private val AccentPalette = listOf(
    0xFF1E88E5.toInt(), 0xFF00897B.toInt(), 0xFF43A047.toInt(), 0xFFF9A825.toInt(), 0xFFFB8C00.toInt(),
    0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(),
)

/** Light/dark/system choice and the accent color swatches. */
@Composable
internal fun AppearanceRows(
    themeMode: String,
    accent: Int,
    onThemeMode: (String) -> Unit,
    onAccent: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val modes = listOf(
                LauncherSettings.THEME_SYSTEM to R.string.settings_theme_system,
                LauncherSettings.THEME_LIGHT to R.string.settings_theme_light,
                LauncherSettings.THEME_DARK to R.string.settings_theme_dark,
            )
            modes.forEach { (key, label) ->
                val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                if (key == themeMode) {
                    Button(onClick = { onThemeMode(key) }, modifier = modifier) { Text(stringResource(label)) }
                } else {
                    OutlinedButton(onClick = { onThemeMode(key) }, modifier = modifier) { Text(stringResource(label)) }
                }
            }
        }

        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.titleMedium)
        val swatches = listOf(LauncherSettings.ACCENT_DEFAULT) + AccentPalette
        swatches.chunked(5).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowColors.forEach { argb ->
                    val selected = argb == accent
                    val description = if (argb == LauncherSettings.ACCENT_DEFAULT) {
                        stringResource(R.string.settings_accent_default)
                    } else {
                        stringResource(R.string.settings_accent_color)
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (argb == LauncherSettings.ACCENT_DEFAULT) MaterialTheme.colorScheme.surfaceVariant else Color(argb))
                            .border(
                                BorderStroke(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                ),
                                CircleShape,
                            )
                            .clickable { onAccent(argb) }
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (argb == LauncherSettings.ACCENT_DEFAULT) {
                            Text("A", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** About AK Launcher: name, version and the upstream credit the Apache-2.0 license asks for. */
@Composable
internal fun AboutRows() {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_about_name), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_about_version, version), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_about_credit), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty().ifEmpty { "?" }
