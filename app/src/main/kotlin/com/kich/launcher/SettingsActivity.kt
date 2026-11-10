package com.kich.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import com.kich.launcher.ui.LauncherAppTheme
import com.kich.launcher.feature.settings.SettingsScreen

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_AK_PROFILES = "open_ak_profiles" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherAppTheme {
                SettingsScreen(openAkProfiles = intent.getBooleanExtra(EXTRA_OPEN_AK_PROFILES, false))
            }
        }
    }
}
