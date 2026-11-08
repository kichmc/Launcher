package com.kich.launcher.motivation

import android.content.Context
import org.json.JSONArray

/**
 * Quotes, the user's own quotes, favorites and the change interval. Everything lives in one small
 * SharedPreferences file; the shown quote is computed from the clock, so nothing runs in the
 * background to change it.
 */
class MotivationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 60)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var favoritesOnly: Boolean
        get() = prefs.getBoolean(KEY_FAV_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_FAV_ONLY, value).apply()

    // ---- Widget look (theme, colours, shape, font, size, opacity) ----

    var themeId: String
        get() = prefs.getString(KEY_THEME, null)?.takeIf { MotivationStyle.isKnownTheme(it) }
            ?: MotivationStyle.DEFAULT_THEME
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var customFill: Int
        get() = prefs.getInt(KEY_CUSTOM_FILL, MotivationStyle.DEFAULT_CUSTOM_FILL)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_FILL, value or 0xFF000000.toInt()).apply()

    var customAccent: Int
        get() = prefs.getInt(KEY_CUSTOM_ACCENT, MotivationStyle.DEFAULT_CUSTOM_ACCENT)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_ACCENT, value or 0xFF000000.toInt()).apply()

    /** 0 sharp, 1 soft, 2 round. */
    var corner: Int
        get() = prefs.getInt(KEY_CORNER, 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_CORNER, value.coerceIn(0, 2)).apply()

    /** 0 serif italic, 1 sans, 2 mono. */
    var font: Int
        get() = prefs.getInt(KEY_FONT, 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_FONT, value.coerceIn(0, 2)).apply()

    /** 0 small, 1 medium, 2 large: the biggest size the quote may use; it still shrinks to fit. */
    var textScale: Int
        get() = prefs.getInt(KEY_TEXT_SCALE, 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_TEXT_SCALE, value.coerceIn(0, 2)).apply()

    /** 0 automatic (by background brightness), 1 light text, 2 dark text. */
    var textMode: Int
        get() = prefs.getInt(KEY_TEXT_MODE, 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_TEXT_MODE, value.coerceIn(0, 2)).apply()

    /** Background opacity in percent (30..100). */
    var opacity: Int
        get() = prefs.getInt(KEY_OPACITY, 95).coerceIn(30, 100)
        set(value) = prefs.edit().putInt(KEY_OPACITY, value.coerceIn(30, 100)).apply()

    var showLabel: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LABEL, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LABEL, value).apply()

    fun resetStyle() {
        prefs.edit()
            .remove(KEY_THEME).remove(KEY_CUSTOM_FILL).remove(KEY_CUSTOM_ACCENT).remove(KEY_CORNER)
            .remove(KEY_FONT).remove(KEY_TEXT_SCALE).remove(KEY_TEXT_MODE).remove(KEY_OPACITY)
            .remove(KEY_SHOW_LABEL)
            .apply()
    }

    private fun styleJson(): org.json.JSONObject = org.json.JSONObject()
        .put("theme", themeId)
        .put("customFill", customFill)
        .put("customAccent", customAccent)
        .put("corner", corner)
        .put("font", font)
        .put("textScale", textScale)
        .put("textMode", textMode)
        .put("opacity", opacity)
        .put("showLabel", showLabel)

    /** Old backups have no "style" block; every value falls back to what is set now. */
    private fun applyStyleJson(obj: org.json.JSONObject) {
        val theme = obj.optString("theme", themeId)
        themeId = if (MotivationStyle.isKnownTheme(theme)) theme else MotivationStyle.DEFAULT_THEME
        customFill = obj.optInt("customFill", customFill)
        customAccent = obj.optInt("customAccent", customAccent)
        corner = obj.optInt("corner", corner)
        font = obj.optInt("font", font)
        textScale = obj.optInt("textScale", textScale)
        textMode = obj.optInt("textMode", textMode)
        opacity = obj.optInt("opacity", opacity)
        showLabel = obj.optBoolean("showLabel", showLabel)
    }

    /** Bumped by "new quote"; shifts the shown quote by one position. */
    private var offset: Int
        get() = prefs.getInt(KEY_OFFSET, 0)
        set(value) = prefs.edit().putInt(KEY_OFFSET, value).apply()

    fun customQuotes(): List<String> = readList(KEY_CUSTOM)
    fun favorites(): List<String> = readList(KEY_FAVORITES)

    fun addCustom(text: String): Boolean {
        val quote = text.trim().take(MAX_LENGTH)
        val current = customQuotes()
        if (quote.isEmpty() || quote in current || quote in BUILT_IN) return false
        writeList(KEY_CUSTOM, current + quote)
        return true
    }

    fun removeCustom(text: String) {
        writeList(KEY_CUSTOM, customQuotes() - text)
        writeList(KEY_FAVORITES, favorites() - text)
    }

    fun isFavorite(text: String): Boolean = text in favorites()

    fun toggleFavorite(text: String) {
        val current = favorites()
        writeList(KEY_FAVORITES, if (text in current) current - text else current + text)
    }

    fun removeFavorite(text: String) = writeList(KEY_FAVORITES, favorites() - text)

    /** Moves on to the next quote right now. */
    fun refresh() {
        offset = (offset + 1) % 1_000_000
    }

    /** The quote that is due now: it changes once per [intervalMinutes]. */
    fun current(now: Long = System.currentTimeMillis()): String {
        val all = (BUILT_IN + customQuotes()).distinct()
        val pool = if (favoritesOnly) favorites().filter { it in all }.ifEmpty { all } else all
        val bucket = now / (intervalMinutes.coerceAtLeast(1) * 60_000L)
        val seed = (bucket * 2_654_435_761L) ushr 16
        return pool[Math.floorMod(seed + offset, pool.size.toLong()).toInt()]
    }

    /** Everything the user set (interval, favorites-only, own quotes, favorites) as one JSON text. */
    fun exportJson(): String = org.json.JSONObject()
        .put("interval", intervalMinutes)
        .put("favoritesOnly", favoritesOnly)
        .put("custom", JSONArray(customQuotes()))
        .put("favorites", JSONArray(favorites()))
        .put("style", styleJson())
        .toString()

    /** Restores [json] from [exportJson]; false (and nothing changed) when it is not valid. */
    fun importJson(json: String): Boolean = runCatching {
        val obj = org.json.JSONObject(json)
        val interval = obj.getInt("interval")
        require(interval in INTERVALS)
        val custom = obj.getJSONArray("custom").let { a -> List(a.length()) { a.getString(it).trim().take(MAX_LENGTH) } }
        val favs = obj.getJSONArray("favorites").let { a -> List(a.length()) { a.getString(it).trim().take(MAX_LENGTH) } }
        intervalMinutes = interval
        favoritesOnly = obj.getBoolean("favoritesOnly")
        writeList(KEY_CUSTOM, custom.filter { it.isNotEmpty() }.distinct())
        writeList(KEY_FAVORITES, favs.filter { it.isNotEmpty() }.distinct())
        obj.optJSONObject("style")?.let { applyStyleJson(it) }
        true
    }.getOrDefault(false)

    private fun readList(key: String): List<String> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeList(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    companion object {
        private const val PREFS = "kich_motivation"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_FAV_ONLY = "favorites_only"
        private const val KEY_OFFSET = "offset"
        private const val KEY_CUSTOM = "custom"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_THEME = "style_theme"
        private const val KEY_CUSTOM_FILL = "style_custom_fill"
        private const val KEY_CUSTOM_ACCENT = "style_custom_accent"
        private const val KEY_CORNER = "style_corner"
        private const val KEY_FONT = "style_font"
        private const val KEY_TEXT_SCALE = "style_text_scale"
        private const val KEY_TEXT_MODE = "style_text_mode"
        private const val KEY_OPACITY = "style_opacity"
        private const val KEY_SHOW_LABEL = "style_show_label"
        const val MAX_LENGTH = 200

        /** Intervals offered in settings; the system updates widgets at most every 30 minutes. */
        val INTERVALS = listOf(30, 60, 180, 360, 1440)

        val BUILT_IN = listOf(
            "Your time is valuable. Use it wisely.",
            "Small progress is still progress.",
            "Build something today that your future self will appreciate.",
            "Start with the smallest next step.",
            "Done is better than perfect.",
            "Focus on one thing at a time.",
            "Protect your attention. It is your most limited resource.",
            "Plan the day, then do the first task before checking anything else.",
            "Consistency beats intensity.",
            "You do not need more time. You need a clearer priority.",
            "Break big tasks into pieces you can finish in twenty-five minutes.",
            "Rest is part of the work, not a reward for it.",
            "What you do every day matters more than what you do once.",
            "Say no to good ideas so you can say yes to the best one.",
            "Finish what you started before starting something new.",
            "Ten focused minutes beat an hour of distraction.",
            "Make it work, then make it better.",
            "A rough draft is the first step to a finished project.",
            "Your future self is counting on what you do now.",
            "Write down tomorrow's top three tasks before you stop today.",
            "Momentum starts with one small action.",
            "Slow progress is still moving forward.",
            "Do the hard thing while your energy is high.",
            "Clear your desk, clear your mind, begin.",
            "Every expert was once a beginner who kept going.",
            "Time spent planning saves time spent fixing.",
            "Turn off one notification and notice the difference.",
            "Learn a little every day and it adds up.",
            "Ship it, learn from it, improve it.",
            "The best time to start was earlier. The next best time is now.",
        )
    }
}
