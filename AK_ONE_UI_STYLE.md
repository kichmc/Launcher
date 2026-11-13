# One UI style behaviour (this pass)

Only the *One UI part* changes when a profile's launcher style is "One UI"; every other style keeps its
current behaviour. The switch is `LauncherStyleSpec.of(settings.launcherStyle)` (core/model). The style is
already part of each profile's settings snapshot, so each profile keeps its own.

One UI turns on:
- **Page editor** (`OneUiPageEditor.kt`): long-press empty home space (or tap the "=" glyph) shrinks the
  pages into swipeable cards drawn from the live layout. House (top left) = set default page, bin (top
  right) = remove the centred page if empty, "+" after the dots = add page, bottom row = Wallpaper and
  style / Themes / Widgets / Settings. Tap outside, Back or HOME leaves it.
- **Page dots** (`OneUiPageDots.kt`): "=" glyph, house on the default page, round dots.
- **Drawer**: floating pill search with search icon + mic, round page dots, top-right three-dot menu.
- **Preset** (picking One UI in Settings): 4 columns, 6 drawer rows, no dock labels, dark see-through drawer.

Not verified: no Gradle build or device test was run (no network/SDK here). Themes opens launcher Settings
and "Wallpaper and style" opens the system wallpaper chooser; a dedicated themes screen does not exist yet.
