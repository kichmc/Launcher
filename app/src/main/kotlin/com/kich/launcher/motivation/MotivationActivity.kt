package com.kich.launcher.motivation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kich.launcher.R
import com.kich.launcher.ui.LauncherAppTheme
import dagger.hilt.android.AndroidEntryPoint

/** Quote settings: interval, manual refresh, favorites and the user's own quotes. */
@AndroidEntryPoint
class MotivationActivity : ComponentActivity() {
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

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                quote,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(24.dp),
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
