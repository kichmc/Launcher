# AK Launcher — audit, fixes and feasibility report (14 Nov 2026)

## How this was checked — read this first
- Method: static source inspection only. The sandbox had no network, no Android SDK and no Gradle
  distribution (services.gradle.org / dl.google.com / Maven were unreachable), so **nothing here was compiled,
  unit-tested, linted or run on a device**. The edited Kotlin files were parsed with tree-sitter to catch
  syntax errors (all clean); that is a syntax check, not a compile.
- Therefore every "fixed" below means "the defect was found in the source and the code was changed to remove it",
  not "verified on a phone". Build with `./gradlew :app:assembleDebug` (or the GitHub Actions workflow) and run the
  test plan at the end before trusting it.
- The earlier AK_*.md notes contradict each other (AK_BUG_AUDIT says Room is shared; AK_WORKSPACE_ARCHITECTURE says
  it is partitioned). The code was read directly: Room **is** partitioned by `profileId` (v9).

## 1. Implementation report
**Implemented (by reading the code; not executed)**
- Room partitioning: `HomeItemEntity.profileId`, unique index (profileId, containerId, page, cellX, cellY), every DAO
  query/mutation scoped through the `workspace_state` selector, `insertActive` stamps the owner, delete-by-profile.
  v8→v9 migration matches the entity (column default, index name, new table) by inspection.
- Settings isolation: whole DataStore exported to a per-workspace snapshot on switch; `importRaw` clears all keys first,
  so nothing leaks from the previous workspace. Hidden apps, drawer folders, icons, labels are inside that snapshot.
- Widget host sweep considers widget ids of **all** workspaces (inactive workspaces keep their ids).
- Launcher wallpaper, drawer wallpaper and Motivation prefs are keyed by workspace id. Five-workspace cap. Delete cleanup.
- Lock design (PBKDF2 + Keystore HMAC, per-profile file, attempt charged before verification, one unlocked profile at most,
  screen-off lock, marker valid for one boot, BiometricPrompt STRONG). Only `ProfileLockManager` was read line by line;
  `PinHasher`/`ProfileLockStore`/`BiometricUnlock` were not re-audited in depth.

**Partially implemented**
- Widgets: a new workspace starts with no widgets. Built-in launcher widgets (smartspace/battery/…) are also dropped on
  clone although they have no AppWidget id and could safely be copied (filter in `createAkProfile`, init and
  `activateProfileLocked`). Not changed — behaviour decision for you.
- Backup/restore: works on the **active** workspace only (`getAllOnce()` + `exportRaw()`); widgets come back as
  placeholders (ids are device-local); wallpapers are not included; the file carries no workspace identity; PIN/lock data is
  correctly excluded. "Back up/restore a chosen workspace" is not implemented.
- QS tile + switcher: correct manifest, `PendingIntent` path on Android 14+. The tile cannot show the active workspace.
- Settings search only matches section titles.

**Broken (found in this pass, now changed — see §2)** and **Missing**: independent system wallpaper (Android-wide), app cloning,
virtual Android (see §3), a "forgot PIN" path (documented limitation).

**Architecture problems still open**
- Two sources of truth for the active workspace (Room `workspace_state` and SharedPreferences `active`); now reconciled at
  launcher start, but the design stays fragile. Long-term: one owner.
- Workspace snapshots (settings + layout JSON ×5) live in one SharedPreferences file that is read on the main thread at start.
- Profile logic sits in `SettingsViewModel` (feature:settings), used by three activities. A `WorkspaceManager` singleton would be cleaner.
- `app/.../users/` (AkUserSwitcher, UserManagerActivity, AkUserTileService) is unreferenced and not in the manifest — dead code, left in place.
- Room schema `9.json` is missing from `core/data/schemas` (only 5–8). The build regenerates it; commit it, and the migration test needs it.

## 2. Bugs fixed (SettingsViewModel, AkProfileSwitcherActivity, LauncherActivity)
1. **Motivation widget never refreshed on workspace switch.** The `PROFILE_CHANGED` broadcast was package-only, but the receiver has
   no intent-filter for it, so it was never delivered (stale quote/style until the next 30-min update). Now an explicit component broadcast.
2. **Stale workspace list across ViewModel instances.** Settings, the launcher and the QS switcher each hold their own copy; create/rename/delete
   wrote a stale list back (could drop workspaces; switch silently refused for a workspace made elsewhere). Each operation re-reads the persisted list inside the mutex.
3. **Switch/create/delete were cancellable.** Leaving Settings or the switcher mid-switch cancelled the coroutine between the Room selector, the settings import and the active-id commit
   (and the rollback `runCatching` swallowed the cancellation). The three operations now run under `NonCancellable`.
