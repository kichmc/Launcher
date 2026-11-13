package com.kich.launcher.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LauncherStyleSpecTest {
    @Test
    fun oneUiTurnsOnTheOneUiBehaviours() {
        val spec = LauncherStyleSpec.of(LauncherSettings.STYLE_ONE_UI)
        assertThat(spec.pageEditor).isTrue()
        assertThat(spec.oneUiPageDots).isTrue()
        assertThat(spec.drawerPillSearch).isTrue()
        assertThat(spec.drawerOneUiChrome).isTrue()
    }

    @Test
    fun otherStylesKeepTheStockBehaviour() {
        for (style in LauncherSettings.LAUNCHER_STYLES - LauncherSettings.STYLE_ONE_UI) {
            assertThat(LauncherStyleSpec.of(style)).isEqualTo(LauncherStyleSpec.UNIVERSAL)
        }
        assertThat(LauncherStyleSpec.of("unknown")).isEqualTo(LauncherStyleSpec.UNIVERSAL)
    }
}
