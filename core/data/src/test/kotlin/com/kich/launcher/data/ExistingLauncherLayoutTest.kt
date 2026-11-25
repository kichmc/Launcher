package com.kich.launcher.data

import android.os.Process
import com.google.common.truth.Truth.assertThat
import com.kich.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExistingLauncherLayoutTest {

    private fun app(pkg: String, cls: String = "$pkg.Main") =
        AppItem(pkg, cls, Process.myUserHandle(), 0L, pkg.substringAfterLast('.'))

    private val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<workspace rows="6" columns="5">
  <autoinstall container="hotseat" rank="1" className="com.msg.Main" packageName="com.msg" />
  <autoinstall container="hotseat" rank="0" className="com.phone.Main" packageName="com.phone" />
  <autoinstall container="hotseat" rank="2" className="com.cam.Main" packageName="com.cam" />
  <autoinstall container="hotseat" rank="3" className="com.web.Main" packageName="com.web" />
  <autoinstall container="hotseat" rank="4" className="com.notes.Main" packageName="com.notes" />
  <autoinstall container="desktop" screen="0" x="0" y="0" className="com.wa.Main" packageName="com.wa" />
  <autoinstall container="desktop" screen="0" x="4" y="0" className="com.zip.Main" packageName="com.zip" />
  <autoinstall container="desktop" screen="0" x="1" y="0" className="com.notinstalled.Main" packageName="com.notinstalled" />
  <appwidget container="desktop" spanX="2" spanY="2" x="0" y="1" screen="0" className="W" packageName="com.w" />
  <folder container="desktop" x="1" y="1" screen="0" title="Samsung">
    <autoinstall className="com.shop.Main" packageName="com.shop" />
    <autoinstall className="com.wear.Main" packageName="com.wear" />
    <autoinstall className="com.gone.Main" packageName="com.gone" />
  </folder>
  <folder container="desktop" x="2" y="1" screen="0" title="Solo">
    <autoinstall className="com.solo.Main" packageName="com.solo" />
    <autoinstall className="com.gone2.Main" packageName="com.gone2" />
  </folder>
  <autoinstall container="desktop" screen="7" x="3" y="2" className="com.cal.Main" packageName="com.cal" />
</workspace>"""

    private val installed = listOf("com.msg", "com.phone", "com.cam", "com.web", "com.notes", "com.wa", "com.zip",
        "com.shop", "com.wear", "com.solo", "com.cal", "com.extra").map { app(it) }

    private fun plan() = ExistingLauncherLayout.plan(ExistingLauncherLayout.parse(xml)!!, installed, 4, 6, 4)!!

    @Test
    fun `parses grid, hotseat order and ignores widgets`() {
        val p = ExistingLauncherLayout.parse(xml)!!
        assertThat(p.columns).isEqualTo(5)
        assertThat(p.hotseat.map { it.packageName }).containsExactly("com.phone", "com.msg", "com.cam", "com.web", "com.notes").inOrder()
        assertThat(p.desktop).hasSize(6) // 3 apps, 2 folders, 1 on screen 7
    }

    @Test
    fun `dock follows the hotseat and is not repeated on the pages`() {
        val p = plan()
        assertThat(p.dock.map { it.packageName }).containsExactly("com.phone", "com.msg", "com.cam", "com.web").inOrder()
        assertThat(p.home.map { it.packageName }).containsNoneOf("com.phone", "com.msg", "com.cam", "com.web", "com.notes")
    }

    @Test
    fun `keeps cells, order, pages and folders, and skips what is not installed`() {
        val p = plan()
        val cells = p.home.associate { it.packageName to Triple(it.page, it.cellX, it.cellY) }
        assertThat(cells["com.wa"]).isEqualTo(Triple(0, 0, 0))
        assertThat(cells["com.zip"]!!.second).isIn(0..3) // x=4 does not exist in a 4-column grid
        assertThat(cells["com.cal"]).isEqualTo(Triple(1, 3, 2)) // screen 7 is the second page
        assertThat(cells.keys).containsNoneOf("com.notinstalled", "com.gone", "com.extra")
        assertThat(p.folders.single { it.name == "Samsung" }.apps.map { it.packageName }).containsExactly("com.shop", "com.wear").inOrder()
        assertThat(cells).containsKey("com.solo") // a folder left with one app is a plain icon
        assertThat(p.home.none { it.isBuiltin }).isTrue()
    }

    @Test
    fun `no overlap and everything inside the grid`() {
        val p = plan()
        val all = p.home.map { Triple(it.page, it.cellX, it.cellY) } + p.folders.map { Triple(it.page, it.cellX, it.cellY) }
        assertThat(all).containsNoDuplicates()
        assertThat(all.all { (_, x, y) -> x in 0..3 && y in 0..5 }).isTrue()
    }

    @Test
    fun `unusable exports give null so the caller can generate a layout`() {
        assertThat(ExistingLauncherLayout.plan(ExistingLauncherLayout.parse(xml)!!, listOf(app("com.other")), 4, 6, 4)).isNull()
        assertThat(ExistingLauncherLayout.parse("<!DOCTYPE x [<!ENTITY a 'b'>]><workspace/>")).isNull()
        assertThat(ExistingLauncherLayout.parse("<workspace><autoinstall")).isNull()
        assertThat(ExistingLauncherLayout.parse("<workspace rows='4' columns='4'/>")).isNull()
        assertThat(ExistingLauncherLayout.parse("  ")).isNull()
        assertThat(ExistingLauncherLayout.parse("<workspace>" + " ".repeat(ExistingLauncherLayout.MAX_XML_CHARS) + "</workspace>")).isNull()
    }

    @Test
    fun `an activity renamed since the export still resolves by package`() {
        val p = ExistingLauncherLayout.parse("<workspace><favorite container='desktop' screen='0' x='0' y='0' packageName='com.wa' className='old.Name'/></workspace>")!!
        assertThat(ExistingLauncherLayout.plan(p, installed, 4, 6, 4)!!.home.single().packageName).isEqualTo("com.wa")
    }

    @Test
    fun `more apps than one screen spill to the next page without overlap`() {
        val many = (1..30).joinToString("") { "<autoinstall container='desktop' screen='0' x='${it % 4}' y='${it / 4}' packageName='com.m$it' className='c'/>" }
        val p = ExistingLauncherLayout.plan(ExistingLauncherLayout.parse("<workspace>$many</workspace>")!!, (1..30).map { app("com.m$it", "c") }, 4, 6, 4)!!
        assertThat(p.home).hasSize(30)
        assertThat(p.home.map { Triple(it.page, it.cellX, it.cellY) }).containsNoDuplicates()
        assertThat(p.home.any { it.page == 1 }).isTrue()
    }
}
