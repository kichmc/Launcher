package com.kich.launcher.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kich.launcher.data.lock.BiometricUnlock
import com.kich.launcher.data.lock.LockMethod
import com.kich.launcher.data.lock.LockPolicy
import com.kich.launcher.data.lock.PasswordPolicy
import com.kich.launcher.data.lock.PatternPolicy
import com.kich.launcher.data.lock.PinCheck
import com.kich.launcher.data.lock.PinPolicy
import com.kich.launcher.ui.expressive.ExpressiveActionRow
import com.kich.launcher.ui.lock.PatternPad
import kotlinx.coroutines.launch

private enum class LockFlow { None, ChooseForSetup, Setup, VerifyForChange, ChooseForChange, CreateForChange, VerifyForRemove }

fun LockMethod.label(): String = when (this) {
    LockMethod.PIN -> "PIN"
    LockMethod.PASSWORD -> "Password"
    LockMethod.PATTERN -> "Pattern"
}

private fun LockMethod.blurb(): String = when (this) {
    LockMethod.PIN -> "${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} digits, quick to enter."
    LockMethod.PASSWORD -> "${PasswordPolicy.MIN_LENGTH}+ characters, strongest option."
    LockMethod.PATTERN -> "Connect at least ${PatternPolicy.MIN_DOTS} dots on a 3×3 grid."
}

/**
 * Lock settings for the ACTIVE AK workspace only. Each workspace keeps its own method. This protects AK's
 * own workspace; it is not Android's device lock and does not protect other apps, Android Settings or the phone.
 */
