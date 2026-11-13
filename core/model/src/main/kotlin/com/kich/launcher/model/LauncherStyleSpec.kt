package com.kich.launcher.model

/**
 * Which style-specific *behaviours* a launcher style switches on. Only the parts listed here change
 * with the style; everything else (apps, folders, widgets, wallpaper, grid) is shared state.
 *
 * Adding another style's behaviour later (Pixel, HyperOS, ColorOS) means adding a field here and
 * returning it from [of]; the screens only ever read the spec, never the raw style string.
 */
data class LauncherStyleSpec(
    /** A long press on empty home space opens the zoomed-out page editor (One UI "edit mode")
     *  instead of the small options popup. */
    val pageEditor: Boolean = false,
    /** Home page indicator: leading "=" glyph, house marker on the default page, round dots. */
    val oneUiPageDots: Boolean = false,
    /** App drawer: floating pill search bar (search icon + mic) at the bottom. */
    val drawerPillSearch: Boolean = false,
    /** App drawer: round page dots (no stretched "current" pill) and a top-right three-dot menu. */
    val drawerOneUiChrome: Boolean = false,
) {
    companion object {
        val UNIVERSAL = LauncherStyleSpec()

        val ONE_UI = LauncherStyleSpec(
            pageEditor = true,
            oneUiPageDots = true,
            drawerPillSearch = true,
            drawerOneUiChrome = true,
        )

        fun of(style: String): LauncherStyleSpec = when (style) {
            LauncherSettings.STYLE_ONE_UI -> ONE_UI
            else -> UNIVERSAL
        }
    }
}
