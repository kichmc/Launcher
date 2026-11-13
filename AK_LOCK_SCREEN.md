# AK profile lock screen — Step 1 (core system)

Status: written, NOT compiled, NOT run. No Android SDK/network was available when this was written.

## Architecture
- `core/data/.../lock/PinHasher.kt` — PBKDF2-HMAC-SHA256 (200k iters, 16-byte random salt per credential), then HMAC with a
  non-exportable Android Keystore key. Constant-time compare. Scheme stored with each credential.
- `ProfileLockStore.kt` — one private file per profile id (`files/profile_lock/<id>.json`): enabled flag, credential,
  biometric verifier, failed-attempt counter, lockout. Attempt is charged BEFORE verification. Unreadable file fails closed.
- `ProfileLockManager.kt` — unlock state. At most one profile (the active one) is ever unlocked. The unlock marker is stored
  in the same prefs file as the active-profile id and cleared in the same `commit()` that changes it, so switching away locks
  the old profile atomically. Marker is valid for one boot only. Screen-off clears it.
- `BiometricUnlock.kt` — framework BiometricPrompt (BIOMETRIC_STRONG) with a Keystore AES-GCM key that needs a fresh biometric
  per use and is invalidated when fingerprints change. No biometric data is read or stored.
- App: `LockScreen.kt` (clock, date, PIN pad, fingerprint, shake/fade), `LauncherActivity` (shows LockScreen *instead of*
  the home shell while locked; FLAG_SECURE while locked), `LockGuard.kt` (Settings/Wallpaper/Motivation/PinRequest/BackupImport
  refuse to show a locked profile), `LauncherApplication` (screen-off receiver, no service).
- Settings: "Lock screen" section (set/change/disable PIN, fingerprint). Deleting/removing a profile that has a lock needs its PIN.

## Separation from Android security
Nothing here calls KeyguardManager, createConfirmDeviceCredentialIntent, ACTION_BIOMETRIC_ENROLL or any device-credential API.
The PIN pepper key needs no device lock. Fingerprint is offered only if Android already has an enrolled strong biometric.

## Known limitations
- No "forgot PIN" recovery (clear app data is the only reset).
- Typed PIN digits are held in a Kotlin String while typing (cannot be zeroed).
- Android decides which STRONG biometric appears; some phones may offer face as well as fingerprint.
- Profiles are launcher configurations; this does not protect other apps or the Android system.
- Legacy `users/AkUsers` code (not in the manifest) is untouched and unused by this feature.
