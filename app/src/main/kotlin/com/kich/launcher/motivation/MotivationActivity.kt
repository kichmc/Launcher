package com.kich.launcher.motivation

import com.kich.launcher.lock.finishIfProfileLocked
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.kich.launcher.R
import com.kich.launcher.ui.LauncherAppTheme
import dagger.hilt.android.AndroidEntryPoint

/** Quote settings: interval, manual refresh, favorites and the user's own quotes. */
@AndroidEntryPoint
class MotivationActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        finishIfProfileLocked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MotivationScreen()
                }
            }
        }
    }
}

@Composable
private fun MotivationScreen() {
    val context = LocalContext.current
    val store = remember { MotivationStore(context) }
    var version by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf<Int?>(null) }

    fun changed() {
        version++
        MotivationWidgetProvider.updateAll(context)
    }

    val quote = remember(version) { store.current() }
    val isFavorite = remember(version) { store.isFavorite(quote) }
    val interval = remember(version) { store.intervalMinutes }
    val favoritesOnly = remember(version) { store.favoritesOnly }
    val custom = remember(version) { store.customQuotes() }
    val favorites = remember(version) { store.favorites() }
    val style = remember(version) { MotivationStyle.resolve(context, store) }
    val themeId = remember(version) { store.themeId }
    val themeIds = remember {
        buildList {
            addAll(MotivationStyle.PRESETS.map { it.id })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(MotivationStyle.THEME_DYNAMIC)
            add(MotivationStyle.THEME_CUSTOM)
        }
    }
    val swatches = remember(version) {
        themeIds.associateWith { MotivationStyle.colorsFor(context, it, store.customFill, store.customAccent) }
    }
    var opacityDraft by remember(version) { mutableStateOf(store.opacity.toFloat()) }
    var sizeDraft by remember(version) { mutableStateOf(store.sizeScale.toFloat()) }
    var fillHex by remember { mutableStateOf(MotivationStyle.toHex(store.customFill)) }
    var accentHex by remember { mutableStateOf(MotivationStyle.toHex(store.customAccent)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.motivation_title), style = MaterialTheme.typography.headlineMedium)

        val previewSp = (MotivationStyle.fixedTextSp(style.textSize) ?: 20f) * style.scale
        val cardShape = RoundedCornerShape(MotivationStyle.CORNER_DP[style.corner].dp)
        val cardAlpha = style.opacity / 100f
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(Color(style.fill).copy(alpha = cardAlpha))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(style.glow).copy(alpha = 0.4f * cardAlpha), Color.Transparent),
                        start = Offset(Float.POSITIVE_INFINITY, 0f),
                        end = Offset(0f, Float.POSITIVE_INFINITY),
                    ),
                ),
        ) {
            Text(
                quote,
                style = MaterialTheme.typography.titleLarge,
                // The card previews the real text size and size slider (Auto shows a medium size).
                fontSize = previewSp.sp,
                lineHeight = (previewSp * 1.3f).sp,
                color = Color(style.text),
                fontFamily = when (style.font) {
                    MotivationStyle.FONT_MONO -> FontFamily.Monospace
                    MotivationStyle.FONT_SANS -> FontFamily.SansSerif
                    else -> FontFamily.Serif
                },
                fontStyle = if (style.font == MotivationStyle.FONT_SERIF) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (style.font == MotivationStyle.FONT_SANS) FontWeight.Medium else null,
                modifier = Modifier.padding((24 * style.scale).dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { store.refresh(); changed() },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.motivation_new_quote)) }
            FilledTonalButton(
                onClick = { store.toggleFavorite(quote); changed() },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Text(
                    if (isFavorite) "\u2605 " + stringResource(R.string.motivation_favorited)
                    else "\u2606 " + stringResource(R.string.motivation_favorite),
                )
            }
        }

        Text(stringResource(R.string.motivation_interval_title), style = MaterialTheme.typography.titleMedium)
        MotivationStore.INTERVALS.chunked(3).forEach { rowValues ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowValues.forEach { minutes ->
                    val label = if (minutes < 60) {
                        stringResource(R.string.motivation_interval_minutes, minutes)
                    } else {
                        stringResource(R.string.motivation_interval_hours, minutes / 60)
                    }
                    val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    if (minutes == interval) {
                        Button(onClick = { }, modifier = modifier) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { store.intervalMinutes = minutes; changed() },
                            modifier = modifier,
                        ) { Text(label) }
                    }
                }
                repeat(3 - rowValues.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.motivation_favorites_only),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = favoritesOnly, onCheckedChange = { store.favoritesOnly = it; changed() })
        }

        Text(stringResource(R.string.motivation_style_title), style = MaterialTheme.typography.titleMedium)
        themeIds.chunked(4).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowIds.forEach { id ->
                    val (fill, glow, _) = swatches.getValue(id)
                    ThemeSwatch(
                        label = stringResource(MotivationStyle.themeNameRes(id)),
                        fill = Color(fill),
                        glow = Color(glow),
                        selected = id == themeId,
                        onClick = { store.themeId = id; changed() },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - rowIds.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }

        if (themeId == MotivationStyle.THEME_CUSTOM) {
            OutlinedTextField(
                value = fillHex,
                onValueChange = { text ->
                    fillHex = text.take(7)
                    MotivationStyle.parseHex(fillHex)?.let { store.customFill = it; changed() }
                },
                label = { Text(stringResource(R.string.motivation_custom_fill_hint)) },
                singleLine = true,
                isError = MotivationStyle.parseHex(fillHex) == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = accentHex,
                onValueChange = { text ->
                    accentHex = text.take(7)
                    MotivationStyle.parseHex(accentHex)?.let { store.customAccent = it; changed() }
                },
                label = { Text(stringResource(R.string.motivation_custom_accent_hint)) },
                singleLine = true,
                isError = MotivationStyle.parseHex(accentHex) == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            stringResource(R.string.motivation_size_scale_title, sizeDraft.toInt()),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = sizeDraft,
            onValueChange = { sizeDraft = it },
            onValueChangeFinished = { store.sizeScale = sizeDraft.toInt(); changed() },
            valueRange = 70f..150f,
        )

        Text(stringResource(R.string.motivation_corner_title), style = MaterialTheme.typography.bodyLarge)
        OptionRow(
            labels = listOf(
                stringResource(R.string.motivation_corner_sharp),
                stringResource(R.string.motivation_corner_soft),
                stringResource(R.string.motivation_corner_round),
            ),
            selected = style.corner,
            onSelect = { store.corner = it; changed() },
        )

        Text(stringResource(R.string.motivation_font_title), style = MaterialTheme.typography.bodyLarge)
        OptionRow(
            labels = listOf(
                stringResource(R.string.motivation_font_serif),
                stringResource(R.string.motivation_font_sans),
                stringResource(R.string.motivation_font_mono),
            ),
            selected = style.font,
            onSelect = { store.font = it; changed() },
        )

        Text(stringResource(R.string.motivation_size_title), style = MaterialTheme.typography.bodyLarge)
        OptionRow(
            labels = listOf(
                stringResource(R.string.motivation_size_auto),
                stringResource(R.string.motivation_size_small),
                stringResource(R.string.motivation_size_medium),
                stringResource(R.string.motivation_size_large),
            ),
            selected = style.textSize,
            onSelect = { store.textSize = it; changed() },
        )

        Text(stringResource(R.string.motivation_textcolor_title), style = MaterialTheme.typography.bodyLarge)
        OptionRow(
            labels = listOf(
                stringResource(R.string.motivation_textcolor_auto),
                stringResource(R.string.motivation_textcolor_light),
                stringResource(R.string.motivation_textcolor_dark),
            ),
            selected = remember(version) { store.textMode },
            onSelect = { store.textMode = it; changed() },
        )

        Text(
            stringResource(R.string.motivation_opacity_title, opacityDraft.toInt()),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = opacityDraft,
            onValueChange = { opacityDraft = it },
            onValueChangeFinished = { store.opacity = opacityDraft.toInt(); changed() },
            valueRange = 30f..100f,
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.motivation_show_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = style.showLabel, onCheckedChange = { store.showLabel = it; changed() })
        }

        TextButton(
            onClick = {
                store.resetStyle()
                fillHex = MotivationStyle.toHex(store.customFill)
                accentHex = MotivationStyle.toHex(store.customAccent)
                changed()
            },
        ) { Text(stringResource(R.string.motivation_style_reset)) }

        Text(
            stringResource(R.string.motivation_style_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.motivation_add_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(MotivationStore.MAX_LENGTH) },
            label = { Text(stringResource(R.string.motivation_add_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (store.addCustom(draft)) { draft = ""; changed() } },
            enabled = draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.motivation_add)) }

        QuoteList(
            title = stringResource(R.string.motivation_my_quotes),
            quotes = custom,
            onRemove = { store.removeCustom(it); changed() },
        )
        QuoteList(
            title = stringResource(R.string.motivation_my_favorites),
            quotes = favorites,
            onRemove = { store.removeFavorite(it); changed() },
        )

        Text(stringResource(R.string.motivation_backup_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Kich quotes", store.exportJson()))
                    backupMessage = R.string.motivation_backup_copied
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.motivation_backup_copy)) }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                    if (store.importJson(text)) {
                        backupMessage = R.string.motivation_backup_restored
                        changed()
                    } else {
                        backupMessage = R.string.motivation_backup_invalid
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.motivation_backup_paste)) }
        }
        backupMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }

        Text(
            stringResource(R.string.motivation_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuoteList(title: String, quotes: List<String>, onRemove: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (quotes.isEmpty()) {
        Text(
            stringResource(R.string.motivation_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    quotes.forEach { text ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(text) }) { Text(stringResource(R.string.motivation_remove)) }
        }
    }
}

@Composable
private fun OptionRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            if (index == selected) {
                Button(onClick = { }, modifier = modifier) { Text(label, maxLines = 1) }
            } else {
                OutlinedButton(onClick = { onSelect(index) }, modifier = modifier) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    label: String,
    fill: Color,
    glow: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(fill, fill, glow),
                        start = Offset(0f, Float.POSITIVE_INFINITY),
                        end = Offset(Float.POSITIVE_INFINITY, 0f),
                    ),
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
