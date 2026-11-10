# AK workspace/profile update

This source includes the initial AK workspace selector and profile-scoped launcher wallpaper storage.

## Implemented in this update
- Each AK workspace has its own launcher wallpaper image file and remembered wallpaper selection/target.
- Switching the active AK workspace changes which launcher wallpaper `LauncherWallpaperLayer` loads on resume.
- The existing wallpaper picker now reads/writes the active workspace's wallpaper because it uses `WallpaperStore`.
- Wallpaper files are keyed by the active AK workspace ID (validated before use).

## Not yet implemented
- Independent home-screen pages, icon positions, folders and dock layout. The current Room `home_items` table is still shared.
- Independent widget placement/bindings. Widget IDs are still stored in the shared home layout and need careful profile-aware ownership handling.
- Independent hidden-app lists. Hidden-app preferences are still shared.
- A complete workspace data migration/backup/restore flow.

Do not describe these pending items as implemented. They require profile-aware persistence in the Room schema/repositories and migration tests; simply changing the visible profile name would not isolate them.

## Build verification
- Source changes were inspected and whitespace checks performed.
- Gradle compilation and device testing have not been performed in this environment. Build and test on-device before release.
