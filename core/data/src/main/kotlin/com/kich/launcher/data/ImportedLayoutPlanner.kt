package com.kich.launcher.data

import com.kich.launcher.data.local.HomeItemEntity
import com.kich.launcher.data.local.HomeItemEntity.Companion.DOCK
import com.kich.launcher.data.local.HomeItemEntity.Companion.HOME
import com.kich.launcher.model.AppItem

/** One row of another launcher's `favorites` table, reduced to what AK can use. */
data class ImportedRow(
    val id: Long,
    val itemType: Int,
    val container: Long,
    val screen: Int,
    val cellX: Int,
    val cellY: Int,
    val rank: Int,
    val title: String?,
    val packageName: String?,
    val className: String?,
)

/**
 * Turns rows read from another launcher (Launcher3's `favorites` schema: container -100 desktop, -101
 * hotseat, a folder's id for its children; itemType 0 app, 2 folder) into an AK [DefaultWorkspaceLayout.Plan].
 *
 * Pure and deterministic. Only what AK can honour is kept: apps that are installed (anything else is
 * skipped), folders (a folder left with one app becomes that app), the dock, and the page each item was
 * on (empty pages are dropped, the order is kept). Widgets and shortcuts are not imported. An item whose
 * cell does not exist on AK's grid, or is taken, moves to the next free cell of its page, then of the
 * following pages — never onto another item. Returns null when nothing could be imported.
 */
object ImportedLayoutPlanner {
    const val CONTAINER_DESKTOP = -100L
    const val CONTAINER_HOTSEAT = -101L
    const val TYPE_APP = 0
    const val TYPE_FOLDER = 2
    const val MAX_PAGES = 8

    private sealed interface Entry {
        val page: Int
        data class App(val app: AppItem, override val page: Int, val x: Int, val y: Int) : Entry
        data class Folder(val name: String, val apps: List<AppItem>, override val page: Int, val x: Int, val y: Int) : Entry
    }

