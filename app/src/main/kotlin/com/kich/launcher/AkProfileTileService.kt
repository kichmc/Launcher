package com.kich.launcher

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings shortcut that opens the AK workspace selector directly. */
class AkProfileTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "AK Users"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SettingsActivity.EXTRA_OPEN_AK_PROFILES, true)
        }
        startActivityAndCollapse(intent)
    }
}
