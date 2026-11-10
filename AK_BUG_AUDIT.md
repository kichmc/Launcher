# AK Launcher — detailed bug audit

Audit baseline: `AK_Launcher_Profile_Wallpaper_Milestone.zip` (10 Nov 2026)

## Changes made in this pass

### 1. Profile switching race protection
- Serialized profile create/switch/delete operations with a `Mutex`.
- Re-read the active profile inside the serialized operation instead of capturing it before launching a coroutine.
- Read the target profile snapshot after saving the current profile, preventing rapid taps from restoring stale snapshots.
- Validate that a requested target profile still exists before switching.
- Clamp restored grid dimensions to safe supported ranges.

### 2. Active-profile deletion snapshot corruption
- Fixed the active-profile deletion sequence. Previously it wrote `active=user1` before switching, which made the switch routine treat User 1 as the old profile and could overwrite User 1's saved snapshot with the deleted profile's settings.
- The corrected sequence restores User 1 while the deleted profile is still recorded as active, then removes the deleted profile's snapshot/list entry.

### 3. Wallpaper from previous profile can remain visible
- `produceState` retains its previous value when a key changes. The launcher wallpaper layer did not clear that value before loading the next profile's wallpaper.
- It now clears the old image before loading. A profile without a wallpaper will no longer continue displaying the previous profile's wallpaper after a switch.

### 4. Wallpaper file-write result handling
- JPEG compression returning `false` is now treated as a failed save rather than a successful save. This prevents a corrupt/incomplete wallpaper file from being treated as a valid selection.

## Findings from the wider audit

### Profile/workspace isolation — incomplete (important)
The current implementation is still a settings preset system, not a fully independent workspace system.

- Only seven settings are included in profile snapshots: launcher style, home columns, drawer columns, drawer rows, theme mode, accent colour, and drawer search.
- Room `home_items` is shared, so home pages, app positions, folders, dock arrangement, shortcuts, and widget placements are shared.
- Hidden-app preferences are shared.
- Drawer folders and drawer wallpaper storage are shared.
- Bound Android widget IDs belong to the device/launcher host and are not yet safely partitioned per AK profile.
- The Android system wallpaper is device-wide; it cannot be independently switched per simulated AK profile without changing the actual phone wallpaper.
- Profile rename is not implemented.
- Deleting a profile removes its settings snapshot but does not yet clean all profile-specific wallpaper files/preferences.

These are not represented as fixed in this update. They need a deliberate database migration and per-profile ownership model before enabling full independent workspaces.

### Quick Settings tile
- The service is declared with `BIND_QUICK_SETTINGS_TILE` and the correct QS_TILE intent action.
- It launches the dedicated workspace picker and uses the PendingIntent overload on Android 14+.
- The tile is always marked `STATE_INACTIVE`; that is acceptable for a launch-action tile, but it does not communicate the active workspace.
- Actual tile pinning and launch behaviour still require physical-device testing, especially on Samsung One UI.

### Launcher leaving/returning from another app
- `LauncherActivity` uses `singleTask`, handles HOME through `onNewIntent`, and has an explicit BACK handler so BACK on an empty home screen does not finish the launcher.
- Widget-host start/stop calls are guarded against exceptions.
- Static inspection did not reveal a definite `finish()`/`finishAffinity()` path in the launcher activity. The reported close/relaunch issue cannot be confirmed or cleared without reproducing it on the target phone and capturing Logcat.

### App placement and hidden apps
- `HomeLayoutRepository.addToHome` checks for an existing app row and performs the check/insert in a Room transaction, protecting against duplicate additions.
- The Add All Visible Apps action filters keys supplied by the hidden-app store.
- Automatic placement of newly installed apps is not verified as implemented in the current source; it remains a separate feature requirement.
- Existing package-event and home-layout repair tests cover event bursts, stale app rows, renamed launcher activities, duplicates, folder repair, and temporary package-query uncertainty.

### Settings search
- Current search matches section titles and displays matching text.
- It does not navigate/scroll to a selected setting section, and it does not index individual controls. This is an incomplete feature, not a fixed bug.

## Verification status

- Source-level inspection completed for profile management, wallpaper storage/layer, launcher activity lifecycle, Quick Settings service/manifest, home-layout repository, app package events, hidden-app filtering, and settings search.
- `git diff --check` is unavailable because the supplied archive is not a Git checkout.
- Gradle tests/build could not run: the wrapper attempted to download Gradle 9.8.2, but this environment has no DNS/network access (`UnknownHostException: services.gradle.org`).
- No APK was produced and no physical-device test was performed. Do not treat this ZIP as a verified release build.

## Recommended next engineering milestone

Implement profile-aware Room storage (including migration from the existing shared layout), profile-aware hidden-app/drawer-folder settings, and safe widget ownership/rebinding. Add migration and profile-switch tests before claiming full workspace independence.
