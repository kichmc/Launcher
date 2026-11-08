package com.kich.launcher.wallpaper

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kich.launcher.R
import com.kich.launcher.ui.LauncherAppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Standalone wallpaper screen: pick an image from storage, preview it, apply to home, lock or both. */
@AndroidEntryPoint
class WallpaperActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WallpaperScreen()
                }
            }
        }
    }
}

@Composable
private fun WallpaperScreen() {
    val context = LocalContext.current
    val store = remember { WallpaperStore(context) }
    val scope = rememberCoroutineScope()
    val metrics = context.resources.displayMetrics
    val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels)

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf(store.lastTarget) }

    // The remembered selection comes back on every start.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        preview = withContext(Dispatchers.IO) { store.loadSelected() }
        loaded = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { store.saveFromUri(uri, maxSide) }.getOrNull() }
            if (bitmap != null) {
                preview = bitmap
                status = null
                errorDetail = null
            } else {
                status = R.string.wallpaper_error_read
                errorDetail = store.lastError
            }
            busy = false
        }
    }

    val canSet = remember { store.canSetWallpaper() }

    fun applyTo(chosen: WallpaperTarget) {
        val bitmap = preview ?: return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.apply(bitmap, chosen) }
            if (ok) target = chosen
            status = if (ok) R.string.wallpaper_applied else R.string.wallpaper_error_apply
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.wallpaper_title), style = MaterialTheme.typography.headlineMedium)

        // Preview in the screen's own aspect ratio, so the crop shown is the crop applied.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 460.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val image = preview
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.wallpaper_preview),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (loaded) {
                Text(
                    stringResource(R.string.wallpaper_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        Button(
            onClick = { picker.launch("image/*") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.wallpaper_choose)) }

        if (!canSet) {
            Text(
                stringResource(R.string.wallpaper_not_allowed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            val enabled = preview != null && !busy && canSet
            FilledTonalButton(
                onClick = { applyTo(WallpaperTarget.HOME) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.wallpaper_home)) }
            FilledTonalButton(
                onClick = { applyTo(WallpaperTarget.LOCK) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.wallpaper_lock)) }
            FilledTonalButton(
                onClick = { applyTo(WallpaperTarget.BOTH) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.wallpaper_both)) }
        }

        status?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
        }
        errorDetail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (store.hasApplied && status == null) {
            Text(
                stringResource(R.string.wallpaper_last_applied, stringResource(targetLabel(target))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun targetLabel(target: WallpaperTarget): Int = when (target) {
    WallpaperTarget.HOME -> R.string.wallpaper_home
    WallpaperTarget.LOCK -> R.string.wallpaper_lock
    WallpaperTarget.BOTH -> R.string.wallpaper_both
}
