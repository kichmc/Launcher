package com.kich.launcher.data

import com.kich.launcher.data.local.HomeItemEntity
import com.kich.launcher.data.local.HomeItemEntity.Companion.DOCK
import com.kich.launcher.data.local.HomeItemEntity.Companion.HOME
import com.kich.launcher.model.AppItem
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The home-screen layout another launcher exported through the documented Launcher3 provider API
 * (`EXPORT_LAYOUT_XML`, see [ExistingLauncherImport]), and how it is fitted into AK.
 *
 * Pure Kotlin (no Android types): parsing and planning are unit-testable. Only apps, folders and the
 * hotseat are read; widgets and other shortcuts are ignored (they cannot be rebuilt from a package name).
 */
object ExistingLauncherLayout {

    /** One app reference from the export: [className] may be blank (then any launcher activity of the package matches). */
    data class AppRef(val packageName: String, val className: String)

    sealed interface Entry { val screen: Int; val x: Int; val y: Int }
    data class AppEntry(val app: AppRef, override val screen: Int, override val x: Int, override val y: Int) : Entry
    data class FolderEntry(val title: String, val apps: List<AppRef>, override val screen: Int, override val x: Int, override val y: Int) : Entry

    data class Parsed(val columns: Int, val rows: Int, val desktop: List<Entry>, val hotseat: List<AppRef>)

    /** Largest export accepted: a home screen is a few KB, so anything near this is not one. */
    const val MAX_XML_CHARS = 1_000_000
    private const val MAX_ENTRIES = 2_000

