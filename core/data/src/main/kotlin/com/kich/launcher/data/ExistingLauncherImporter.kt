package com.kich.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tries to read the home-screen arrangement of the phone's other launcher — and only through what
 * Android allows an ordinary app to do.
 *
 * Android has no API for this: nothing in the SDK (`LauncherApps`, `ShortcutManager`, …) exposes another
 * launcher's pages, dock or folders. The one mechanism that exists is a launcher's own ContentProvider
 * (Launcher3 and its derivatives publish `content://<authority>/favorites`), which is readable only by an
 * app that holds the permission the launcher guards it with (its `READ_SETTINGS`). So this class:
 *  - finds the other home apps installed (never AK itself),
 *  - looks at their exported providers and the read permission each declares,
 *  - queries one only when AK actually holds that permission (or the provider declares none),
 *  - never opens another app's database file, uses reflection or hidden APIs, or asks for root.
 *
 * On current phones the permission is usually signature- or system-level (Samsung One UI Home, Pixel
 * Launcher), so AK cannot hold it and [read] returns [Result.Unavailable] with the reason; AK then builds
 * its own default layout. A launcher that publishes a readable provider (an older or open-source
 * Launcher3 build, for example) is read and its arrangement is imported.
 */
@Singleton
class ExistingLauncherImporter @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface Result {
        data class Rows(val launcherPackage: String, val rows: List<ImportedRow>) : Result
        data class Unavailable(val reason: String) : Result
    }

    fun read(): Result {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = runCatching { pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            // The preinstalled launcher (the one the phone shipped with) first.
            .sortedByDescending { pkg ->
                runCatching { pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)
            }
        if (launchers.isEmpty()) return Result.Unavailable("No other launcher is installed.")

        val reasons = ArrayList<String>()
        for (pkg in launchers) {
            val providers = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS).providers }.getOrNull().orEmpty()
                .filter { it.exported && !it.authority.isNullOrBlank() }
                .filter { p -> p.authority.lowercase().let { "settings" in it || "launcher" in it } }
            if (providers.isEmpty()) {
                reasons += "$pkg publishes no layout provider."
                continue
            }
            for (p in providers) for (authority in p.authority.split(';')) {
                val perm = p.readPermission
                if (perm != null && context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                    reasons += "$pkg: reading its layout needs $perm (${protection(pm, perm)}), which AK does not hold."
                    continue
                }
                when (val r = query(authority.trim())) {
                    is Query.Rows -> return Result.Rows(pkg, r.rows)
                    is Query.Failed -> reasons += "$pkg: ${r.reason}"
                }
            }
        }
        return Result.Unavailable(reasons.joinToString(" ").ifBlank { "No readable launcher layout was found." })
    }

    private sealed interface Query {
        data class Rows(val rows: List<ImportedRow>) : Query
        data class Failed(val reason: String) : Query
    }

    private fun query(authority: String): Query {
        val uri = Uri.parse("content://$authority/favorites")
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                fun col(name: String) = c.getColumnIndex(name)
                val iId = col("_id"); val iType = col("itemType"); val iContainer = col("container")
                val iScreen = col("screen"); val iX = col("cellX"); val iY = col("cellY")
                val iRank = col("rank"); val iTitle = col("title"); val iIntent = col("intent")
                if (iId < 0 || iType < 0 || iContainer < 0) return Query.Failed("its table is not in the expected format.")
                val out = ArrayList<ImportedRow>()
                while (c.moveToNext() && out.size < MAX_ROWS) {
                    val component = if (iIntent >= 0) {
                        runCatching { Intent.parseUri(c.getString(iIntent).orEmpty(), 0).component }.getOrNull()
                    } else null
                    out += ImportedRow(
                        id = c.getLong(iId), itemType = c.getInt(iType), container = c.getLong(iContainer),
                        screen = if (iScreen >= 0) c.getInt(iScreen) else 0,
                        cellX = if (iX >= 0) c.getInt(iX) else 0, cellY = if (iY >= 0) c.getInt(iY) else 0,
                        rank = if (iRank >= 0) c.getInt(iRank) else 0,
                        title = if (iTitle >= 0) c.getString(iTitle) else null,
                        packageName = component?.packageName, className = component?.className,
                    )
                }
                Query.Rows(out)
            } ?: Query.Failed("it returned no data.")
        } catch (e: SecurityException) {
            Query.Failed("access was refused (${e.message?.take(80)}).")
        } catch (e: Exception) {
            Log.w(TAG, "Layout query failed for $authority", e)
            Query.Failed("the query failed (${e.javaClass.simpleName}).")
        }
    }

    private fun protection(pm: PackageManager, permission: String): String = runCatching {
        @Suppress("DEPRECATION")
        when (pm.getPermissionInfo(permission, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature-only"
            else -> "restricted"
        }
    }.getOrDefault("not defined on this phone")

    private companion object {
        const val TAG = "LauncherImport"
        const val MAX_ROWS = 3_000
    }
}
