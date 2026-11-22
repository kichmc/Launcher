package com.kich.launcher.data

import com.kich.launcher.data.local.HomeItemEntity
import com.kich.launcher.data.local.HomeItemEntity.Companion.DOCK
import com.kich.launcher.data.local.HomeItemEntity.Companion.HOME
import com.kich.launcher.model.AppItem

/**
 * Builds the first-time home screen of a new AK workspace: which apps go where.
 *
 * Pure and deterministic — no Android, Room or storage; the same inputs give the same result.
 * [HomeLayoutRepository.initializeDefaultLayout] stores it. It works on the live grid (columns × rows).
 *
 *  - Page 0 starts with the built-in clock/date widget; the dock gets [dockApps] (not repeated on pages).
 *  - Apps are NOT all placed: only recognised, useful ones, ordered by category priority for the
 *    workspace kind (see [AppCategorizer.priority]) and, inside a category, by the categorizer's list
 *    order. Unrecognised apps stay in the drawer. At most [MAX_AUTO_PAGES] pages are filled, and every
 *    page keeps its bottom row empty so nothing is crammed.
 *  - Related apps are folded into at most [MAX_FOLDERS] folders (Google, Microsoft, Games, Social, Media),
 *    only when a group has [MIN_FOLDER_APPS]+ apps; a folder holds at most [MAX_FOLDER_APPS]. Messaging and
 *    browsers are never foldered, and a group that is the workspace's main focus (Games on a gaming
 *    workspace, Microsoft on a work one) stays loose.
 */
object DefaultWorkspaceLayout {

    const val SMARTSPACE_SPAN_Y = 2
    const val MAX_AUTO_PAGES = 2
    const val MAX_FOLDERS = 3
    const val MIN_FOLDER_APPS = 3
    const val MAX_FOLDER_APPS = 8

    data class PlannedFolder(val name: String, val page: Int, val cellX: Int, val cellY: Int, val apps: List<AppItem>)

    data class Plan(
        /** Widget and loose app rows on the home pages. */
        val home: List<HomeItemEntity>,
        val folders: List<PlannedFolder>,
        val dock: List<HomeItemEntity>,
    )

    private class Group(val name: String, val suppressedFor: WorkspaceKind?, val matches: (AppItem, AppCategory) -> Boolean)

    private val groups = listOf(
        Group("Google", null) { a, _ -> a.packageName.startsWith("com.google.android.apps.") || a.packageName in GOOGLE_EXACT },
        Group("Microsoft", WorkspaceKind.WORK) { a, _ -> a.packageName.startsWith("com.microsoft.") },
        Group("Games", WorkspaceKind.GAMING) { _, c -> c == AppCategory.GAMES },
        Group("Social", null) { _, c -> c == AppCategory.SOCIAL },
        Group("Media", null) { _, c -> c == AppCategory.MEDIA },
    )
    private val GOOGLE_EXACT = setOf("com.google.android.youtube", "com.google.android.gm", "com.google.android.videos", "com.google.android.keep")

    private class Cand(val app: AppItem, val category: AppCategory, val catRank: Int, val rule: Int)

    /** An app or a folder, in priority order, waiting for a cell. */
    private class Item(val catRank: Int, val rule: Int, val label: String, val key: String, val app: AppItem?, val folder: Pair<String, List<AppItem>>?)