    fun plan(
        source: List<ImportedRow>,
        installed: List<AppItem>,
        columns: Int,
        rows: Int,
        dockColumns: Int,
        smartspaceSpanY: Int,
    ): DefaultWorkspaceLayout.Plan? {
        val cols = columns.coerceAtLeast(1)
        val rws = rows.coerceAtLeast(1)
        val byComponent = installed.associateBy { it.packageName to it.className }
        val byPackage = installed.groupBy { it.packageName }
            .mapValues { (_, v) -> v.minWith(compareBy({ it.userSerial }, { it.className })) }
        fun resolve(r: ImportedRow): AppItem? {
            val pkg = r.packageName ?: return null
            return byComponent[pkg to r.className] ?: byPackage[pkg]
        }

        val used = HashSet<String>()

        // Dock first: an app is never both in the dock and on a page.
        val dockApps = source.asSequence()
            .filter { it.container == CONTAINER_HOTSEAT && it.itemType == TYPE_APP }
            .sortedWith(compareBy({ it.screen }, { it.cellX }, { it.id }))
            .mapNotNull(::resolve)
            .filter { used.add(it.key) }
            .take(dockColumns.coerceAtLeast(0))
            .toList()

        // Desktop items in reading order of their original pages.
        val desktop = source.filter { it.container == CONTAINER_DESKTOP }
            .sortedWith(compareBy({ it.screen }, { it.cellY }, { it.cellX }, { it.id }))
        val childrenOf = source.filter { it.container >= 0 && it.itemType == TYPE_APP }.groupBy { it.container }

        val raw = ArrayList<Pair<Int, Entry>>() // original screen to entry
        for (r in desktop) {
            when (r.itemType) {
                TYPE_APP -> resolve(r)?.takeIf { used.add(it.key) }?.let { raw += r.screen to Entry.App(it, 0, r.cellX, r.cellY) }
                TYPE_FOLDER -> {
                    val members = childrenOf[r.id].orEmpty()
                        .sortedWith(compareBy({ it.rank }, { it.cellY }, { it.cellX }, { it.id }))
                        .mapNotNull(::resolve)
                        .filter { used.add(it.key) }
                    when (members.size) {
                        0 -> Unit
                        1 -> raw += r.screen to Entry.App(members[0], 0, r.cellX, r.cellY)
                        else -> raw += r.screen to Entry.Folder(r.title?.takeIf { it.isNotBlank() } ?: "Folder", members, 0, r.cellX, r.cellY)
                    }
                }
            }
        }
        if (raw.isEmpty() && dockApps.isEmpty()) return null

        // Original screens → consecutive AK pages, order kept, empty ones dropped.
        val pageOf = raw.map { it.first }.distinct().sorted().withIndex().associate { (i, s) -> s to i }
        val entries = raw.map { (screen, e) ->
            val page = pageOf.getValue(screen)
            when (e) {
                is Entry.App -> e.copy(page = page)
                is Entry.Folder -> e.copy(page = page)
            }
        }

        // The clock widget only when the top rows of page 0 are really free in the source layout.
        val widgetRows = if (smartspaceSpanY <= 0 || rws < 2) 0 else smartspaceSpanY.coerceIn(1, rws - 1)
        val widgetFits = widgetRows > 0 && entries.none { e ->
            e.page == 0 && when (e) { is Entry.App -> e.y < widgetRows; is Entry.Folder -> e.y < widgetRows }
        }

        val occupied = HashMap<Int, HashSet<Pair<Int, Int>>>()
        fun cellsOf(page: Int) = occupied.getOrPut(page) { HashSet() }
        val home = ArrayList<HomeItemEntity>()
        if (widgetFits) {
            for (y in 0 until widgetRows) for (x in 0 until cols) cellsOf(0).add(x to y)
            home += HomeItemEntity(
                containerId = HOME, page = 0, cellX = 0, cellY = 0, spanX = cols, spanY = widgetRows,
                builtinType = HomeItemEntity.BUILTIN_SMARTSPACE,
            )
        }

        /** The wanted cell when it exists and is free, else the first free cell from [page] on; null when full. */
        fun slot(page: Int, wantX: Int, wantY: Int): Triple<Int, Int, Int>? {
            if (wantX in 0 until cols && wantY in 0 until rws && (wantX to wantY) !in cellsOf(page)) return Triple(page, wantX, wantY)
            var p = page
            while (p < MAX_PAGES && HomeLayoutRepository.isShownPage(p)) {
                for (y in 0 until rws) for (x in 0 until cols) if ((x to y) !in cellsOf(p)) return Triple(p, x, y)
                p++
            }
            return null
        }

        val folders = ArrayList<DefaultWorkspaceLayout.PlannedFolder>()
        for (e in entries.sortedWith(compareBy({ it.page }))) {
            when (e) {
                is Entry.App -> slot(e.page, e.x, e.y)?.let { (p, x, y) ->
                    cellsOf(p).add(x to y)
                    home += HomeItemEntity(
                        containerId = HOME, packageName = e.app.packageName, className = e.app.className,
                        userSerial = e.app.userSerial, page = p, cellX = x, cellY = y,
                    )
                }
                is Entry.Folder -> slot(e.page, e.x, e.y)?.let { (p, x, y) ->
                    cellsOf(p).add(x to y)
                    folders += DefaultWorkspaceLayout.PlannedFolder(e.name, p, x, y, e.apps)
                }
            }
        }

        val dockRows = dockApps.mapIndexed { slotIndex, app ->
            HomeItemEntity(
                containerId = DOCK, packageName = app.packageName, className = app.className,
                userSerial = app.userSerial, page = 0, cellX = slotIndex, cellY = 0,
            )
        }
        return DefaultWorkspaceLayout.Plan(home, folders, dockRows)
    }
}
