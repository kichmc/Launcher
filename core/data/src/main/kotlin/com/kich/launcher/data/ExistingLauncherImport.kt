package com.kich.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Asks the phone's other home app (launcher) for its layout through the one documented route Android has:
 * the Launcher3 `LauncherProvider` API (source.android.com, "Import and export home screen data"),
 * `contentResolver.call(content://<launcher package>.settings, "EXPORT_LAYOUT_XML")`.
 *
 * Nothing here reads another app's files or database, and nothing is forced: the call works only for a
 * launcher that implements this API AND lets AK use it (the provider's permission is enforced by Android).
 * Stock Launcher3-based launchers may; most OEM launchers do not. Every refusal is a normal outcome:
 * [export] then returns [Result.Unavailable] with the reason, and AK generates its own layout.
 */
object ExistingLauncherImport {

    sealed interface Result {
        data class Exported(val launcherPackage: String, val xml: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    private const val METHOD_EXPORT_LAYOUT_XML = "EXPORT_LAYOUT_XML"
    private const val KEY_LAYOUT = "KEY_LAYOUT"

    /** Blocking (binder calls): run it off the main thread. */
    fun export(context: Context): Result {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = runCatching { pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        val launchers = runCatching { pm.queryIntentActivities(home, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName }
            // The default launcher at install time first, then preinstalled ones, then the rest.
            .sortedWith(compareBy({ it.packageName != current }, { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0 }, { it.packageName }))
            .map { it.packageName }
            .distinct()
        if (launchers.isEmpty()) return Result.Unavailable("no other launcher is installed")

        val reasons = ArrayList<String>()
        for (pkg in launchers) {
            val authority = "$pkg.settings"
            if (runCatching { pm.resolveContentProvider(authority, 0) }.getOrNull() == null) {
                reasons += "$pkg has no layout provider"
                continue
            }
            val xml = try {
                context.contentResolver.call(Uri.parse("content://$authority"), METHOD_EXPORT_LAYOUT_XML, null, null)
                    ?.getString(KEY_LAYOUT)
            } catch (e: Exception) {
                // SecurityException (permission not granted to AK), unsupported method, dead provider…
                reasons += "$pkg refused (${e.javaClass.simpleName})"
                continue
            }
            if (xml.isNullOrBlank()) { reasons += "$pkg returned no layout"; continue }
            return Result.Exported(pkg, xml)
        }
        return Result.Unavailable(reasons.joinToString("; "))
    }
}
