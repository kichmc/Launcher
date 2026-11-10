package com.kich.launcher.users

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A launcher profile. It does not create an isolated Android account or private app-data space. */
data class AkUser(
    val id: String,
    val name: String,
    val profileFile: String? = null,
)

data class AkProfileSettings(
    val wallpaperUri: String? = null,
    val hiddenApps: Set<String> = emptySet(),
    val autoPlaceApps: Boolean = true,
    val customProfileLockEnabled: Boolean = false,
    val theme: String = "system",
    val iconSize: Int = 48,
    val columns: Int = 4,
    val rows: Int = 5,
    val appPlacementMode: String = "all_except_hidden",
)

/** Stores one portable JSON .akprofile file per AK user (system/One UI is not an AK profile). */
object AkUsers {
    const val MAX_AK_PROFILES = 5
    private const val PREFS = "ak_user_profiles"
    private const val KEY = "users"
    private const val ACTIVE_KEY = "active_user_id"
    private const val SYSTEM_ID = "system"
    private const val PROFILE_VERSION = 1

    private fun directory(context: Context): File = File(context.filesDir, "profiles").apply { mkdirs() }
    private fun profileFile(context: Context, id: String) = File(directory(context), "$id.akprofile")

    fun all(context: Context): List<AkUser> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        val profiles = raw?.split('\n')?.mapNotNull { row ->
            val parts = row.split('|', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                val file = profileFile(context, parts[0])
                if (!file.exists()) writeProfile(context, parts[0], parts[1], AkProfileSettings())
                AkUser(parts[0], parts[1], file.name)
            } else null
        }.orEmpty().filter { it.id != SYSTEM_ID }
        return listOf(AkUser(SYSTEM_ID, "One UI")) + profiles
    }

    fun add(context: Context, name: String): Boolean {
        val clean = name.trim().take(28)
        if (clean.isEmpty()) return false
        val current = all(context).filter { it.id != SYSTEM_ID }
        if (current.size >= MAX_AK_PROFILES) return false
        val id = UUID.randomUUID().toString()
        if (!writeProfile(context, id, clean, AkProfileSettings())) return false
        saveIndex(context, current + AkUser(id, clean, "$id.akprofile"))
        return true
    }

    fun delete(context: Context, id: String): Boolean {
        if (id == SYSTEM_ID) return false
        val current = all(context).filterNot { it.id == id }
        profileFile(context, id).delete()
        saveIndex(context, current)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(ACTIVE_KEY, SYSTEM_ID) == id) prefs.edit().putString(ACTIVE_KEY, SYSTEM_ID).apply()
        return true
    }

    fun rename(context: Context, id: String, name: String): Boolean {
        val clean = name.trim().take(28)
        if (id == SYSTEM_ID || clean.isEmpty()) return false
        val settings = settings(context, id)
        if (!writeProfile(context, id, clean, settings)) return false
        saveIndex(context, all(context).filter { it.id != SYSTEM_ID }.map { if (it.id == id) it.copy(name = clean) else it })
        return true
    }

    fun settings(context: Context, id: String): AkProfileSettings {
        if (id == SYSTEM_ID) return AkProfileSettings()
        val file = profileFile(context, id)
        if (!file.exists()) return AkProfileSettings()
        return runCatching {
            val json = JSONObject(file.readText())
            val hidden = json.optJSONArray("hiddenApps") ?: JSONArray()
            AkProfileSettings(
                wallpaperUri = json.optString("wallpaperUri").takeIf { it.isNotBlank() && it != "null" },
                hiddenApps = buildSet { for (i in 0 until hidden.length()) hidden.optString(i).takeIf { it.isNotBlank() }?.let(::add) },
                autoPlaceApps = json.optBoolean("autoPlaceApps", true),
                customProfileLockEnabled = json.optBoolean("customProfileLockEnabled", false),
                theme = json.optString("theme", "system"),
                iconSize = json.optInt("iconSize", 48).coerceIn(24, 96),
                columns = json.optInt("columns", 4).coerceIn(3, 8),
                rows = json.optInt("rows", 5).coerceIn(3, 10),
                appPlacementMode = json.optString("appPlacementMode", "all_except_hidden"),
            )
        }.getOrDefault(AkProfileSettings())
    }

    fun updateSettings(context: Context, id: String, settings: AkProfileSettings): Boolean {
        val user = all(context).firstOrNull { it.id == id } ?: return false
        if (id == SYSTEM_ID) return false
        return writeProfile(context, id, user.name, settings)
    }

    fun activeId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(ACTIVE_KEY, SYSTEM_ID) ?: SYSTEM_ID

    fun setActive(context: Context, id: String) {
        if (all(context).any { it.id == id }) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_KEY, id).apply()
    }

    private fun saveIndex(context: Context, users: List<AkUser>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, users.joinToString("\n") { "${it.id}|${it.name.replace('|', ' ')}" }).apply()
    }

    private fun writeProfile(context: Context, id: String, name: String, settings: AkProfileSettings): Boolean = runCatching {
        val json = JSONObject().apply {
            put("format", "akprofile")
            put("version", PROFILE_VERSION)
            put("id", id)
            put("name", name)
            put("wallpaperUri", settings.wallpaperUri)
            put("hiddenApps", JSONArray(settings.hiddenApps.toList()))
            put("autoPlaceApps", settings.autoPlaceApps)
            put("customProfileLockEnabled", settings.customProfileLockEnabled)
            put("theme", settings.theme)
            put("iconSize", settings.iconSize.coerceIn(24, 96))
            put("columns", settings.columns.coerceIn(3, 8))
            put("rows", settings.rows.coerceIn(3, 10))
            put("appPlacementMode", settings.appPlacementMode)
        }
        val target = profileFile(context, id)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(json.toString(2))
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
    }.isSuccess
}
