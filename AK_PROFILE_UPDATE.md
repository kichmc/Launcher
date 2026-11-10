# AK workspace/profile update

This update adds an initial in-launcher AK workspace selector under Settings > AK Workspaces.

- Up to five workspace profiles can be created.
- Profiles can be switched and deleted (User 1 is protected).
- Each profile stores its launcher style, home grid columns, app-drawer grid, theme mode, accent color, and drawer-search preference.
- The app drawer page indicator now uses One UI-inspired pill/dot pagination instead of a numeric counter.

## Current limitation
This is the first profile foundation, not full isolated workspaces. Home-screen icon positions, folders, widgets, wallpaper, hidden-app list, and app ordering are still shared between profiles. A Quick Settings tile and home-screen floating/profile shortcut are not included yet. Do not describe these as implemented.

## Build verification
The Gradle compile could not run in this environment because the Gradle distribution could not be downloaded (network DNS unavailable). Please build with the project's normal Gradle setup and test profile switching on-device before release.
