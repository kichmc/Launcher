package com.kich.launcher.data

import com.kich.launcher.data.AppCategory.*

/** What kind of thing an app is, for first-time home placement. Pure data, no Android types. */
enum class AppCategory { COMMUNICATION, SOCIAL, CAMERA_PHOTOS, BROWSER, PRODUCTIVITY, MEDIA, GAMES, UTILITIES, SHOPPING_SERVICES, OTHER }

/**
 * The flavour of a new workspace, which decides what its first home screen favours. AK stores no
 * workspace type, only a name, so the kind is read from the name the user typed at creation
 * ([fromName]); anything unrecognised (including the default "User N") is [GENERAL].
 */
enum class WorkspaceKind {
    GENERAL, PERSONAL, WORK, GAMING;

    companion object {
        fun fromName(name: String): WorkspaceKind {
            val n = name.lowercase()
            fun has(vararg words: String) = words.any { it in n }
            return when {
                has("work", "office", "job", "business", "study", "school") -> WORK
                has("gam", "play") -> GAMING
                has("personal", "private", "family", "home") -> PERSONAL
                else -> GENERAL
            }
        }
    }
}

/**
 * Classifies an app by package name. There was no categorisation in AK, so this is a small, ordered
 * table of well-known packages (order inside a category = how early the app is placed), then the
 * category the app declares itself (`ApplicationInfo.category`, passed in as an int so this stays pure),
 * then a few package-name hints. Unknown apps are [AppCategory.OTHER] and are left in the drawer.
 */
object AppCategorizer {

    private val known: List<Pair<String, AppCategory>> = listOf(
        // Communication
        "com.google.android.dialer" to AppCategory.COMMUNICATION,
        "com.samsung.android.dialer" to AppCategory.COMMUNICATION,
        "com.android.dialer" to AppCategory.COMMUNICATION,
        "com.google.android.apps.messaging" to AppCategory.COMMUNICATION,
        "com.samsung.android.messaging" to AppCategory.COMMUNICATION,
        "com.android.mms" to AppCategory.COMMUNICATION,
        "com.whatsapp" to AppCategory.COMMUNICATION,
        "org.telegram" to AppCategory.COMMUNICATION,
        "org.thoughtcrime.securesms" to AppCategory.COMMUNICATION,
        "com.google.android.gm" to AppCategory.COMMUNICATION,
        "com.microsoft.office.outlook" to AppCategory.COMMUNICATION,
        "com.facebook.orca" to AppCategory.COMMUNICATION,
        "com.discord" to AppCategory.COMMUNICATION,
        "com.google.android.apps.tachyon" to AppCategory.COMMUNICATION,
        "com.google.android.contacts" to AppCategory.COMMUNICATION,
        "com.samsung.android.app.contacts" to AppCategory.COMMUNICATION,
        "com.android.contacts" to AppCategory.COMMUNICATION,
        "us.zoom" to AppCategory.COMMUNICATION,
        "com.skype" to AppCategory.COMMUNICATION,
        // Camera / photos
        "com.sec.android.app.camera" to AppCategory.CAMERA_PHOTOS,
        "com.google.android.GoogleCamera" to AppCategory.CAMERA_PHOTOS,
        "com.android.camera" to AppCategory.CAMERA_PHOTOS,
        "com.sec.android.gallery3d" to AppCategory.CAMERA_PHOTOS,
        "com.google.android.apps.photos" to AppCategory.CAMERA_PHOTOS,
        // Browsers
        "com.android.chrome" to AppCategory.BROWSER,
        "com.sec.android.app.sbrowser" to AppCategory.BROWSER,
        "org.mozilla.firefox" to AppCategory.BROWSER,
        "com.brave.browser" to AppCategory.BROWSER,
        "com.microsoft.emmx" to AppCategory.BROWSER,
        "com.duckduckgo.mobile.android" to AppCategory.BROWSER,
        "com.opera" to AppCategory.BROWSER,
        // Social
        "com.instagram" to AppCategory.SOCIAL,
        "com.facebook.katana" to AppCategory.SOCIAL,
        "com.twitter.android" to AppCategory.SOCIAL,
        "com.snapchat" to AppCategory.SOCIAL,
        "com.zhiliaoapp.musically" to AppCategory.SOCIAL,
        "com.reddit" to AppCategory.SOCIAL,
        "com.linkedin" to AppCategory.SOCIAL,
        "com.pinterest" to AppCategory.SOCIAL,
        // Productivity
        "com.google.android.calendar" to AppCategory.PRODUCTIVITY,
        "com.samsung.android.calendar" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.docs" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office" to AppCategory.PRODUCTIVITY,
        "com.microsoft.teams" to AppCategory.PRODUCTIVITY,
        "com.slack" to AppCategory.PRODUCTIVITY,
        "com.google.android.keep" to AppCategory.PRODUCTIVITY,
        "com.samsung.android.app.notes" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.tasks" to AppCategory.PRODUCTIVITY,
        "com.notion" to AppCategory.PRODUCTIVITY,
        "com.dropbox" to AppCategory.PRODUCTIVITY,
        "com.adobe.reader" to AppCategory.PRODUCTIVITY,
        "com.todoist" to AppCategory.PRODUCTIVITY,
        // Media
        "com.google.android.youtube" to AppCategory.MEDIA,
        "com.spotify" to AppCategory.MEDIA,
        "com.netflix" to AppCategory.MEDIA,
        "com.amazon.avod" to AppCategory.MEDIA,
        "com.google.android.apps.youtube.music" to AppCategory.MEDIA,
        "com.google.android.videos" to AppCategory.MEDIA,
        "com.sec.android.app.music" to AppCategory.MEDIA,
        "in.startv.hotstar" to AppCategory.MEDIA,
        // Utilities
        "com.android.vending" to AppCategory.UTILITIES,
        "com.google.android.apps.maps" to AppCategory.UTILITIES,
        "com.google.android.deskclock" to AppCategory.UTILITIES,
        "com.sec.android.app.clockpackage" to AppCategory.UTILITIES,
        "com.google.android.calculator" to AppCategory.UTILITIES,
        "com.sec.android.app.popupcalculator" to AppCategory.UTILITIES,
        "com.google.android.apps.nbu.files" to AppCategory.UTILITIES,
        "com.sec.android.app.myfiles" to AppCategory.UTILITIES,
        "com.sec.android.daemonapp" to AppCategory.UTILITIES,
        "com.android.settings" to AppCategory.UTILITIES,
        // Shopping and services
        "com.amazon.mShop" to AppCategory.SHOPPING_SERVICES,
        "in.amazon.mShop" to AppCategory.SHOPPING_SERVICES,
        "com.flipkart" to AppCategory.SHOPPING_SERVICES,
        "com.ebay" to AppCategory.SHOPPING_SERVICES,
        "com.google.android.apps.walletnfcrel" to AppCategory.SHOPPING_SERVICES,
        "com.google.android.apps.nbu.paisa.user" to AppCategory.SHOPPING_SERVICES,
        "com.phonepe" to AppCategory.SHOPPING_SERVICES,
        "net.one97.paytm" to AppCategory.SHOPPING_SERVICES,
        "com.ubercab" to AppCategory.SHOPPING_SERVICES,
        "com.application.zomato" to AppCategory.SHOPPING_SERVICES,
        "in.swiggy" to AppCategory.SHOPPING_SERVICES,
    )

