package com.kich.launcher.lock

import androidx.activity.ComponentActivity
import com.kich.launcher.data.lock.ProfileLockManager

/**
 * Secondary screens (Settings, wallpaper, pin/backup requests…) must never show a locked profile.
 * Returns true and finishes the activity when the active profile is locked; the launcher beneath
 * then shows the lock screen. Call from onStart (and onCreate for transient request activities).
 */
fun ComponentActivity.finishIfProfileLocked(): Boolean {
    if (!ProfileLockManager.get(this).isLocked()) return false
    finish()
    return true
}
