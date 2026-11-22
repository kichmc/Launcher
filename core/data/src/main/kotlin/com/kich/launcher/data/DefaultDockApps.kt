package com.kich.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import com.kich.launcher.model.AppItem

/**
 * The default dock. First-run order (user-chosen): phone, messages, system Settings, browser, camera. Each slot resolves the device's DEFAULT handler and is silently skipped when
 * nothing resolves (e.g. a tablet without telephony), so the seed degrades gracefully.
 */
fun resolveDefaultDockKeys(context: Context, apps: List<AppItem>, settingsLast: Boolean = false): List<String> {
    val pm = context.packageManager

    /** The default handler's (package, activity); the activity tells the handler's entries apart. */
    fun defaultFor(intent: Intent): Pair<String, String?>? =
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.let { it.packageName to it.name }
            // "android" is the chooser/resolver — there is no single default app to pin.
            ?.takeIf { it.first != "android" }

    val phone = defaultFor(Intent(Intent.ACTION_DIAL))
    val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let { it to null }
    val settings = defaultFor(Intent(Settings.ACTION_SETTINGS))
    val browser = defaultFor(Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com")))
    val camera = defaultFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    // First-run order is phone, messages, Settings, browser, camera. [settingsLast] (new workspaces)
    // puts Settings after camera so a short dock keeps phone, messages, browser and camera.
    val slots = if (settingsLast) listOf(phone, sms, browser, camera, settings)
    else listOf(phone, sms, settings, browser, camera)
    return slots
        .mapNotNull { slot ->
            slot?.let { (p, activity) ->
                // The dock stores drawer-app keys; the main profile's entries of the package come
                // first (a work copy may be listed earlier: the list is sorted by label). Among them
                // a package with several launcher entries (a dialer that also holds Contacts) gives
                // the entry the intent resolved to, not the alphabetically first one.
                val entries = apps.filter { it.packageName == p }
                val own = entries.minOfOrNull { it.userSerial }
                val inProfile = entries.filter { it.userSerial == own }
                (inProfile.firstOrNull { it.className == activity } ?: inProfile.firstOrNull())?.key
            }
        }
        .distinct()
}
