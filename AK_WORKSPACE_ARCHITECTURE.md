# AK Launcher virtual workspace architecture

## What is isolated per AK workspace

- The full `SettingsRepository` DataStore preference set is snapshotted and restored per profile. This covers theme/style, grid sizes, icon and label options, gestures, dock options, app drawer options, hidden apps, drawer folders, icon-pack choices, custom labels, custom icon mappings and frequent-app usage ranking.
- Room `home_items` rows are physically partitioned by `profileId`. Every read, insert, update, move and delete is scoped to the active AK workspace. The unique cell index includes `profileId`, so two workspaces can safely use the same grid coordinates.
- Each Room partition contains its own home pages, app positions, folders and their members, dock entries/folders, pinned shortcuts, built-in widgets and app-widget rows.
- The persisted `workspace_state` row selects the active Room partition. Switching profiles changes this selector; it no longer clears and repopulates one shared layout table on every switch.
- The v8→v9 Room migration assigns the existing shared layout to User 1. Other profiles are imported once from their saved snapshots when first opened after upgrade.
- Launcher wallpaper and selected wallpaper copies are keyed by AK profile ID.
- Drawer wallpaper files are stored in a profile-specific directory. User 1 continues using the legacy directory so existing data is retained.
- Motivation widget preferences, custom quotes and favourites use a profile-specific preference file. User 1 keeps the legacy preference file.
- Custom icon image files referenced by inactive profile snapshots are protected from unused-image cleanup and replacement cleanup.
- Profile rename, delete cleanup and the five-profile limit remain available.

## Switching sequence

1. Serialize profile mutations across Settings-screen and Quick Settings ViewModel instances with one process-wide mutex.
2. Save the outgoing profile's settings snapshot.
3. Change the active Room partition selector.
4. Import a legacy layout snapshot only the first time a profile is opened after the v9 migration; subsequent switches retain the profile's live Room rows directly.
5. Restore the target profile's settings and publish the active profile ID only after the target is ready.
6. Reload drawer/home wallpaper and refresh the Motivation widget.
7. If restoration fails, select the previous Room partition and restore the previous settings/layout.

Settings snapshots remain in `ak_workspace_profiles` SharedPreferences. Layout rows are no longer dependent on repeatedly serializing the entire Room table; each workspace's rows remain in place. Folder-child `containerId` links and row IDs therefore remain stable across switches.

## Widget handling

A new workspace no longer copies the creating workspace's layout (see "Default layout for new workspaces"). Widget rows are never duplicated between workspaces, and none is added automatically except the built-in clock/date widget. Android AppWidget IDs identify actual device-owned widget instances; cloning the same ID into two virtual workspaces would make them share one widget instance and settings. Add widgets separately in each workspace. Existing workspaces retain their own widget rows. The AppWidgetHost cleanup sweep checks widget IDs across **all** AK workspaces, so switching to a workspace without a given widget does not delete an inactive workspace's widget ID.

## Default layout for new workspaces

`SettingsViewModel.createAkProfile` builds the new workspace's home with `DefaultWorkspaceLayout.plan` (core/data, pure and deterministic) and stores it with `HomeLayoutRepository.initializeDefaultLayout` inside the existing switch journal:

- Page 0: the built-in clock/date (smartspace) widget, full width, 2 rows tall (shortened on grids with fewer rows, dropped on a 1-row grid).
- Dock: the device's default phone, messages, Settings, browser and camera apps (`resolveDefaultDockKeys`), cut to the dock's column count. Those apps are not repeated on the pages.
- Pages: every other visible (non-hidden) app, alphabetical by label then key, row by row into the cells the widget leaves free, continuing on new pages (never beyond `MAX_PAGES`). Uses the live grid size; no pixel values.
- No folders and no app widgets (Weather, Search…) are added: those need a binding or permission the user has not given.
- `initializeDefaultLayout` refuses when the new partition already has rows, and runs once inside one Room transaction. It is called only from workspace creation, so launching, switching, rotating or restarting never re-applies it.
- Failure rolls back through the existing journal/catch path (partition deleted, previous workspace restored).

Pre-v9 workspaces opened for the first time after upgrade still import their saved snapshot as before; that migration is unchanged.

## Platform boundary

AK workspaces are launcher-level virtual profiles, not Android users. Installed apps, app data, Android permissions, notification access, and the phone's actual system/lock-screen wallpaper remain device-wide. Applying a wallpaper through Android's WallpaperManager changes the device wallpaper; the launcher's own home wallpaper and drawer wallpaper are profile-specific.

## Build and verification

A Room regression test covers same-cell app placement and separate widget IDs across two workspace partitions. Gradle compilation and Android/device tests must still be run by CI or locally. The current editing environment cannot download the project's Gradle 9.8.2 distribution, so no successful build is claimed.
