package com.kich.launcher.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/** Which screens a wallpaper is applied to. */
enum class WallpaperTarget(val key: String, val flags: Int) {
    HOME("home", WallpaperManager.FLAG_SYSTEM),
    LOCK("lock", WallpaperManager.FLAG_LOCK),
    BOTH("both", WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
    ;

    companion object {
        fun fromKey(key: String?): WallpaperTarget = entries.firstOrNull { it.key == key } ?: BOTH
    }
}

/**
 * Keeps the wallpaper the user picked (a downscaled private copy, so it survives the picker's
 * URI grant expiring) and applies it through the platform [WallpaperManager]. Blocking calls:
 * run them off the main thread.
 */
class WallpaperStore(context: Context) {
    private val appContext = context.applicationContext
    private val profileId: String get() = appContext.getSharedPreferences("ak_workspace_profiles", Context.MODE_PRIVATE)
        .getString("active", "user1")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) } ?: "user1"
    private fun selectedFile() = File(appContext.filesDir, "wallpaper_selected_${profileId}.jpg")
    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun profileKey(key: String) = "${key}_${profileId}"

    /** The target the user last applied (or [WallpaperTarget.BOTH] before any). */
    var lastTarget: WallpaperTarget
        get() = WallpaperTarget.fromKey(prefs().getString(profileKey(KEY_TARGET), null))
        set(value) = prefs().edit().putString(profileKey(KEY_TARGET), value.key).apply()

    /** Whether the user applied the remembered picture at least once. */
    val hasApplied: Boolean get() = prefs().getLong(profileKey(KEY_APPLIED_AT), 0L) > 0L

    /** The remembered picture, decoded; null when none was picked or the copy is unreadable. */
    fun loadSelected(): Bitmap? {
        val file = selectedFile()
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** Why the last [saveFromUri] failed, for showing to the user; null when it succeeded. */
    var lastError: String? = null
        private set

    /**
     * Reads the picture at [uri], scaled so its long side is about [maxSide] pixels (keeps memory
     * low on small devices), stores it as the remembered selection and returns it.
     * Uses the platform ImageDecoder (handles JPEG, PNG, WebP, HEIC, EXIF rotation, one-shot
     * streams) and falls back to BitmapFactory.
     */
    fun saveFromUri(uri: Uri, maxSide: Int): Bitmap? {
        lastError = null
        val bitmap = decodeWithImageDecoder(uri, maxSide) ?: decodeWithBitmapFactory(uri, maxSide)
        if (bitmap == null) {
            if (lastError == null) lastError = "unsupported or unreadable image"
            return null
        }
        return runCatching {
            val written = selectedFile().outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            check(written) { "JPEG encoder failed" }
            prefs().edit().remove(profileKey(KEY_APPLIED_AT)).apply()
            bitmap
        }.getOrElse {
            lastError = "could not save a copy: ${it.javaClass.simpleName}"
            null
        }
    }

    private fun decodeWithImageDecoder(uri: Uri, maxSide: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(appContext.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > maxSide) {
                val ratio = maxSide.toFloat() / longSide
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * ratio).toInt()),
                    maxOf(1, (info.size.height * ratio).toInt()),
                )
            }
        }
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(80)}"
        null
    }

    private fun decodeWithBitmapFactory(uri: Uri, maxSide: Int): Bitmap? = try {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide > 0 && longSide / (sample * 2) >= maxSide) sample *= 2
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(80)}"
        null
    }

    /**
     * Saves [bitmap] as the launcher's OWN wallpaper: drawn behind the home screen by Kich
     * Launcher itself. The phone's system (One UI) wallpaper is not touched.
     */
    fun saveLauncherWallpaper(bitmap: Bitmap): Boolean = runCatching {
        val written = launcherFile(appContext, profileId).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        check(written) { "JPEG encoder failed" }
    }.isSuccess

    fun hasLauncherWallpaper(): Boolean = launcherFile(appContext, profileId).exists()

    fun clearLauncherWallpaper() {
        launcherFile(appContext, profileId).delete()
    }

    /** True when the system allows apps to change the wallpaper (managed devices may forbid it). */
    fun canSetWallpaper(): Boolean {
        val manager = WallpaperManager.getInstance(appContext)
        return manager.isWallpaperSupported && manager.isSetWallpaperAllowed
    }

    /** Applies [bitmap] to [target]; returns whether the system accepted it. */
    fun apply(bitmap: Bitmap, target: WallpaperTarget): Boolean = runCatching {
        WallpaperManager.getInstance(appContext).setBitmap(bitmap, null, true, target.flags)
        lastTarget = target
        prefs().edit().putLong(profileKey(KEY_APPLIED_AT), System.currentTimeMillis()).apply()
        true
    }.getOrDefault(false)

    companion object {
        private fun launcherFile(context: Context, profileId: String): File =
            File(context.applicationContext.filesDir, "launcher_wallpaper_${profileId}.jpg")

        private fun activeProfileId(context: Context): String = context.applicationContext
            .getSharedPreferences("ak_workspace_profiles", Context.MODE_PRIVATE)
            .getString("active", "user1")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) } ?: "user1"

        /** The launcher's own wallpaper, decoded; null when none is set. Blocking: call off the main thread. */
        fun loadLauncherWallpaper(context: Context): Bitmap? {
            val file = launcherFile(context, activeProfileId(context))
            if (!file.exists()) return null
            return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }

        private const val PREFS = "kich_wallpaper"
        private const val KEY_TARGET = "target"
        private const val KEY_APPLIED_AT = "applied_at"
    }
}
