package com.kich.launcher.users

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kich.launcher.ui.LauncherAppTheme

class UserManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherAppTheme {
                var users by remember { mutableStateOf(AkUsers.all(this)) }
                var name by remember { mutableStateOf("") }
                var selectedId by remember { mutableStateOf(users.firstOrNull { it.id != "system" }?.id) }
                var refresh by remember { mutableIntStateOf(0) }
                val _refresh = refresh
                val selected = users.firstOrNull { it.id == selectedId && it.id != "system" }
                val settings = selected?.let { AkUsers.settings(this, it.id) }
                Surface {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("AK User Manager", style = MaterialTheme.typography.headlineSmall)
                        Text("One UI remains the phone's normal home screen. Each AK profile is stored as its own .akprofile file in app-private storage. Profiles are launcher configurations, not separate Android accounts or isolated app data.", style = MaterialTheme.typography.bodyMedium)
                        Text("AK profiles: ${users.size - 1}/${AkUsers.MAX_AK_PROFILES}", style = MaterialTheme.typography.labelLarge)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(users, key = { it.id }) { user ->
                                ElevatedCard(Modifier.fillMaxWidth().clickable { selectedId = if (user.id == "system") null else user.id }) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(user.name, style = MaterialTheme.typography.titleMedium)
                                            Text(if (user.id == "system") "Samsung system home · protected" else "AK profile · ${user.profileFile ?: "profile file"}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (user.id != "system") TextButton(onClick = {
                                            AkUsers.delete(this@UserManagerActivity, user.id)
                                            users = AkUsers.all(this@UserManagerActivity)
                                            if (selectedId == user.id) selectedId = users.firstOrNull { it.id != "system" }?.id
                                        }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                        if (selected != null && settings != null) {
                            HorizontalDivider()
                            Text("${selected.name} · Profile settings", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Automatically place apps")
                                Switch(checked = settings.autoPlaceApps, onCheckedChange = { value ->
                                    AkUsers.updateSettings(this@UserManagerActivity, selected.id, settings.copy(autoPlaceApps = value)); refresh++
                                })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Optional in-app profile lock")
                                Switch(checked = settings.customProfileLockEnabled, onCheckedChange = { value ->
                                    AkUsers.updateSettings(this@UserManagerActivity, selected.id, settings.copy(customProfileLockEnabled = value)); refresh++
                                })
                            }
                            Text("Grid: ${settings.columns} columns × ${settings.rows} rows", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { AkUsers.updateSettings(this@UserManagerActivity, selected.id, settings.copy(columns = (settings.columns - 1).coerceAtLeast(3))); refresh++ }) { Text("− columns") }
                                OutlinedButton(onClick = { AkUsers.updateSettings(this@UserManagerActivity, selected.id, settings.copy(columns = (settings.columns + 1).coerceAtMost(8))); refresh++ }) { Text("+ columns") }
                            }
                        }
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = users.size - 1 < AkUsers.MAX_AK_PROFILES)
                        Button(
                            onClick = {
                                if (AkUsers.add(this@UserManagerActivity, name)) {
                                    users = AkUsers.all(this@UserManagerActivity)
                                    selectedId = users.lastOrNull()?.id
                                    name = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = users.size - 1 < AkUsers.MAX_AK_PROFILES && name.isNotBlank(),
                        ) { Text(if (users.size - 1 >= AkUsers.MAX_AK_PROFILES) "Maximum 5 AK profiles reached" else "＋ Add AK user") }
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                }
            }
        }
    }
}
