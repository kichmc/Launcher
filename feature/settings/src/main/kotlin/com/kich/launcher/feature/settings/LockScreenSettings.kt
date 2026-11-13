package com.kich.launcher.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kich.launcher.data.lock.BiometricUnlock
import com.kich.launcher.data.lock.PinCheck
import com.kich.launcher.data.lock.PinPolicy
import com.kich.launcher.ui.expressive.ExpressiveActionRow
import kotlinx.coroutines.launch

private enum class LockDialog { None, SetPin, ChangePin, Disable }

/**
 * Lock screen settings for the ACTIVE AK profile only. This protects AK's own profile; it is not
 * Android's device lock and never changes it.
 */
@Composable
internal fun LockScreenSettingsContent(viewModel: SettingsViewModel, profileName: String) {
    val state by viewModel.lockState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(LockDialog.None) }
    var note by remember { mutableStateOf<String?>(null) }
    val availability = remember(state.fingerprint, state.lockEnabled) { BiometricUnlock.availability(context) }

    Text(
        "Applies only to “$profileName”. AK’s lock protects this launcher profile; it does not replace or change your phone’s own screen lock.",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
    )
    var returnToFirst by remember { mutableStateOf(viewModel.lockManager.returnToFirstOnScreenOff) }
    SwitchRow(
        label = "Return to User 1 when screen turns off",
        description = "Applies to all workspaces. User 1’s own lock (if any) then appears.",
        checked = returnToFirst,
        onCheckedChange = { returnToFirst = it; viewModel.lockManager.returnToFirstOnScreenOff = it },
    )
    if (!state.lockEnabled) {
        ExpressiveActionRow("Set AK PIN", "Turn on the lock screen for this profile.", onClick = { dialog = LockDialog.SetPin })
    } else {
        ExpressiveActionRow("Change PIN", "Needs your current PIN.", onClick = { dialog = LockDialog.ChangePin })
        ExpressiveActionRow("Turn off lock screen", "Needs your current PIN.", onClick = { dialog = LockDialog.Disable })
        SwitchRow(
            label = "Fingerprint unlock",
            description = when (availability) {
                BiometricUnlock.Availability.Available -> "Uses Android’s fingerprint check. PIN always still works."
                BiometricUnlock.Availability.NotEnrolled -> "Add a fingerprint in Android settings first."
                BiometricUnlock.Availability.NoHardware -> "This device has no fingerprint sensor."
                BiometricUnlock.Availability.Unavailable -> "Fingerprint is unavailable right now."
            },
            checked = state.fingerprint,
            enabled = availability == BiometricUnlock.Availability.Available || state.fingerprint,
            onCheckedChange = { on ->
                if (!on) { viewModel.disableBiometric(); note = null }
                else BiometricUnlock.enroll(context, state.profileId, "Enable fingerprint unlock", "Cancel") { r ->
                    when (r) {
                        is BiometricUnlock.Result.Success ->
                            if (r.iv != null && r.blob != null) viewModel.storeBiometric(r.iv!!, r.blob!!)
                        is BiometricUnlock.Result.Failed -> note = "Fingerprint could not be enabled."
                        else -> Unit
                    }
                }
            },
        )
    }
    note?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }

    when (dialog) {
        LockDialog.SetPin -> PinDialog("Set AK PIN", askOld = false, onDismiss = { dialog = LockDialog.None }) { _, new ->
            if (viewModel.setLockPin(new)) null else "Could not save the PIN."
        }
        LockDialog.ChangePin -> PinDialog("Change PIN", askOld = true, onDismiss = { dialog = LockDialog.None }) { old, new ->
            pinMessage(viewModel.changeLockPin(old!!, new))
        }
        LockDialog.Disable -> PinDialog("Turn off lock screen", askOld = true, askNew = false, onDismiss = { dialog = LockDialog.None }) { old, _ ->
            pinMessage(viewModel.disableLock(old!!))
        }
        LockDialog.None -> Unit
    }
}

fun pinMessage(check: PinCheck): String? = when (check) {
    PinCheck.Success -> null
    is PinCheck.Wrong -> if (check.lockoutMs > 0) "Wrong PIN. Try again in ${check.lockoutMs / 1000}s." else "Wrong PIN. ${check.attemptsLeft} tries left before a delay."
    is PinCheck.LockedOut -> "Too many attempts. Try again in ${(check.remainingMs + 999) / 1000}s."
    PinCheck.NotConfigured -> "No PIN is set."
    PinCheck.Unavailable -> "PIN check unavailable. Try again."
}

/** PIN entry dialog. [onSubmit] returns an error message, or null to close. PINs are never logged or saved in state. */
@Composable
fun PinDialog(
    title: String,
    askOld: Boolean,
    askNew: Boolean = true,
    onDismiss: () -> Unit,
    onSubmit: suspend (old: CharArray?, new: CharArray) -> String?,
) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                if (askOld) PinField(old, if (askNew) "Current PIN" else "PIN") { old = it }
                if (askNew) {
                    PinField(new, "New PIN (${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} digits)") { new = it }
                    PinField(confirm, "Confirm PIN") { confirm = it }
                }
                error?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                error = when {
                    askOld && old.isEmpty() -> "Enter your PIN."
                    askNew && !PinPolicy.isValid(new) -> "Use ${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} digits."
                    askNew && new != confirm -> "The PINs don’t match."
                    else -> null
                }
                if (error != null) return@TextButton
                busy = true
                scope.launch {
                    val oldChars = if (askOld) old.toCharArray() else null
                    val newChars = if (askNew) new.toCharArray() else CharArray(0)
                    val result = onSubmit(oldChars, newChars)
                    oldChars?.fill('0'); newChars.fill('0')
                    busy = false
                    if (result == null) onDismiss() else { error = result; old = "" }
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