    /** Position of the package's rule (smaller = placed earlier within its category), or [UNRANKED]. */
    fun ruleIndex(packageName: String): Int {
        val i = known.indexOfFirst { packageName == it.first || packageName.startsWith(it.first + ".") }
        return if (i < 0) UNRANKED else i
    }

    const val UNRANKED = 9_999

    /** @param systemCategory `ApplicationInfo.category` (-1 = undefined / unknown). */
    fun categoryOf(packageName: String, systemCategory: Int = -1): AppCategory {
        known.firstOrNull { packageName == it.first || packageName.startsWith(it.first + ".") }?.let { return it.second }
        when (systemCategory) {
            0 -> return AppCategory.GAMES              // CATEGORY_GAME
            1, 2, 5 -> return AppCategory.MEDIA        // AUDIO, VIDEO, NEWS
            3 -> return AppCategory.CAMERA_PHOTOS      // IMAGE
            4 -> return AppCategory.SOCIAL             // SOCIAL
            6 -> return AppCategory.UTILITIES          // MAPS
            7 -> return AppCategory.PRODUCTIVITY       // PRODUCTIVITY
        }
        val p = packageName.lowercase()
        fun has(vararg s: String) = s.any { it in p }
        return when {
            has("camera", "gallery") -> AppCategory.CAMERA_PHOTOS
            has("dialer", "contacts", "messag", ".mms") -> AppCategory.COMMUNICATION
            has("browser") -> AppCategory.BROWSER
            has("calendar", "notes", "office", "docs") -> AppCategory.PRODUCTIVITY
            has("music", "video", "player", "radio") -> AppCategory.MEDIA
            has("calculator", "clock", "myfiles", "weather", "recorder") -> AppCategory.UTILITIES
            has(".game", "games.") -> AppCategory.GAMES
            else -> AppCategory.OTHER
        }
    }

    /** Category order per workspace kind: earlier = placed first, on the first page. */
    fun priority(kind: WorkspaceKind): List<AppCategory> =
        when (kind) {
            WorkspaceKind.GENERAL -> listOf(COMMUNICATION, BROWSER, CAMERA_PHOTOS, SOCIAL, MEDIA, UTILITIES, SHOPPING_SERVICES, PRODUCTIVITY, GAMES, OTHER)
            WorkspaceKind.PERSONAL -> listOf(COMMUNICATION, SOCIAL, CAMERA_PHOTOS, MEDIA, BROWSER, UTILITIES, SHOPPING_SERVICES, PRODUCTIVITY, GAMES, OTHER)
            WorkspaceKind.WORK -> listOf(PRODUCTIVITY, COMMUNICATION, UTILITIES, BROWSER, CAMERA_PHOTOS, SHOPPING_SERVICES, MEDIA, SOCIAL, GAMES, OTHER)
            WorkspaceKind.GAMING -> listOf(GAMES, COMMUNICATION, SOCIAL, MEDIA, UTILITIES, BROWSER, CAMERA_PHOTOS, SHOPPING_SERVICES, PRODUCTIVITY, OTHER)
        }
}
