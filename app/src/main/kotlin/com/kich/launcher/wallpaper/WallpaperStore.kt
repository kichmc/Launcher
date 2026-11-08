package com.kich.launcher.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val file = File(appContext.filesDir, FILE_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The target the user last applied (or [WallpaperTarget.BOTH] before any). */
    var lastTarget: WallpaperTarget
        get() = WallpaperTarget.fromKey(prefs.getString(KEY_TARGET, null))
        set(value) = prefs.edit().putString(KEY_TARGET, value.key).apply()

    /** Whether the user applied the remembered picture at least once. */
    val hasApplied: Boolean get() = prefs.getLong(KEY_APPLIED_AT, 0L) > 0L

    /** The remembered picture, decoded; null when none was picked or the copy is unreadable. */
    fun loadSelected(): Bitmap? {
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /**
     * Reads the picture at [uri], scaled so its long side is about [maxSide] pixels (keeps memory
     * low on small devices), stores it as the remembered selection and returns it.
     */
    fun saveFromUri(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scaled = if (maxOf(decoded.width, decoded.height) > maxSide * 3 / 2) {
            val ratio = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt(), (decoded.height * ratio).toInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        prefs.edit().remove(KEY_APPLIED_AT).apply()
        return scaled
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
        prefs.edit().putLong(KEY_APPLIED_AT, System.currentTimeMillis()).apply()
        true
    }.getOrDefault(false)

    private companion object {
        const val FILE_NAME = "wallpaper_selected.jpg"
        const val PREFS = "kich_wallpaper"
        const val KEY_TARGET = "target"
        const val KEY_APPLIED_AT = "applied_at"
    }
}
