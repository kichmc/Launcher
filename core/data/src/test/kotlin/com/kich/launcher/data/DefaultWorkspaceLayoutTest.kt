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

    private fun app(i: Int) = AppItem(
        packageName = "pkg$i", className = "pkg$i.Main", user = Process.myUserHandle(),
        userSerial = 0L, label = "App%03d".format(i),
    )

    private val apps = (1..30).map(::app)
    private val dock = listOf(apps[4], apps[2], apps[9], apps[11], apps[20])

    private fun plan(
        list: List<AppItem> = apps, dockApps: List<AppItem> = dock,
        columns: Int = 4, rows: Int = 6, dockColumns: Int = 4, span: Int = 2,
    ) = DefaultWorkspaceLayout.plan(list, dockApps, columns, rows, dockColumns, span)

    private fun cells(r: HomeItemEntity) =
        (0 until r.spanX).flatMap { dx -> (0 until r.spanY).map { dy -> Triple(r.page, r.cellX + dx, r.cellY + dy) } }

    @Test
    fun `dock is capped, ordered and kept off the pages`() {
        val p = plan()
        assertThat(p.dock.map { it.packageName }).containsExactly("pkg5", "pkg3", "pkg10", "pkg12").inOrder()
        assertThat(p.dock.map { it.cellX }).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(p.home.filter { it.isApp }.map { it.key }).containsNoneIn(p.dock.map { it.key })
    }

    @Test
    fun `nothing overlaps and everything is inside the grid`() {
        val all = plan().home.flatMap(::cells)
        assertThat(all).containsNoDuplicates()
        assertThat(all.all { (_, x, y) -> x in 0 until 4 && y in 0 until 6 }).isTrue()
    }

    @Test
    fun `clock widget sits full width at the top and icons start below it on page 0`() {
        val home = plan().home
        val w = home.single { it.isBuiltin }
        assertThat(listOf(w.page, w.cellX, w.cellY, w.spanX, w.spanY)).containsExactly(0, 0, 0, 4, 2).inOrder()
        assertThat(home.filter { it.isApp && it.page == 0 }.minOf { it.cellY }).isEqualTo(2)
    }

    @Test
    fun `a full page continues on the next page`() {
        val apps = plan().home.filter { it.isApp }
        assertThat(apps).hasSize(26)
        assertThat(apps.count { it.page == 0 }).isEqualTo(16)
        assertThat(apps.count { it.page == 1 }).isEqualTo(10)
    }

    @Test
    fun `result does not depend on the order apps arrive in`() {
        assertThat(plan(list = apps.shuffled(Random(7)))).isEqualTo(plan())
    }

    @Test
    fun `no apps gives just the clock widget`() {
        val p = plan(list = emptyList(), dockApps = emptyList())
        assertThat(p.home).hasSize(1)
        assertThat(p.dock).isEmpty()
    }

    @Test
    fun `a dock app that is not installed is ignored and duplicates are placed once`() {
        assertThat(plan(list = listOf(app(1)), dockApps = listOf(app(99))).dock).isEmpty()
        assertThat(plan(list = apps + apps[0], dockApps = emptyList(), span = 0).home.count { it.isApp }).isEqualTo(30)
    }

    @Test
    fun `degenerate grids neither crash nor overlap`() {
        assertThat(plan(columns = 3, rows = 1, dockApps = emptyList()).home.count { it.isApp }).isEqualTo(30)
        assertThat(plan(columns = 3, rows = 2, span = 5, dockApps = emptyList()).home.single { it.isBuiltin }.spanY).isEqualTo(1)
        val bad = plan(columns = 0, rows = 0, dockColumns = -1, span = 0)
        assertThat(bad.dock).isEmpty()
        assertThat(bad.home.flatMap(::cells)).containsNoDuplicates()
    }

    @Test
    fun `never uses a page the pager does not show`() {
        val p = plan(list = (1..5000).map(::app), dockApps = emptyList(), rows = 5)
        assertThat(p.home.maxOf { it.page }).isLessThan(HomeLayoutRepository.MAX_PAGES)
    }
}