4. **Half-finished switch after process death.** Added a switch journal (written after the outgoing snapshot is saved, cleared in the same atomic commit that publishes the new active id).
   At startup an interrupted switch is repaired: Room selector and live settings go back to the persisted active workspace, an orphaned half-created workspace is removed.
   Without this, the next switch could overwrite a workspace's good snapshot with another workspace's settings.
5. **Reconciliation only ran when Settings/switcher was opened.** `LauncherActivity` now triggers it at every start.
6. **Locked workspace could be cloned.** The Quick Settings switcher is not behind the lock, and "Add workspace" copies the active workspace's layout and settings into an unlocked one.
   Refused in the ViewModel and disabled in the switcher while locked.
7. **Failed creation leaked rows and could leave a ghost list entry.** Rollback now deletes the partial partition and removes the entry.
8. **Delete order** could leave a listed workspace whose data was already gone; the list entry is now removed first.
9. **Switcher stayed open after Switch.** It now closes once the switch has actually finished.

## 3. Feasibility (Phase 4) — nothing implemented here, by design
A launcher cannot copy another app's package or data. A duplicate shortcut is not a clone and was not built.
| Option | What it is | Verdict |
|---|---|---|
| Android work profile / clone profile | Real second user for the same device; needs a profile owner (DPC) app or OEM support (Samsung Secure Folder, "Dual Messenger") | A launcher can *display and launch* them via `LauncherApps`/`UserHandle` (AK already has `AndroidProfileSystem`/`WorkProfileRepository`); it cannot create them |
| Private space (Android 15+) | Separate profile, apps installed as fresh copies, no data copied; one per device, main user only; launcher needs `ACCESS_HIDDEN_PROFILES` and the HOME role (Android docs) | The only first-party, reliable "second copy". AK could show it as a workspace's app source once it is the default home app |
| Secondary users (multi-user) | Creating users needs system permission | Not possible for a normal app |
| In-app virtualization (VirtualApp-style, Parallel Space) | Hooks/proxies framework calls so a host app runs guest APKs | Technically possible, fragile: breaks on Android updates, many apps (banking, Play Integrity, push, accounts) fail or ban, heavy maintenance, security exposure. Not recommended as a core feature |
| Virtual Android VM (AVF/pKVM) | Hypervisor guest | Not available to third-party apps for a guest Android on consumer phones |
Recommendation: (1) keep AK workspaces as launcher-level environments and say so in the UI; (2) add "open in work/clone/private profile" support where the device already has one;
(3) revisit virtualization only as an explicit opt-in experiment with warnings.

## 4. Android limits to keep in the docs
- The system wallpaper is device-wide; AK wallpapers are launcher-only.
- Widgets are device-host instances; the same widget id must never appear in two workspaces; widget data lives in the provider.
- AK locks protect the AK UI only, not other apps, the notification shade or Android itself. FLAG_SECURE is used on the AK lock screen.
- Apps opened from AK run normally; Android's task rules decide what Back/Recents show. A launcher must not force itself to the foreground.

## 5. Decisions for you
- "Return to User 1 when the screen turns off" is **on by default** (`ProfileLockManager`, toggle in Lock screen settings). Your brief expects Workspace 2 to still be shown after screen off/on; the default conflicts with that.
- Motivation widget: default corner is 28dp (soft) / 44dp (round), not the 24dp in the brief; no change made.
- Clone built-in widgets into new workspaces? (recommended: yes)
- `ak-signing.p12` and `kich-signing.p12` were inside the ZIP you sent, although AK_SOURCE_FIX_REPORT says the keystore was removed. They are **not** in the ZIP delivered with this report.
  BUILD_ON_PHONE.md tells you to upload project files through GitHub's web page, which ignores .gitignore. Keep the keystores out of the repo, keep your own backup (a lost key means no updates), and consider rotating them if they were ever uploaded.

## 6. Test plan (not executed — run on a device/emulator)
1. Create User 2. 2. Set a different launcher wallpaper. 3. Add/move apps, create a folder, add a widget. 4. Hide two apps. 5. Set a PIN (and fingerprint) for User 2.
6. Switch to User 1 through the QS tile: layout, wallpaper, hidden apps, widgets and Motivation look must be unchanged. 7. Force-stop AK, reopen: both workspaces intact, User 2 asks for its PIN.
8. Interrupted switch: start a switch and kill the process (`adb shell am kill com.kich.launcher.debug`); on reopen the active workspace's layout, settings and wallpaper must match.
9. Lock User 2, open the QS switcher: "Add workspace" must be disabled. 10. Back / Home / Recents / app switching / screen off-on from an app opened in User 2 (with the screen-off return toggle off).
11. Delete User 2: its rows, wallpapers, PIN file and Motivation prefs are gone; User 1 untouched. 12. `./gradlew test` and `:core:data:connectedDebugAndroidTest` (HomeLayoutRepositoryTest covers two-workspace same-cell placement and separate widget ids).
