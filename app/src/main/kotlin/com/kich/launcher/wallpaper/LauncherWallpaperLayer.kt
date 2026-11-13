package com.kich.launcher.wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws the launcher's own wallpaper behind the home screen when the user set one. Nothing is
 * drawn (the phone's system wallpaper shows through, as before) when none is set. Reloaded each
 * time the launcher comes back to the foreground, so a change in the wallpaper screen shows at once.
 */
@Composable
fun LauncherWallpaperLayer() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var profileId by remember {
        mutableStateOf(context.getSharedPreferences("ak_workspace_profiles", android.content.Context.MODE_PRIVATE)
            .getString("active", "user1") ?: "user1")
    }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("ak_workspace_profiles", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "active") profileId = prefs.getString("active", "user1") ?: "user1"
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val image by produceState<ImageBitmap?>(initialValue = null, tick, profileId) {
        // Clear the previous profile's image before loading the newly active profile. produceState
        // keeps its old value when a key changes; without this reset, a profile with no wallpaper
        // briefly (or indefinitely after a failed read) displays the previous profile's wallpaper.
        value = null
        value = withContext(Dispatchers.IO) {
            WallpaperStore.loadLauncherWallpaper(context)?.asImageBitmap()
        }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
