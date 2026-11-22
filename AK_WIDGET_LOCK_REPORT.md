# AK Launcher — widget completion + multi-method workspace lock (report)

## Verification status — read first
Static work only. No Android SDK / Gradle distribution was reachable here, so **nothing was compiled, unit-tested or run on a device**.
All edited Kotlin files parse cleanly with tree-sitter (syntax only, not a compile). Build with `./gradlew :app:assembleDebug`, then run
`./gradlew test` and `:core:data:connectedDebugAndroidTest` before trusting any "fixed" below.

## Inspection findings (before editing)
- Widgets were already workspace-partitioned: Room rows carry `profileId`; ids are unique host ids; the host sweep keeps ids of ALL workspaces;
  restored widgets come back as tap-to-set-up placeholders; move/resize go through `setWidgetBounds`. These were NOT rewritten.
- Lock already had PBKDF2 + Keystore HMAC hashing, per-profile files, attempt throttling, one-unlocked-profile invariant, screen-off lock, BiometricPrompt (STRONG, Keystore-bound). Only PIN existed.
- Real bugs found: (1) deleting a workspace removed its rows but left its AppWidgetHost ids allocated until the next launcher start; (2) uninstalling an app removed its widget rows only from the ACTIVE workspace, leaving dead placeholders in the others; (3) delete-workspace prompts only accepted digits, so a password/pattern workspace could never be deleted once those methods exist; (4) the restore message said "Backup restored" with no mention that widgets need setting up.

## Changed
**Lock (core:data)**
- `LockMethod` (PIN / PASSWORD / PATTERN), `PasswordPolicy` (6–64 chars), `PatternPolicy` (4–9 distinct dots on 3×3), `LockPolicy`.
- `ProfileLockConfig.method` (stored as `"method"`; files without it are PINs, so existing PINs keep working with no migration).
- `ProfileLockStore`: `setCredential`, `changeCredential` (verifies the current secret, then replaces in ONE atomic file write; any failure leaves the old lock), `verify` (method-agnostic; `verifyPin`/`setPin`/`changePin` kept as wrappers). Same PBKDF2 + Keystore pepper; passwords/patterns are domain-prefixed so a pattern string can't satisfy a password. Attempt counter/lockout shared by all methods. Fingerprint verifier is kept when the method changes.
- `ProfileLockManager.unlockWith`, `LockUiState.method`.

**Lock UI**
- `core:ui/lock/PatternPad.kt`: touch pattern grid, highlighted dots, live line, skipped-dot pickup, error colour; trace is cleared the instant the finger lifts (verification) — never stored.
- `LockScreen`: PIN keypad (unchanged), password field (hidden by default, Show/Hide), pattern pad; "Use fingerprint" button for non-PIN methods; wrong-credential messages name the method; too-short patterns are rejected locally without costing an attempt.
- Settings → Lock: shows the active method (+ fingerprint), Turn on / Change lock / Remove lock, method picker. Change flow = verify current → choose method → enter + confirm new → saved only at the end; Cancel at any step leaves the old lock. Held secret is zeroed on cancel, on leaving Settings and on workspace switch. Password setup/confirm with Show/Hide; pattern setup draws twice to confirm.
- Delete-workspace dialogs (Settings and QS switcher) now ask for the workspace's own method (`VerifyLockDialog`).

**Widgets**
- `deleteWorkspace` now returns the host ids it removed (read in the same transaction); `SettingsViewModel` frees exactly those in the AppWidgetHost.
- Uninstall cleanup also deletes widget rows/placeholders of that provider in every other workspace.
- `WidgetHostIds.APPWIDGET_HOST_ID` moved to core:data (value unchanged; `feature:home` constant aliases it).
- Restore: `RestoreResult.widgetsNeedSetup`; the message now says N widget(s) need setting up again (English; other locales fall back). Widgets are never reported as restored.

**Tests added**: `ProfileLockStoreTest` (password/pattern verify, per-workspace methods, nothing plaintext on disk, change-method failure keeps old lock, legacy file = PIN, policies, fingerprint kept) and two `HomeLayoutRepositoryTest` cases (delete returns only own ids; uninstall clears all workspaces).

## Not changed / not done (be aware)
- Widget editing UX (add/drag/resize/preview) was inspected, not redesigned: I could not run it, and the code paths exist. Needs real-device checking.
- Backup/restore still covers the ACTIVE workspace only; no per-workspace backup, no workspace identity in the file.
- Settings lock screen manages the ACTIVE workspace (each workspace has its own method; switch to it to change it).
- No "forgot credential" recovery (clear app data is the only reset). Patterns are hard with TalkBack — PIN/password remain available.
- Fingerprint code was already present and was not altered; its fallback is now the workspace's own method.
- Built-in widget cloning into new workspaces is still your open decision (see AK_IMPLEMENTATION_REPORT §5).
- AK locks protect only the AK workspace UI, not other apps, Android Settings or the phone.

## Needs real-device testing
Add widget in WS1 → switch to WS2 (absent) → add another → back; move/resize; force-stop + reboot; rename; delete workspace (check `adb shell dumpsys appwidget` ids freed); uninstall a widget app with widgets in two workspaces; restore backup (placeholders + message); unavailable provider; PIN/password/pattern set/verify/change/cancel/remove; wrong-credential lockout; switching between locked workspaces; screen-off lock; process restart; fingerprint + fallback; small screens, keyboard over password field.
