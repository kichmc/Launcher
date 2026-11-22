package com.kich.launcher.data

import android.os.Process
import com.google.common.truth.Truth.assertThat
import com.kich.launcher.data.local.HomeItemEntity
import com.kich.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultWorkspaceLayoutTest {

    private fun app(pkg: String) = AppItem(
        packageName = pkg, className = "$pkg.Main", user = Process.myUserHandle(),
        userSerial = 0L, label = pkg.substringAfterLast('.'),
    )

    private val pkgs = listOf(
        "com.google.android.dialer", "com.google.android.apps.messaging", "com.android.chrome",
        "com.sec.android.app.camera", "com.android.settings", "com.whatsapp", "org.telegram.messenger",
        "com.google.android.gm", "com.instagram.android", "com.facebook.katana", "com.snapchat.android",
        "com.google.android.youtube", "com.spotify.music", "com.netflix.mediaclient",
        "com.google.android.apps.photos", "com.google.android.apps.maps", "com.google.android.apps.docs",
        "com.google.android.calendar", "com.google.android.deskclock", "com.google.android.calculator",
        "com.android.vending", "com.microsoft.office.word", "com.microsoft.office.excel",
        "com.microsoft.teams", "com.microsoft.office.outlook", "com.slack", "com.game.one",
        "com.game.two", "com.game.three", "com.game.four", "com.discord", "com.weird.unknown.a",
    )
    private val apps = pkgs.map(::app)
    private fun by(vararg p: String) = p.map { x -> apps.first { it.packageName == x } }
    private val dock = by(
        "com.google.android.dialer", "com.google.android.apps.messaging", "com.android.chrome",
        "com.sec.android.app.camera", "com.android.settings",
    )
    private val games = apps.filter { it.packageName.startsWith("com.game.") }.associate { it.key to 0 }

    private fun plan(
        kind: WorkspaceKind = WorkspaceKind.GENERAL, columns: Int = 4, rows: Int = 6,
        list: List<AppItem> = apps, dockApps: List<AppItem> = dock,
    ) = DefaultWorkspaceLayout.plan(list, dockApps, columns, rows, 4, 2, kind, games)

    private fun cells(r: HomeItemEntity) =
        (0 until r.spanX).flatMap { dx -> (0 until r.spanY).map { dy -> Triple(r.page, r.cellX + dx, r.cellY + dy) } }

    private fun allCells(p: DefaultWorkspaceLayout.Plan) =
        p.home.flatMap(::cells) + p.folders.map { Triple(it.page, it.cellX, it.cellY) }

    private fun placed(p: DefaultWorkspaceLayout.Plan) =
        p.home.filter { it.isApp }.map { it.packageName } + p.folders.flatMap { f -> f.apps.map { it.packageName } }

    private fun order(p: DefaultWorkspaceLayout.Plan): List<String> =
        (p.home.filter { it.isApp }.map { Triple(it.page, it.cellY, it.cellX) to it.packageName } +
            p.folders.map { Triple(it.page, it.cellY, it.cellX) to "[${it.name}]" })
            .sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third })).map { it.second }

    @Test
    fun `dock keeps phone, messages, browser, camera and nothing is repeated on the pages`() {
        val p = plan()
        assertThat(p.dock).hasSize(4)
        assertThat(placed(p)).containsNoneIn(p.dock.map { it.packageName })
        assertThat(placed(p)).containsNoDuplicates()
    }

    @Test
    fun `only recognised apps are placed`() {
        val p = plan()
        assertThat(placed(p)).doesNotContain("com.weird.unknown.a")
        assertThat(placed(p).size).isLessThan(apps.size)
    }

    @Test
    fun `messaging comes before social which comes before media on a general workspace`() {
        val o = order(plan())
        assertThat(o.indexOf("com.whatsapp")).isLessThan(o.indexOf("org.telegram.messenger"))
        assertThat(o.indexOf("org.telegram.messenger")).isLessThan(o.indexOf("com.instagram.android"))
        assertThat(o.indexOf("com.instagram.android")).isLessThan(o.indexOf("com.spotify.music"))
    }

    @Test
    fun `workspace kind changes the arrangement`() {
        val work = order(plan(WorkspaceKind.WORK))
        val gaming = order(plan(WorkspaceKind.GAMING))
        assertThat(gaming.take(4)).containsExactly("com.game.four", "com.game.one", "com.game.three", "com.game.two")
        assertThat(work.take(2)).doesNotContain("com.whatsapp")
        assertThat(work).isNotEqualTo(gaming)
        assertThat(work).isNotEqualTo(order(plan(WorkspaceKind.GENERAL)))
    }

    @Test
    fun `games are foldered normally but stay loose on a gaming workspace, Microsoft loose on work`() {
        assertThat(plan(WorkspaceKind.GENERAL).folders.map { it.name }).contains("Games")
        assertThat(plan(WorkspaceKind.GAMING).folders.map { it.name }).doesNotContain("Games")
        assertThat(plan(WorkspaceKind.WORK).folders.map { it.name }).doesNotContain("Microsoft")
    }

    @Test
    fun `folders are few, sensibly sized and never hold messaging or browsers`() {
        for (k in WorkspaceKind.values()) {
            val p = plan(k)
            assertThat(p.folders.size).isAtMost(DefaultWorkspaceLayout.MAX_FOLDERS)
            p.folders.forEach { assertThat(it.apps.size).isIn(3..DefaultWorkspaceLayout.MAX_FOLDER_APPS) }
            assertThat(p.folders.flatMap { it.apps }.map { it.packageName }).containsNoneOf(
                "com.whatsapp", "org.telegram.messenger", "com.google.android.gm", "com.android.chrome", "com.discord",
            )
        }
    }

    @Test
    fun `no overlap, inside the grid, bottom row free, at most two pages`() {
        for (k in WorkspaceKind.values()) {
            val all = allCells(plan(k))
            assertThat(all).containsNoDuplicates()
            assertThat(all.all { (_, x, y) -> x in 0 until 4 && y in 0 until 5 }).isTrue()
            assertThat(all.maxOf { it.first }).isAtMost(DefaultWorkspaceLayout.MAX_AUTO_PAGES - 1)
        }
    }

    @Test
    fun `result does not depend on the order apps arrive in`() {
        assertThat(plan(list = apps.shuffled(Random(3)))).isEqualTo(plan())
    }

    @Test
    fun `no apps, missing dock app and degenerate grids are safe`() {
        val none = plan(list = emptyList(), dockApps = emptyList())
        assertThat(none.home).hasSize(1)
        assertThat(none.folders).isEmpty()
        assertThat(plan(dockApps = listOf(app("com.not.installed"))).dock).isEmpty()
        assertThat(allCells(plan(columns = 3, rows = 1))).containsNoDuplicates()
        val bad = DefaultWorkspaceLayout.plan(apps, dock, 0, 0, -1, 0)
        assertThat(bad.dock).isEmpty()
        assertThat(allCells(bad)).containsNoDuplicates()
        assertThat(allCells(plan(columns = 5, rows = 8))).containsNoDuplicates()
    }

    @Test
    fun `workspace kind is read from the name`() {
        assertThat(WorkspaceKind.fromName("Work")).isEqualTo(WorkspaceKind.WORK)
        assertThat(WorkspaceKind.fromName("Gaming")).isEqualTo(WorkspaceKind.GAMING)
        assertThat(WorkspaceKind.fromName("Personal")).isEqualTo(WorkspaceKind.PERSONAL)
        assertThat(WorkspaceKind.fromName("User 2")).isEqualTo(WorkspaceKind.GENERAL)
        assertThat(WorkspaceKind.fromName("Team meeting")).isEqualTo(WorkspaceKind.GENERAL)
    }
}
