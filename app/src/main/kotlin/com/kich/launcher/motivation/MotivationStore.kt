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
