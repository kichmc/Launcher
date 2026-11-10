package com.kich.launcher.users

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kich.launcher.LauncherActivity
import com.kich.launcher.R

/** Quick Settings entry point. The tile stays available when AK Launcher is not running. */
class AkUserTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val users = AkUsers.all(this)
        val labels = users.map { if (it.id == "system") "${it.name} · Samsung home" else it.name }.toMutableList()
        labels.add("＋ Add user / Manage users")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Switch user")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == users.size) {
                        openManager()
                    } else {
                        switchTo(users[which])
                    }
                }
                .setOnCancelListener { }
                .create()
            showDialog(dialog)
        } else {
            openManager()
        }
    }

    private fun switchTo(user: AkUser) {
        AkUsers.setActive(this, user.id)
        refreshTile()
        val intent = if (user.id == "system") {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val samsungHome = packageManager.queryIntentActivities(home, 0)
                .firstOrNull { it.activityInfo.packageName == "com.sec.android.app.launcher" }?.activityInfo
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .also { if (samsungHome != null) it.component = ComponentName(samsungHome.packageName, samsungHome.name) }
        } else {
            Intent(this, LauncherActivity::class.java)
                .putExtra("ak_profile_id", user.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(intent)
    }

    private fun openManager() {
        val intent = Intent(this, UserManagerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences("ak_user_profiles", MODE_PRIVATE)
        val activeId = prefs.getString("active_user_id", "system")
        val active = AkUsers.all(this).firstOrNull { it.id == activeId } ?: AkUsers.all(this).first()
        tile.label = "AK Users"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = active.name
        }
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher_foreground)
        tile.state = if (active.id == "system") Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
