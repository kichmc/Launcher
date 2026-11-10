# AK multi-profile implementation notes

- Supports up to 5 AK launcher profiles (the Samsung One UI/system home entry is separate and does not count toward the limit).
- Each AK profile is persisted as an individual JSON `.akprofile` file under the app-private `files/profiles/` directory.
- Profile files currently persist profile identity, wallpaper URI slot, hidden-app package list, auto-placement preference, optional in-app profile-lock flag, theme, grid dimensions, icon size, and placement mode.
- The User Manager currently exposes profile creation/deletion and a small set of per-profile controls. The profile data layer is ready for further per-user settings migration.
- The `.akprofile` files are private to the app installation in this build; import/export UI is not included yet.
- Important: profiles are launcher configurations only. They do not isolate Android app data, accounts, notifications, or widget host state. The custom profile-lock flag is stored as a preference; a full profile-lock flow is not implemented yet.
- Existing launcher-wide settings and workspace data have not all been migrated to profile-scoped storage. Do not assume every existing AK setting is independent yet.
- No Gradle build or tests were run, per request. Build and test on-device.
