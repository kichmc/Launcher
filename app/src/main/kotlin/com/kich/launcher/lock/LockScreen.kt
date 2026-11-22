package com.kich.launcher.lock

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.kich.launcher.ui.lock.PatternPad
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kich.launcher.data.lock.BiometricUnlock
import com.kich.launcher.data.lock.LockMethod
import com.kich.launcher.data.lock.LockUiState
import com.kich.launcher.data.lock.PasswordPolicy
import com.kich.launcher.data.lock.PatternPolicy
import com.kich.launcher.data.lock.PinCheck
import com.kich.launcher.data.lock.PinPolicy
import com.kich.launcher.data.lock.ProfileLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AK's own profile lock screen. Shows NO notifications and no home content: the launcher shell is
 * not even composed while this is up. Typed digits live only in this composition (never saved
 * across recreation, never logged).
 */
@Composable
fun LockScreen(state: LockUiState, manager: ProfileLockManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember(state.profileId) { mutableStateOf("") }
    var password by remember(state.profileId) { mutableStateOf("") }
    var showPassword by remember(state.profileId) { mutableStateOf(false) } // hidden by default
    var patternError by remember(state.profileId) { mutableStateOf(false) }
    val noun = when (state.method) { LockMethod.PIN -> "PIN"; LockMethod.PASSWORD -> "password"; LockMethod.PATTERN -> "pattern" }
    var message by remember(state.profileId) { mutableStateOf<String?>(null) }
    var lockoutEndsAt by remember(state.profileId) { mutableLongStateOf(0L) }
    var checking by remember(state.profileId) { mutableStateOf(false) }
    var promptActive by remember { mutableStateOf(false) }
    var autoTried by remember(state.profileId) { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(350)) }

    // A lockout can already be running (process restart, returning to the launcher).
    LaunchedEffect(state.profileId) {
        val left = withContext(Dispatchers.Default) { manager.store.lockoutRemainingMs(state.profileId) }
        if (left > 0) lockoutEndsAt = System.currentTimeMillis() + left
    }
    val now by produceState(System.currentTimeMillis(), lockoutEndsAt) {
        while (true) { value = System.currentTimeMillis(); if (lockoutEndsAt <= value) break; delay(500) }
    }
    val remainingSec = ((lockoutEndsAt - now + 999) / 1000).coerceAtLeast(0)
    val lockedOut = remainingSec > 0

    fun startBiometric() {
        if (!state.fingerprint || promptActive) return
        val cfg = manager.store.config(state.profileId)
        val iv = cfg.bioIv; val blob = cfg.bioBlob
        if (iv == null || blob == null) return
        promptActive = true
        BiometricUnlock.authenticate(context, state.profileId, iv, blob, "Unlock", "Use $noun") { r ->
            promptActive = false
            when (r) {
                is BiometricUnlock.Result.Success -> manager.unlockWithBiometric(state.profileId)
                BiometricUnlock.Result.KeyInvalidated -> {
                    // Fingerprints changed or the device lock was removed: fall back to PIN only.
                    manager.store.clearBiometric(state.profileId); manager.refresh()
                    message = "Fingerprints changed. Use your $noun, then re-enable fingerprint in Settings."
                }
                else -> Unit
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!autoTried) { autoTried = true; startBiometric() }
    }

    fun submitSecret(chars: CharArray) {
        if (checking || lockedOut) { chars.fill('\u0000'); return }
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { manager.unlockWith(state.profileId, chars) }
            chars.fill('\u0000')
            checking = false
            when (result) {
                PinCheck.Success -> Unit // state flips; the host animates this screen away
                is PinCheck.Wrong -> {
                    pin = ""; password = ""; patternError = true
                    if (result.lockoutMs > 0) { lockoutEndsAt = System.currentTimeMillis() + result.lockoutMs; message = "Too many attempts" }
                    else message = "Wrong $noun · ${result.attemptsLeft} tries left"
                    for (x in listOf(-18f, 18f, -12f, 12f, 0f)) shake.animateTo(x, tween(40))
                }
                is PinCheck.LockedOut -> { pin = ""; password = ""; lockoutEndsAt = System.currentTimeMillis() + result.remainingMs }
                PinCheck.Unavailable -> message = "Check unavailable. Try again."
                PinCheck.NotConfigured -> message = "This workspace’s lock can’t be verified."
            }
        }
    }

    fun submitPin() { if (PinPolicy.isValid(pin)) submitSecret(pin.toCharArray()) }
    fun submitPassword() { if (password.isNotEmpty()) submitSecret(password.toCharArray()) }
    fun submitPattern(dots: List<Int>) {
        // A too-short or malformed drawing is rejected locally and does not cost an attempt.
        if (!PatternPolicy.isValidDots(dots)) { patternError = true; message = "Connect at least ${PatternPolicy.MIN_DOTS} dots"; return }
        submitSecret(PatternPolicy.encode(dots))
    }

    val time by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(60_000 - value % 60_000 + 50) }
    }
    val timeText = remember(time) { clockText(context, time) }
    val dateText = remember(time) { SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), Locale.getDefault()).format(Date(time)) }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp)
                .graphicsLayer { translationY = (1f - appear.value) * 40f; alpha = appear.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(timeText, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
            Text(dateText, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Column(Modifier.graphicsLayer { translationX = shake.value }, horizontalAlignment = Alignment.CenterHorizontally) {
                when (state.method) {
                    LockMethod.PIN -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val slots = maxOf(PinPolicy.MIN_LENGTH, pin.length)
                        repeat(slots) { i ->
                            Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) Color.White else Color.White.copy(alpha = 0.3f)))
                        }
                    }
                    LockMethod.PASSWORD -> Unit
                    LockMethod.PATTERN -> PatternPad(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 280.dp),
                        enabled = !lockedOut && !checking,
                        isError = patternError,
                        showTraceAfterLift = false, // the drawn pattern disappears as soon as the finger lifts
                        onPatternStart = { patternError = false; message = null },
                        onPatternComplete = ::submitPattern,
                    )
                }
            }
            Text(
                if (lockedOut) "Try again in ${remainingSec}s" else message ?: " ",
                color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )
            when (state.method) {
                LockMethod.PIN -> Keypad(
                    enabled = !lockedOut && !checking,
                    showFingerprint = state.fingerprint,
                    canSubmit = PinPolicy.isValid(pin),
                    onDigit = { if (pin.length < PinPolicy.MAX_LENGTH) { pin += it; message = null } },
                    onBackspace = { pin = pin.dropLast(1) },
                    onSubmit = ::submitPin,
                    onFingerprint = ::startBiometric,
                )
                LockMethod.PASSWORD -> Column(Modifier.widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(PasswordPolicy.MAX_LENGTH); message = null },
                        enabled = !lockedOut && !checking,
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedLabelColor = Color.White, unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                            cursorColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show", color = Color.White) }
                        TextButton(enabled = !lockedOut && !checking && password.isNotEmpty(), onClick = ::submitPassword) { Text("Unlock", color = Color.White) }
                    }
                }
                LockMethod.PATTERN -> Unit
            }
            if (state.fingerprint && state.method != LockMethod.PIN) {
                TextButton(onClick = ::startBiometric) { Text("Use fingerprint", color = Color.White) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean, showFingerprint: Boolean, canSubmit: Boolean,
    onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit, onFingerprint: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { c -> Key(c.toString(), enabled) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (showFingerprint) Key("☝", true, "Use fingerprint", onFingerprint) else Key("⌫", enabled, "Delete", onBackspace)
            Key("0", enabled) { onDigit('0') }
            Key("OK", enabled && canSubmit, "Unlock", onSubmit)
        }
        if (showFingerprint) Key("⌫", enabled, "Delete", onBackspace)
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, description: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.16f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { description?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = if (label.length > 1) 16.sp else 26.sp) }
}

private fun clockText(context: Context, millis: Long): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
