package com.kich.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.kich.launcher.data.SettingsRepository
import com.kich.launcher.designsystem.theme.LauncherTheme
import com.kich.launcher.model.LauncherSettings
import javax.inject.Inject

/** The user's theme mode and accent color. */
data class ThemeLook(
    val mode: String = LauncherSettings.THEME_SYSTEM,
    val accent: Int = LauncherSettings.ACCENT_DEFAULT,
)

/** The settings the theme itself depends on: font, light/dark mode and accent color. */
@HiltViewModel
class ThemeViewModel @Inject constructor(settingsRepository: SettingsRepository) : ViewModel() {
    val fontFamily: StateFlow<String> = settingsRepository.settings
        .map { it.fontFamily }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherSettings.FONT_DEFAULT)

    val look: StateFlow<ThemeLook> = settingsRepository.settings
        .map { ThemeLook(it.themeMode, it.accentColor) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeLook())
}

/** [LauncherTheme] with the user's font, mode and accent, for every activity of the launcher. */
@Composable
fun LauncherAppTheme(content: @Composable () -> Unit) {
    val viewModel = hiltViewModel<ThemeViewModel>()
    val font by viewModel.fontFamily.collectAsStateWithLifecycle()
    val look by viewModel.look.collectAsStateWithLifecycle()
    val dark = when (look.mode) {
        LauncherSettings.THEME_DARK -> true
        LauncherSettings.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    LauncherTheme(
        darkTheme = dark,
        accentColor = if (look.accent == LauncherSettings.ACCENT_DEFAULT) null else Color(look.accent),
        fontFamily = launcherFontFamily(font),
        content = content,
    )
}
