# AK Launcher

A lightweight Android home-screen launcher built with **Jetpack Compose**, with a customisable
**Motivation widget** that shows a rotating quote on your home screen.

AK Launcher is open source under the **Apache License 2.0**.

> **Source launcher:** AK Launcher is based on
> [**ARK-launcher**](https://github.com/jrs8205/ARK-launcher) by **jrs8205**
> (Apache-2.0). The launcher core (home screen, dock, app drawer, widget hosting, settings,
> backup) comes from that project. All credit for it goes to its author. See
> [Credits](#credits) below.

> **Made with Claude:** the Motivation widget styling, responsive layout, customisation settings,
> the permission clean-up and the launcher icon described below were written by
> [**Claude**](https://www.anthropic.com/claude), an AI assistant made by Anthropic, working with
> the project owner. Claude could not compile or run the code on a device, so those parts need
> testing. See [Status](#status).

## What is different from ARK-launcher

### Motivation widget

A home-screen widget (a real Android AppWidget, shown in the launcher's widget picker) with a
quote that rotates on an interval you choose. Tap the quote to open its settings; use the
buttons on the widget to favourite the quote or get a new one.

Customise it in the Motivation settings screen (the preview card updates live):

| Setting | Options |
|---|---|
| Theme | Midnight, Sunset, Forest, Ocean, Paper (light), Black, System (Material You, Android 12+), Custom (your own `#RRGGBB` background and accent) |
| Corners | Sharp, Soft, Round |
| Font | Serif, Sans, Mono |
| Text size | Auto (fits the space), Small, Medium, Large |
| Text colour | Auto (by background brightness), Light, Dark |
| Widget size | Slider, 70% to 150%: scales text, padding and buttons |
| Background opacity | Slider, 30% to 100% |
| Title label | On or off |

- **Responsive:** each widget is drawn for its own size. About one row tall (under 120 dp) uses a
  compact layout with the quote and two small buttons side by side; anything taller uses the
  normal layout. In **Auto** text mode the quote size is fitted to the space. It is redrawn when
  you resize the widget.
- **Fixed sizes are real sizes.** If a quote is too long for the space at Medium or Large it is
  cut with an ellipsis. Make the widget taller or pick Small or Auto.
- Your style is included in the quotes backup, and old backups without a style still import.
- The *System* theme follows Android 12+ dynamic colours and updates on the widget's next
  refresh, not instantly when the system theme changes.
- Buttons resize with the size slider on Android 12+ only; on Android 11 they keep their base size.

### Permission screens follow the manifest

A permission that is not declared in `AndroidManifest.xml` can never be granted, because Android
shows no dialog for it. ARK-launcher's screens could still ask for such permissions, so the UI now
checks the manifest through one helper (`declaresPermission()` in `core/ui`):

- no `<uses-permission>` line means no prompt, toggle or settings row for it;
- Smartspace widget: no location permission means no weather and no "Tap to allow location" line;
  no calendar permission means no calendar prompt;
- onboarding and the settings Permissions list only show declared permissions, and "Grant all"
  only asks for those;
- the weather settings and the contacts-search section are hidden when their permission is
  not declared.

In this snapshot the manifest declares only `READ_CALENDAR` of the runtime permissions, so
weather, contacts search and the mobile-signal icon are unavailable. To bring a feature back,
add its `<uses-permission>` line again and its UI returns by itself.

### New launcher icon

The icon is an **AK monogram** on a navy-to-purple background with a warm glow, matching the
Midnight widget theme. It is an adaptive icon with a monochrome layer, so it follows the
Android 13+ themed-icon setting. `tools/make_icon.py` (Python and Pillow) generates the PNGs, so
you can change the colours or the shape and regenerate them. Claude designed and generated it. It
has only been checked as a render under circle, rounded and square masks, not on a device.

### Languages

The launcher keeps ARK-launcher's translations (English, Finnish, German, Spanish, French,
Polish, Portuguese, Russian, Swedish and Turkish). The Motivation widget screens and the
text added in this project are **English only** for now and fall back to English in other
languages. Translations are welcome: add the strings to the matching `values-<lang>/strings.xml`.

## Features inherited from ARK-launcher

Paged home screen with drag and drop, dock, folders, app drawer with universal search (apps,
settings, optional contacts, calculator and unit converter), home-screen widgets with resize and
reconfigure, notification dots, themed icons and icon packs, configurable gestures, backup and
restore, double-tap to lock, lock desktop, and many appearance options. The full feature list
lives in the
[ARK-launcher README](https://github.com/jrs8205/ARK-launcher#features), and its
[wiki](https://github.com/jrs8205/ARK-launcher/wiki) has a user guide. Both describe
ARK-launcher, so where they differ from this project (the Motivation widget, the permissions
below), this README is the one that applies.

## Permissions

What this snapshot declares in its merged manifest:

- **Runtime (asks you):** `READ_CALENDAR` only, for the next event in the clock widget.
- **No prompt:** `SET_WALLPAPER`, `EXPAND_STATUS_BAR`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `BLUETOOTH` (Android 11 only), `QUERY_ALL_PACKAGES`,
  `REQUEST_DELETE_PACKAGES`, `HIDE_OVERLAY_WINDOWS` and `INTERNET`.
- `INTERNET` is declared in `core/data` and used only by the weather code. Weather needs the
  location permission, which is not declared here, and the weather code returns before any request
  without it, so the launcher makes no network requests at the moment. If you want a launcher
  with no network access at all, remove that `<uses-permission>` line.
- The Motivation widget needs no permission. Quotes and style settings are stored on the device.

## Requirements

- Android 11 (API 30) or newer
- To build: JDK 21 and the Android SDK (Android Studio's bundled JDK works)

## Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as `com.kich.launcher.debug`. Then set it as your home app in
Settings > Apps > Default apps > Home app.

No computer? See [BUILD_ON_PHONE.md](BUILD_ON_PHONE.md) to build with GitHub Actions from a phone.

### Release signing

**Never commit a keystore (`.p12`, `.jks`, `.keystore`) or its password to this repository.**
Anyone with them can sign apps as you. Keep them outside the repo and give the release workflow
the values through your CI's secret store. If a keystore was ever committed or published, treat it
as compromised and create a new one.

## Project layout

- `app`: the single Activity, manifest, dependency injection, the AppWidgetHost, and the
  Motivation widget (`app/.../motivation`)
- `core/*`: `model`, `data`, `ui` (shared Compose components), `designsystem`, `launcher`
- `feature/*`: `home` (workspace, dock, widgets), `appdrawer`, `settings`, `backup`
- `build-logic`: Gradle convention plugins

Files for the Motivation widget:

- `MotivationWidgetProvider.kt`: draws the widget (responsive layout and text fitting)
- `MotivationStyle.kt`: themes, colour resolving, text-fit estimate
- `MotivationStore.kt`: quotes, favourites and style settings (SharedPreferences), backup
- `MotivationActivity.kt`: the settings screen (Compose)
- `res/layout/widget_motivation*.xml`, `res/drawable/widget_motivation_*.xml`: layouts and shapes

## Status

- The Claude-written parts (Motivation styling, size and text controls, permission clean-up)
  were checked for XML validity and bracket balance only. They were **not compiled and not run
  on a device** by Claude. Build and test them, and open an issue if something breaks.
- The launcher icon was only checked as a rendered image, not installed on a device.
- The text-fit in Auto mode is an estimate from average glyph width, not a real measurement.
- Only RemoteViews-safe views can be used in the widget layouts (`FrameLayout`, `LinearLayout`,
  `ImageView`, `TextView`). A plain `View` makes the widget fail with "Couldn't add widget".

## Credits

- **ARK-launcher** by [jrs8205](https://github.com/jrs8205/ARK-launcher), the project this is
  based on (Apache-2.0).
- **Android Open Source Project, Launcher3**: design and architecture patterns that ARK-launcher
  re-implemented in Compose (Apache-2.0). See [NOTICE](NOTICE).
- **Open-Meteo** for weather data (CC BY 4.0) and **BigDataCloud** for place-name lookup, as
  listed in [NOTICE](NOTICE).
- **Claude** (Anthropic) wrote the Motivation widget styling, responsive layout, customisation
  settings, the manifest-driven permission UI and the launcher icon described above.

## Contributing

Issues and pull requests are welcome. For the launcher core, check whether the change belongs in
[ARK-launcher](https://github.com/jrs8205/ARK-launcher) first; changes to the Motivation widget,
the icon and the permission handling belong here. By contributing you agree your work is licensed
under Apache-2.0.

## License

Licensed under the **Apache License, Version 2.0**, see [LICENSE](LICENSE). Keep the
[NOTICE](NOTICE) file and the original copyright notices when you redistribute, and mark files you
change.
