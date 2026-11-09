package com.kich.launcher.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

private val declaredCache = ConcurrentHashMap<String, Boolean>()

/**
 * True when this app's manifest declares [permission]. A permission that is not declared can never
 * be granted (the system shows no dialog for it), so any prompt, toggle or row that exists only to
 * ask for it must be hidden: the UI follows the manifest, and removing a `<uses-permission>` line
 * there is all it takes to drop the matching UI. The manifest cannot change while the app runs, so
 * the answer is cached.
 */
fun Context.declaresPermission(permission: String): Boolean {
    val key = "$packageName|$permission"
    return declaredCache.getOrPut(key) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)
    }
}
