package com.kich.launcher.data

import com.kich.launcher.data.local.HomeItemEntity
import com.kich.launcher.data.local.HomeItemEntity.Companion.DOCK
import com.kich.launcher.data.local.HomeItemEntity.Companion.HOME
import com.kich.launcher.model.AppItem

/**
 * Builds the first-time home screen of a new AK workspace as plain, unsaved [HomeItemEntity] rows.
 *
 * Pure and deterministic: the same inputs always give the same rows, and nothing here touches
 * Android, Room or storage — [HomeLayoutRepository.initializeDefaultLayout] stores the result.
 * It works on the user's current grid (columns × rows), never on pixel coordinates.
 *
 * Strategy (a freshly set-up phone):
 *  - Page 0 starts with the built-in clock/date (smartspace) widget across the full width at the top.
 *  - The dock holds [dockApps] (the phone/messages/settings/browser/camera defaults), capped at
 *    [dockColumns]; those apps are not repeated on the pages, as on a stock phone.
 *  - Every other app follows in alphabetical order, row by row, into the cells the widget leaves
 *    free; a full page continues on the next one (at most [HomeLayoutRepository.MAX_PAGES]).
 */
object DefaultWorkspaceLayout {

    /** Height in rows of the default clock/date widget (also the first-run install's height). */
    const val SMARTSPACE_SPAN_Y = 2

    data class Plan(val home: List<HomeItemEntity>, val dock: List<HomeItemEntity>)

    /**
     * @param apps every app that may appear on home (hidden apps already removed by the caller).
     * @param dockApps preferred dock apps, in order; entries that are not in [apps] are ignored.
     * @param smartspaceSpanY height of the clock/date widget in rows, or 0 for none.
     */
    fun plan(
        apps: List<AppItem>,
        dockApps: List<AppItem>,
        columns: Int,
        rows: Int,
        dockColumns: Int,
        smartspaceSpanY: Int,
    ): Plan {
        val cols = columns.coerceAtLeast(1)
        val rws = rows.coerceAtLeast(1)

        val available = apps.associateBy { it.key }
        val dock = dockApps
            .mapNotNull { available[it.key] }
            .distinctBy { it.key }
            .take(dockColumns.coerceAtLeast(0))
        val dockKeys = dock.mapTo(HashSet()) { it.key }

        val rowsOut = ArrayList<HomeItemEntity>()

        // Widget footprint on page 0. It must leave at least one free row for icons, so a very
        // short grid gets a shorter widget (or none) rather than an icon-less first page.
        val widgetRows = if (smartspaceSpanY <= 0 || rws < 2) 0 else smartspaceSpanY.coerceIn(1, rws - 1)
        if (widgetRows > 0) {
            rowsOut += HomeItemEntity(
                containerId = HOME, page = 0, cellX = 0, cellY = 0,
                spanX = cols, spanY = widgetRows,
                builtinType = HomeItemEntity.BUILTIN_SMARTSPACE,
            )
        }

        // Deterministic order: label (case-insensitive), then the stable key as the tiebreak.
        val ordered = apps
            .filter { it.key !in dockKeys }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.key }))

        var index = 0
        var page = 0
        while (index < ordered.size && HomeLayoutRepository.isShownPage(page)) {
            val firstRow = if (page == 0) widgetRows else 0
            for (y in firstRow until rws) {
                for (x in 0 until cols) {
                    if (index >= ordered.size) break
                    val app = ordered[index++]
                    rowsOut += HomeItemEntity(
                        containerId = HOME, packageName = app.packageName, className = app.className,
                        userSerial = app.userSerial, page = page, cellX = x, cellY = y,
                    )
                }
            }
            page++
        }

        val dockRows = dock.mapIndexed { slot, app ->
            HomeItemEntity(
                containerId = DOCK, packageName = app.packageName, className = app.className,
                userSerial = app.userSerial, page = 0, cellX = slot, cellY = 0,
            )
        }
        return Plan(rowsOut, dockRows)
    }
}