    /**
     * Parses [xml], or returns null when it is not a usable export (too large, has a DOCTYPE — an
     * export never needs one and it is the way into entity attacks —, not well-formed, or empty).
     */
    fun parse(xml: String): Parsed? {
        if (xml.isBlank() || xml.length > MAX_XML_CHARS || xml.contains("<!DOCTYPE", ignoreCase = true)) return null
        val root = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.isExpandEntityReferences = false }
            factory.newDocumentBuilder().apply { setErrorHandler(null) }.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        }.getOrNull() ?: return null

        val desktop = ArrayList<Entry>()
        val hotseat = ArrayList<Pair<Int, AppRef>>()
        var seen = 0
        for (el in root.elements()) {
            if (++seen > MAX_ENTRIES) break
            val container = el.getAttribute("container").lowercase()
            val tag = el.tagName.lowercase()
            val x = el.intAttr("x"); val y = el.intAttr("y"); val screen = el.intAttr("screen")
            when {
                tag == "folder" || tag == "partner-folder" -> {
                    if (container == "hotseat") continue // a hotseat folder has no equivalent in the AK dock
                    val apps = el.elements().mapNotNull(::appRef)
                    if (apps.isNotEmpty()) desktop += FolderEntry(el.getAttribute("title").ifBlank { el.getAttribute("titleText") }, apps, screen, x, y)
                }
                tag == "appwidget" || tag == "searchwidget" -> Unit
                else -> {
                    val app = appRef(el) ?: continue
                    if (container == "hotseat") hotseat += (el.intAttr("rank", el.intAttr("x")) to app)
                    else desktop += AppEntry(app, screen, x, y)
                }
            }
        }
        if (desktop.isEmpty() && hotseat.isEmpty()) return null
        return Parsed(
            columns = root.intAttr("columns", 0), rows = root.intAttr("rows", 0),
            desktop = desktop, hotseat = hotseat.sortedBy { it.first }.map { it.second },
        )
    }

    private fun appRef(el: Element): AppRef? {
        val pkg = el.getAttribute("packageName")
        if (pkg.isBlank() || el.tagName.lowercase() in NOT_APPS) return null
        return AppRef(pkg, el.getAttribute("className"))
    }

    private val NOT_APPS = setOf("appwidget", "searchwidget", "shortcut", "folder", "partner-folder")

    private fun Element.intAttr(name: String, default: Int = 0) = getAttribute(name).toIntOrNull() ?: default
    private fun Element.elements(): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    /**
     * Fits [parsed] into AK's [columns]×[rows] grid with the apps in [installed], or null when none of its
     * apps is installed here (then the caller generates a layout instead).
     *
     * Order and grouping are kept: pages follow the export's screens in order, entries go in reading
     * order, an app or folder keeps its cell when that cell exists and is free, and otherwise moves to
     * the next free cell after it. Apps that are not installed are skipped, an app is placed once, the
     * dock comes from the hotseat (cut to [dockColumns]) and is not repeated on the pages, and a folder
     * left with one app becomes a plain icon. No clock widget is added: the layout is the user's.
     */
    fun plan(
        parsed: Parsed,
        installed: List<AppItem>,
        columns: Int,
        rows: Int,
        dockColumns: Int,
    ): DefaultWorkspaceLayout.Plan? {
        val cols = columns.coerceAtLeast(1)
        val rws = rows.coerceAtLeast(1)

        fun resolve(ref: AppRef): AppItem? =
            installed.firstOrNull { it.packageName == ref.packageName && ref.className.isNotBlank() && it.className == ref.className }
                ?: installed.firstOrNull { it.packageName == ref.packageName }

        // The dock comes first; its apps are not repeated on the pages (hotseat apps cut by the dock's size
        // are not placed either: they were never on the pages in the source launcher).
        val dock = parsed.hotseat.mapNotNull(::resolve).distinctBy { it.key }.take(dockColumns.coerceAtLeast(0))
        val used = dock.mapTo(HashSet()) { it.key }
        fun take(ref: AppRef): AppItem? = resolve(ref)?.takeIf { used.add(it.key) }

        val screens = parsed.desktop.map { it.screen }.distinct().sorted()
        val ordered = parsed.desktop.sortedWith(compareBy({ screens.indexOf(it.screen) }, { it.y }, { it.x }))

        val home = ArrayList<HomeItemEntity>()
        val folders = ArrayList<DefaultWorkspaceLayout.PlannedFolder>()
        val occupied = HashSet<Triple<Int, Int, Int>>()

        /** The wanted cell when it exists and is free, else the next free cell in reading order after it
         *  (from the page's start when the wanted cell is outside AK's grid); null when AK has no room. */
        fun freeCell(page0: Int, x0: Int, y0: Int): Triple<Int, Int, Int>? {
            val valid = x0 in 0 until cols && y0 in 0 until rws && HomeLayoutRepository.isShownPage(page0)
            if (valid && Triple(page0, x0, y0) !in occupied) return Triple(page0, x0, y0)
            for (page in page0 until HomeLayoutRepository.MAX_PAGES) {
                if (!HomeLayoutRepository.isShownPage(page)) break
                for (i in (if (page == page0 && valid) y0 * cols + x0 + 1 else 0) until cols * rws) {
                    val c = Triple(page, i % cols, i / cols)
                    if (c !in occupied) return c
                }
            }
            return null
        }

        for (entry in ordered) {
            val page = screens.indexOf(entry.screen).coerceAtLeast(0)
            when (entry) {
                is AppEntry -> {
                    val app = take(entry.app) ?: continue
                    val cell = freeCell(page, entry.x, entry.y) ?: continue
                    occupied += cell
                    home += HomeItemEntity(
                        containerId = HOME, packageName = app.packageName, className = app.className,
                        userSerial = app.userSerial, page = cell.first, cellX = cell.second, cellY = cell.third,
                    )
                }
                is FolderEntry -> {
                    val apps = entry.apps.mapNotNull(::take).take(DefaultWorkspaceLayout.MAX_FOLDER_APPS * 2)
                    if (apps.isEmpty()) continue
                    val cell = freeCell(page, entry.x, entry.y) ?: continue
                    occupied += cell
                    if (apps.size == 1) {
                        val app = apps.single()
                        home += HomeItemEntity(
                            containerId = HOME, packageName = app.packageName, className = app.className,
                            userSerial = app.userSerial, page = cell.first, cellX = cell.second, cellY = cell.third,
                        )
                    } else {
                        folders += DefaultWorkspaceLayout.PlannedFolder(entry.title.ifBlank { "Folder" }, cell.first, cell.second, cell.third, apps)
                    }
                }
            }
        }

        if (home.isEmpty() && folders.isEmpty() && dock.isEmpty()) return null
        val dockRows = dock.mapIndexed { slot, app ->
            HomeItemEntity(
                containerId = DOCK, packageName = app.packageName, className = app.className,
                userSerial = app.userSerial, page = 0, cellX = slot, cellY = 0,
            )
        }
        return DefaultWorkspaceLayout.Plan(home, folders, dockRows)
    }
}