    fun plan(
        apps: List<AppItem>,
        dockApps: List<AppItem>,
        columns: Int,
        rows: Int,
        dockColumns: Int,
        smartspaceSpanY: Int,
        kind: WorkspaceKind = WorkspaceKind.GENERAL,
        systemCategories: Map<String, Int> = emptyMap(),
    ): Plan {
        val cols = columns.coerceAtLeast(1)
        val rws = rows.coerceAtLeast(1)

        val available = apps.associateBy { it.key }
        val dock = dockApps.mapNotNull { available[it.key] }.distinctBy { it.key }.take(dockColumns.coerceAtLeast(0))
        val dockKeys = dock.mapTo(HashSet()) { it.key }

        val home = ArrayList<HomeItemEntity>()
        val widgetRows = if (smartspaceSpanY <= 0 || rws < 2) 0 else smartspaceSpanY.coerceIn(1, rws - 1)
        if (widgetRows > 0) {
            home += HomeItemEntity(
                containerId = HOME, page = 0, cellX = 0, cellY = 0, spanX = cols, spanY = widgetRows,
                builtinType = HomeItemEntity.BUILTIN_SMARTSPACE,
            )
        }

        // 1. Recognised, not-docked apps with their category rank.
        val order = AppCategorizer.priority(kind)
        val cands = apps.asSequence()
            .filter { it.key !in dockKeys }
            .distinctBy { it.key }
            .map { a ->
                val cat = AppCategorizer.categoryOf(a.packageName, systemCategories[a.key] ?: -1)
                Cand(a, cat, order.indexOf(cat), AppCategorizer.ruleIndex(a.packageName))
            }
            .filter { it.category != AppCategory.OTHER }
            .sortedWith(compareBy({ it.catRank }, { it.rule }, { it.app.label.lowercase() }, { it.app.key }))
            .toList()

        // 2. Folders: groups with enough members, never for messaging/browsers, capped.
        val folderOf = HashMap<String, String>()          // app key -> folder name
        val folderApps = LinkedHashMap<String, List<AppItem>>()
        val pool = cands.filter { it.category != AppCategory.COMMUNICATION && it.category != AppCategory.BROWSER }
        for (g in groups) {
            if (folderApps.size >= MAX_FOLDERS) break
            if (g.suppressedFor == kind) continue
            val members = pool.filter { it.app.key !in folderOf && g.matches(it.app, it.category) }.take(MAX_FOLDER_APPS)
            if (members.size < MIN_FOLDER_APPS) continue
            folderApps[g.name] = members.map { it.app }
            members.forEach { folderOf[it.app.key] = g.name }
        }

        // 3. Loose apps and folders in one priority-ordered list.
        val byKey = cands.associateBy { it.app.key }
        val items = ArrayList<Item>()
        cands.filter { it.app.key !in folderOf }.forEach {
            items += Item(it.catRank, it.rule, it.app.label.lowercase(), it.app.key, it.app, null)
        }
        for ((name, members) in folderApps) {
            val best = members.map { byKey.getValue(it.key) }
            items += Item(best.minOf { it.catRank }, best.minOf { it.rule }, name.lowercase(), "folder:$name", null, name to members)
        }
        items.sortWith(compareBy({ it.catRank }, { it.rule }, { it.label }, { it.key }))

        // 4. Fill cells row by row; every page keeps its bottom row free; at most MAX_AUTO_PAGES pages.
        val folders = ArrayList<PlannedFolder>()
        var index = 0
        for (page in 0 until MAX_AUTO_PAGES) {
            if (!HomeLayoutRepository.isShownPage(page)) break
            val firstRow = if (page == 0) widgetRows else 0
            val usableRows = (rws - firstRow - 1).coerceAtLeast(1).coerceAtMost(rws - firstRow)
            for (y in firstRow until firstRow + usableRows) for (x in 0 until cols) {
                if (index >= items.size) break
                val it = items[index++]
                if (it.app != null) {
                    home += HomeItemEntity(
                        containerId = HOME, packageName = it.app.packageName, className = it.app.className,
                        userSerial = it.app.userSerial, page = page, cellX = x, cellY = y,
                    )
                } else {
                    folders += PlannedFolder(it.folder!!.first, page, x, y, it.folder.second)
                }
            }
        }

        val dockRows = dock.mapIndexed { slot, app ->
            HomeItemEntity(
                containerId = DOCK, packageName = app.packageName, className = app.className,
                userSerial = app.userSerial, page = 0, cellX = slot, cellY = 0,
            )
        }
        return Plan(home, folders, dockRows)
    }
}
