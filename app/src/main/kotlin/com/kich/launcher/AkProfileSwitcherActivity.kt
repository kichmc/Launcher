package com.kich.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.kich.launcher.feature.settings.SettingsViewModel
import com.kich.launcher.ui.LauncherAppTheme

/** Dedicated Quick Settings destination: shows only the AK workspace selector, never Settings. */
@AndroidEntryPoint
class AkProfileSwitcherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherAppTheme {
                AkProfileSwitcherScreen(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun AkProfileSwitcherScreen(
    onDone: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var removingId by remember { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("AK workspaces", style = MaterialTheme.typography.headlineSmall)
            Text("Choose the workspace you want to use.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            profiles.forEach { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (!profile.active) viewModel.activateAkProfile(profile.id)
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (profile.active) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            if (profile.active) Text("CURRENT WORKSPACE", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (!profile.active) TextButton(onClick = { viewModel.activateAkProfile(profile.id) }) {
                            Text("Switch")
                        }
                        if (profile.id != "user1") TextButton(onClick = {
                            if (viewModel.lockManager.store.isEnabled(profile.id)) removingId = profile.id
                            else viewModel.deleteAkProfile(profile.id)
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { showAdd = true },
                enabled = profiles.size < 5,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Add workspace") }
            Spacer(Modifier.width(1.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }

    removingId?.let { target ->
        com.kich.launcher.feature.settings.PinDialog("Enter this workspace’s PIN to remove it", askOld = true, askNew = false, onDismiss = { removingId = null }) { pin, _ ->
            val done = kotlinx.coroutines.CompletableDeferred<String?>()
            viewModel.deleteAkProfile(target, pin?.copyOf()) { done.complete(com.kich.launcher.feature.settings.pinMessage(it)) }
            done.await()
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; newName = "" },
            title = { Text("Add workspace") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(24) },
                    label = { Text("Workspace name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    viewModel.createAkProfile(newName)
                    showAdd = false
                    newName = ""
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}