@Composable
internal fun LockScreenSettingsContent(viewModel: SettingsViewModel, profileName: String) {
    val state by viewModel.lockState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var flow by remember { mutableStateOf(LockFlow.None) }
    var chosen by remember { mutableStateOf(LockMethod.PIN) }
    var held by remember { mutableStateOf<CharArray?>(null) } // current secret between "verify" and "save new"
    var note by remember { mutableStateOf<String?>(null) }
    val availability = remember(state.fingerprint, state.lockEnabled) { BiometricUnlock.availability(context) }

    fun dropHeld() { held?.fill('\u0000'); held = null }
    fun cancel() { dropHeld(); flow = LockFlow.None }
    // Leaving Settings mid-flow must not leave the current secret in memory.
    DisposableEffect(Unit) { onDispose { held?.fill('\u0000') } }
    // Switching workspace while a flow is open must never carry it over to the other workspace.
    DisposableEffect(state.profileId) { onDispose { dropHeld(); flow = LockFlow.None } }

    Text(
        "Applies only to “$profileName”. AK’s lock protects this launcher workspace; it does not secure other apps, " +
            "Android Settings or the phone, and it does not replace or change your phone’s own screen lock.",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        when {
            !state.lockEnabled -> "Workspace lock: off"
            state.fingerprint -> "Workspace lock: ${state.method.label()} + fingerprint"
            else -> "Workspace lock: ${state.method.label()}"
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall,
    )
    var returnToFirst by remember { mutableStateOf(viewModel.lockManager.returnToFirstOnScreenOff) }
    SwitchRow(
        label = "Return to User 1 when screen turns off",
        description = "Applies to all workspaces. User 1’s own lock (if any) then appears.",
        checked = returnToFirst,
        onCheckedChange = { returnToFirst = it; viewModel.lockManager.returnToFirstOnScreenOff = it },
    )
    if (!state.lockEnabled) {
        ExpressiveActionRow("Turn on workspace lock", "Choose a PIN, password or pattern for this workspace.", onClick = { flow = LockFlow.ChooseForSetup })
    } else {
        ExpressiveActionRow("Change lock", "Verify your current ${state.method.label().lowercase()}, then pick a PIN, password or pattern.", onClick = { flow = LockFlow.VerifyForChange })
        ExpressiveActionRow("Remove lock", "Needs your current ${state.method.label().lowercase()}.", onClick = { flow = LockFlow.VerifyForRemove })
        SwitchRow(
            label = "Fingerprint unlock",
            description = when (availability) {
                BiometricUnlock.Availability.Available -> "Uses Android’s fingerprint check. Your ${state.method.label().lowercase()} always still works."
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
    note?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }

    when (flow) {
        LockFlow.None -> Unit
        LockFlow.ChooseForSetup -> ChooseMethodDialog("Choose a lock", current = null, onDismiss = ::cancel) { chosen = it; flow = LockFlow.Setup }
        LockFlow.Setup -> SecretDialog("Set ${chosen.label().lowercase()}", chosen, create = true, onCancel = ::cancel, onDone = { flow = LockFlow.None }) { secret ->
            if (viewModel.setLockCredential(chosen, secret)) null else "Could not save the ${chosen.label().lowercase()}. Nothing was changed."
        }
        LockFlow.VerifyForChange -> SecretDialog("Enter current ${state.method.label().lowercase()}", state.method, create = false, onCancel = ::cancel, onDone = { flow = LockFlow.ChooseForChange }) { secret ->
            val check = viewModel.verifyCurrentLock(secret)
            if (check == PinCheck.Success) { dropHeld(); held = secret.copyOf(); null } else lockMessage(check, state.method)
        }
        LockFlow.ChooseForChange -> ChooseMethodDialog("Choose the new lock", current = state.method, onDismiss = ::cancel) { chosen = it; flow = LockFlow.CreateForChange }
        LockFlow.CreateForChange -> SecretDialog("New ${chosen.label().lowercase()}", chosen, create = true, onCancel = ::cancel, onDone = { dropHeld(); flow = LockFlow.None }) { secret ->
            val current = held ?: return@SecretDialog "Session expired. Start again."
            // The old lock is replaced only inside this one call, after it re-verifies the current secret.
            val check = viewModel.changeLockCredential(current, chosen, secret)
            if (check == PinCheck.Success) null else if (check == PinCheck.Unavailable) "Could not save the new lock. Your previous lock is unchanged." else lockMessage(check, state.method)
        }
        LockFlow.VerifyForRemove -> SecretDialog("Remove lock", state.method, create = false, onCancel = ::cancel, onDone = { flow = LockFlow.None }) { secret ->
            lockMessage(viewModel.disableLock(secret), state.method)
        }
    }
}

fun lockMessage(check: PinCheck, method: LockMethod): String? {
    val noun = method.label().lowercase()
    return when (check) {
        PinCheck.Success -> null
        is PinCheck.Wrong -> if (check.lockoutMs > 0) "Wrong $noun. Try again in ${check.lockoutMs / 1000}s." else "Wrong $noun. ${check.attemptsLeft} tries left before a delay."
        is PinCheck.LockedOut -> "Too many attempts. Try again in ${(check.remainingMs + 999) / 1000}s."
        PinCheck.NotConfigured -> "No lock is set."
        PinCheck.Unavailable -> "The check is unavailable. Try again."
    }
}

/** Kept for existing callers that only know about PINs. */
fun pinMessage(check: PinCheck): String? = lockMessage(check, LockMethod.PIN)

@Composable
private fun ChooseMethodDialog(title: String, current: LockMethod?, onDismiss: () -> Unit, onPick: (LockMethod) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LockMethod.entries.forEach { m ->
                    TextButton(onClick = { onPick(m) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if (m == current) "${m.label()} (current)" else m.label(), style = MaterialTheme.typography.titleSmall)
                            Text(m.blurb(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "Fingerprint can be added on top and never replaces this.",
                    modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks for (create = false) or sets (create = true, with confirmation) one PIN, password or pattern.
 * [onSubmit] returns an error message, or null on success (then [onDone] runs). [onCancel] leaves everything
 * as it was. Secrets are never logged and are zeroed as soon as [onSubmit] returns.
 */
@Composable
fun SecretDialog(
    title: String,
    method: LockMethod,
    create: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onSubmit: suspend (CharArray) -> String?,
) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var showPassword by remember { mutableStateOf(false) } // hidden by default
    var error by remember { mutableStateOf<String?>(null) }
    var patternError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun submit(secret: CharArray) {
        busy = true
        scope.launch {
            val result = onSubmit(secret)
            secret.fill('\u0000')
            busy = false
            if (result == null) onDone() else { error = result; patternError = true; first = ""; confirm = ""; firstPattern = null }
        }
    }

    fun onPattern(dots: List<Int>) {
        if (busy) return
        if (!PatternPolicy.isValidDots(dots)) { error = "Connect at least ${PatternPolicy.MIN_DOTS} dots."; patternError = true; return }
        val chars = PatternPolicy.encode(dots)
        when {
            !create -> submit(chars)
            firstPattern == null -> { firstPattern = dots; error = null; chars.fill('\u0000') }
            firstPattern == dots -> submit(chars)
            else -> { error = "The patterns don’t match. Draw the first one again."; patternError = true; firstPattern = null; chars.fill('\u0000') }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (method) {
                    LockMethod.PATTERN -> {
                        Text(
                            when {
                                !create -> "Draw your pattern."
                                firstPattern == null -> "Draw a new pattern (at least ${PatternPolicy.MIN_DOTS} dots)."
                                else -> "Draw it again to confirm."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        PatternPad(
                            modifier = Modifier.fillMaxWidth().widthIn(max = 260.dp).align(Alignment.CenterHorizontally),
                            enabled = !busy,
                            isError = patternError,
                            showTraceAfterLift = create,
                            dotColor = MaterialTheme.colorScheme.primary,
                            errorColor = MaterialTheme.colorScheme.error,
                            onPatternStart = { patternError = false; error = null },
                            onPatternComplete = ::onPattern,
                        )
                    }
                    else -> {
                        val max = if (method == LockMethod.PIN) PinPolicy.MAX_LENGTH else PasswordPolicy.MAX_LENGTH
                        SecretField(first, if (create) "New ${method.label().lowercase()}" else method.label(), method, showPassword, max) { first = it; error = null }
                        if (create) SecretField(confirm, "Confirm ${method.label().lowercase()}", method, showPassword, max) { confirm = it; error = null }
                        if (method == LockMethod.PASSWORD) {
                            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide password" else "Show password") }
                        }
                        if (create) Text(method.blurb(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (method != LockMethod.PATTERN) {
                TextButton(enabled = !busy, onClick = {
                    error = when {
                        first.isEmpty() -> "Enter your ${method.label().lowercase()}."
                        create && !LockPolicy.isValid(method, first) -> when (method) {
                            LockMethod.PIN -> "Use ${PinPolicy.MIN_LENGTH}–${PinPolicy.MAX_LENGTH} digits."
                            else -> "Use at least ${PasswordPolicy.MIN_LENGTH} characters."
                        }
                        create && first != confirm -> "The ${method.label().lowercase()}s don’t match."
                        else -> null
                    }
                    if (error == null) submit(first.toCharArray())
                }) { Text("OK") }
            }
        },
        dismissButton = {
            Row {
                if (method == LockMethod.PATTERN && create && firstPattern != null) {
                    TextButton(enabled = !busy, onClick = { firstPattern = null; error = null; patternError = false }) { Text("Clear") }
                }
                TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/** Verification-only dialog for call sites that just need "prove you own this workspace" (delete flows). */
@Composable
fun VerifyLockDialog(
    title: String,
    method: LockMethod,
    onDismiss: () -> Unit,
    onSubmit: suspend (CharArray) -> String?,
) = SecretDialog(title, method, create = false, onCancel = onDismiss, onDone = onDismiss, onSubmit = onSubmit)

@Composable
private fun SecretField(
    value: String, label: String, method: LockMethod, visible: Boolean, max: Int, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (method == LockMethod.PIN) it.filter(Char::isDigit).take(max) else it.take(max)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (method == LockMethod.PIN) KeyboardType.NumberPassword else KeyboardType.Password),
        visualTransformation = if (method == LockMethod.PASSWORD && visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}
