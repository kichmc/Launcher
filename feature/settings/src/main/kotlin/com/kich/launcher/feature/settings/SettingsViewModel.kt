package com.kich.launcher.feature.settings

import androidx.lifecycle.ViewModel
import com.kich.launcher.data.DrawerWallpaperStore
import com.kich.launcher.data.DrawerWallpaper
import android.net.Uri
import java.io.File
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kich.launcher.data.AppRepository
import com.kich.launcher.data.CustomIconStore
import com.kich.launcher.data.DrawerFolder
import com.kich.launcher.data.HomeLayoutRepository
import com.kich.launcher.data.IconPackInfo
import com.kich.launcher.data.IconPackRepository
import com.kich.launcher.data.SettingsRepository
import com.kich.launcher.model.AppItem
import com.kich.launcher.model.LauncherSettings
import com.kich.launcher.data.local.HomeItemEntity
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: SettingsRepository,
    private val homeLayoutRepository: HomeLayoutRepository,
    private val iconPackRepository: IconPackRepository,
    private val customIcons: CustomIconStore,
    private val appRepository: AppRepository,
    private val drawerWallpaper: DrawerWallpaperStore,
) : ViewModel() {

    data class AkWorkspaceProfile(val id: String, val name: String, val active: Boolean = false)

    private val profilePrefs by lazy { appContext.getSharedPreferences("ak_workspace_profiles", Context.MODE_PRIVATE) }
    private val _profiles = MutableStateFlow<List<AkWorkspaceProfile>>(emptyList())
    /** Shared across SettingsViewModel instances (Settings screen + Quick Settings activity). */
    private val profileMutex = AkProfileMutationLock.mutex

    val profiles = _profiles.asStateFlow()
    private val _activeProfileName = MutableStateFlow("User 1")
    val activeProfileName = _activeProfileName.asStateFlow()

    init {
        refreshProfiles()
        viewModelScope.launch {
            profileMutex.withLock {
                // Room now keeps a physical partition for every AK workspace. On an upgrade, the
                // existing shared rows are User 1's rows; other users are imported once from their
                // last snapshot. Thereafter switching only changes the active partition selector.
                val activeId = activeProfileId()
                // Before v9 all rows lived in one table; migration assigns them to User 1.
                val legacyUser1Layout = homeLayoutRepository.snapshotLayout()
                homeLayoutRepository.setActiveWorkspace(activeId)
                // The pre-partition Room table is migrated wholesale into User 1. Never replace it
                // with a potentially stale JSON snapshot, even if User 2 was active during upgrade.
                profilePrefs.edit().putBoolean("layout_partitioned_user1", true).commit()
                val partitionKey = "layout_partitioned_$activeId"
                if (!profilePrefs.getBoolean(partitionKey, false)) {
                    if (activeId != "user1") {
                        val saved = runCatching { JSONObject(profilePrefs.getString("snapshot_$activeId", "{}")) }
                            .getOrDefault(JSONObject())
                        val savedLayout = saved.optJSONArray("homeLayout")?.let(::decodeHomeLayout)
                            ?: legacyUser1Layout.filterNot { it.isWidget || it.isBuiltin || it.hasFootprint }
                        homeLayoutRepository.cloneLayoutIntoActiveWorkspace(savedLayout)
                    }
                    profilePrefs.edit().putBoolean(partitionKey, true).commit()
                }
                if (activeId == "user1" && !profilePrefs.contains("snapshot_user1")) saveProfileSnapshot("user1")
            }
        }
    }

    private fun refreshProfiles() {
        val json = runCatching { JSONArray(profilePrefs.getString("profiles", "[]")) }.getOrDefault(JSONArray())
        val activeId = profilePrefs.getString("active", "user1") ?: "user1"
        val list = buildList {
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val id = item.optString("id")
                add(AkWorkspaceProfile(id, item.optString("name", "Workspace"), id == activeId))
            }
        }.ifEmpty { listOf(AkWorkspaceProfile("user1", "User 1", true)) }
        _profiles.value = list
        _activeProfileName.value = list.firstOrNull { it.active }?.name ?: "User 1"
        if (json.length() == 0) saveProfileList(list)
    }

    private fun saveProfileList(list: List<AkWorkspaceProfile>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        profilePrefs.edit().putString("profiles", array.toString()).apply()
    }

    /** Adds every currently visible app to the home grid, preserving existing entries. */
    fun addAllVisibleAppsToHome(hiddenKeys: Set<String>) {
        viewModelScope.launch {
            val current = repository.settings.first()
            val visible = appRepository.apps.first().filterNot { it.key in hiddenKeys }
            visible.forEach { app ->
                homeLayoutRepository.addToHome(app, current.homeColumns, current.homeRows)
            }
        }
    }

    fun createAkProfile(name: String) {
        val clean = name.trim().take(24)
        if (clean.isBlank()) return
        viewModelScope.launch {
            profileMutex.withLock {
                if (_profiles.value.size >= 5) return@withLock
                val oldId = activeProfileId()
                val originalLayout = homeLayoutRepository.snapshotLayout()
                saveProfileSnapshot(oldId)

                val id = "ak_" + java.util.UUID.randomUUID().toString().take(8)
                // A new workspace starts with the current settings as a useful default, but gets
                // its own snapshot from the moment it is created.
                // Clone the app/folder arrangement, but do not duplicate bound widget IDs: Android
                // widgets are device-owned instances. The new workspace can add its own widgets.
                val clonedLayout = homeLayoutRepository.snapshotLayout().filterNot { it.isWidget || it.isBuiltin || it.hasFootprint }
                saveProfileSnapshot(id, clonedLayout)
                try {
                    homeLayoutRepository.setActiveWorkspace(id)
                    homeLayoutRepository.cloneLayoutIntoActiveWorkspace(clonedLayout)
                    profilePrefs.edit().putBoolean("layout_partitioned_$id", true).commit()
                    val updated = _profiles.value.map { it.copy(active = false) } +
                        AkWorkspaceProfile(id, clean, true)
                    saveProfileList(updated)
                    check(withContext(Dispatchers.IO) { profilePrefs.edit().putString("active", id).commit() })
                    drawerWallpaper.reloadForActiveProfile()
                    refreshMotivationWidget()
                    refreshProfiles()
                } catch (_: Exception) {
                    runCatching { homeLayoutRepository.setActiveWorkspace(oldId) }
                    runCatching { homeLayoutRepository.restoreLayout(originalLayout) }
                    profilePrefs.edit().putString("active", oldId)
                        .remove("layout_partitioned_$id").remove("snapshot_$id").commit()
                    saveProfileList(_profiles.value)
                }
            }
        }
    }

    fun renameAkProfile(id: String, name: String) {
        val clean = name.trim().take(24)
        if (clean.isBlank()) return
        viewModelScope.launch {
            profileMutex.withLock {
                val current = _profiles.value
                if (current.none { it.id == id }) return@withLock
                val updated = current.map { if (it.id == id) it.copy(name = clean) else it }
                saveProfileList(updated)
                refreshProfiles()
            }
        }
    }

    fun activateAkProfile(id: String) {
        viewModelScope.launch {
            profileMutex.withLock {
                if (_profiles.value.none { it.id == id }) return@withLock
                activateProfileLocked(id, saveCurrent = true)
            }
        }
    }

    /** Must be called while [profileMutex] is held. */
    private suspend fun activateProfileLocked(id: String, saveCurrent: Boolean) {
        val oldId = activeProfileId()
        if (oldId == id) return
        if (saveCurrent) saveProfileSnapshot(oldId)

        // Settings remain snapshot-based, but Room layouts now live in separate profileId
        // partitions. Select the target partition first; import a legacy snapshot only once, then
        // publish the SharedPreferences active id after settings/layout are ready.
        val snapshot = runCatching {
            JSONObject(profilePrefs.getString("snapshot_$id", "{}"))
        }.getOrDefault(JSONObject())
        val beforeSettings = repository.exportRaw(includeLocalImages = true, includeAppUsage = true)
        val beforeLayout = homeLayoutRepository.snapshotLayout()
        try {
            homeLayoutRepository.setActiveWorkspace(id)
            val partitionKey = "layout_partitioned_$id"
            val needsLayoutImport = !profilePrefs.getBoolean(partitionKey, false)
            val settingsObject = snapshot.optJSONObject("settings")
            if (settingsObject != null) {
                repository.importRaw(jsonToPreferenceMap(settingsObject), preserveLocalImages = false, preserveAppUsage = false)
            } else if (snapshot.length() > 0) {
                // One-time migration from the original seven-setting workspace format.
                repository.setLauncherStyle(snapshot.optString("style", LauncherSettings.STYLE_UNIVERSAL))
                repository.setHomeColumns(snapshot.optInt("homeColumns", 4).coerceIn(1, 12))
                repository.setDrawerColumns(snapshot.optInt("drawerColumns", 4).coerceIn(1, 12))
                repository.setDrawerRows(snapshot.optInt("drawerRows", 6).coerceIn(1, 20))
                repository.setThemeMode(snapshot.optString("theme", LauncherSettings.THEME_SYSTEM))
                repository.setAccentColor(snapshot.optInt("accent", LauncherSettings.ACCENT_DEFAULT))
                repository.setShowDrawerSearch(snapshot.optBoolean("search", true))
            }
            if (needsLayoutImport) {
                val savedLayout = snapshot.optJSONArray("homeLayout")?.let(::decodeHomeLayout)
                val initialLayout = savedLayout
                    ?: beforeLayout.filterNot { it.isWidget || it.isBuiltin || it.hasFootprint }
                homeLayoutRepository.cloneLayoutIntoActiveWorkspace(initialLayout)
                check(profilePrefs.edit().putBoolean(partitionKey, true).commit())
            }
            check(withContext(Dispatchers.IO) { profilePrefs.edit().putString("active", id).commit() })
        } catch (_: Exception) {
            // Restore the previous Room partition before restoring its layout.
            runCatching { homeLayoutRepository.setActiveWorkspace(oldId) }
            runCatching { repository.importRaw(beforeSettings, preserveLocalImages = false, preserveAppUsage = false) }
            runCatching { homeLayoutRepository.restoreLayout(beforeLayout) }
            profilePrefs.edit().putString("active", oldId).commit()
            return
        }
        drawerWallpaper.reloadForActiveProfile()
        refreshMotivationWidget()
        refreshProfiles()
    }

    private fun refreshMotivationWidget() {
        appContext.sendBroadcast(Intent("com.kich.launcher.motivation.PROFILE_CHANGED").setPackage(appContext.packageName))
    }

    private fun activeProfileId(): String = profilePrefs.getString("active", "user1")
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) } ?: "user1"

    /** Persists launcher preferences and a compatibility layout snapshot for one-time migrations. */
    private suspend fun saveProfileSnapshot(id: String, layoutOverride: List<HomeItemEntity>? = null) {
        val preferences = repository.exportRaw(includeLocalImages = true, includeAppUsage = true)
        val settingsJson = JSONObject()
        preferences.forEach { (key, value) -> settingsJson.put(key, value) }
        val layout = JSONArray().apply {
            (layoutOverride ?: homeLayoutRepository.snapshotLayout()).forEach { row ->
                put(JSONObject()
                    .put("id", row.id).put("containerId", row.containerId)
                    .put("folderName", row.folderName).put("packageName", row.packageName)
                    .put("className", row.className).put("userSerial", row.userSerial)
                    .put("shortcutId", row.shortcutId).put("page", row.page)
                    .put("cellX", row.cellX).put("cellY", row.cellY)
                    .put("spanX", row.spanX).put("spanY", row.spanY)
                    .put("appWidgetId", row.appWidgetId).put("widgetProvider", row.widgetProvider)
                    .put("builtinType", row.builtinType))
            }
        }
        val snapshot = JSONObject().put("version", 2).put("settings", settingsJson).put("homeLayout", layout)
        check(withContext(Dispatchers.IO) { profilePrefs.edit().putString("snapshot_$id", snapshot.toString()).commit() }) {
            "Could not save AK workspace snapshot"
        }
    }

    private fun jsonToPreferenceMap(json: JSONObject): Map<String, Any> = buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key) ?: continue
            when (value) {
                is Boolean, is String, is Number -> put(key, value)
            }
        }
    }

    private fun decodeHomeLayout(array: JSONArray): List<HomeItemEntity> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(HomeItemEntity(
                id = item.optLong("id"), containerId = item.optLong("containerId", HomeItemEntity.HOME),
                folderName = if (item.isNull("folderName")) null else item.optString("folderName"),
                packageName = item.optString("packageName"), className = item.optString("className"),
                userSerial = item.optLong("userSerial"),
                shortcutId = if (item.isNull("shortcutId")) null else item.optString("shortcutId"),
                page = item.optInt("page"), cellX = item.optInt("cellX"), cellY = item.optInt("cellY"),
                spanX = item.optInt("spanX", 1).coerceAtLeast(1), spanY = item.optInt("spanY", 1).coerceAtLeast(1),
                appWidgetId = if (item.isNull("appWidgetId")) null else item.optInt("appWidgetId"),
                widgetProvider = if (item.isNull("widgetProvider")) null else item.optString("widgetProvider"),
                builtinType = if (item.isNull("builtinType")) null else item.optString("builtinType"),
            ))
        }
    }

    fun deleteAkProfile(id: String) {
        if (id == "user1") return
        viewModelScope.launch {
            profileMutex.withLock {
                if (_profiles.value.none { it.id == id }) return@withLock
                // Restore User 1 while the deleted profile is still the recorded active profile.
                // Setting active=user1 before this call would make the old-profile snapshot write
                // overwrite User 1's own saved snapshot with the deleted profile's settings.
                if (activeProfileId() == id) {
                    activateProfileLocked("user1", saveCurrent = false)
                }
                val remaining = _profiles.value.filterNot { it.id == id }
                homeLayoutRepository.deleteWorkspace(id)
                profilePrefs.edit().remove("snapshot_$id").remove("layout_partitioned_$id").apply()
                // Remove files owned only by the deleted virtual workspace. User 1 is protected
                // above and its legacy drawer-wallpaper directory is never touched here.
                File(appContext.filesDir, "wallpaper_selected_$id.jpg").delete()
                File(appContext.filesDir, "launcher_wallpaper_$id.jpg").delete()
                File(appContext.filesDir, "drawer_wallpaper/$id").deleteRecursively()
                appContext.getSharedPreferences("kich_wallpaper", Context.MODE_PRIVATE).edit()
                    .remove("target_$id").remove("applied_at_$id").apply()
                appContext.getSharedPreferences("kich_motivation_$id", Context.MODE_PRIVATE).edit().clear().apply()
                saveProfileList(remaining)
                refreshProfiles()
            }
        }
    }

    /** The user's own picture behind the app drawer, or null (a Telegram request after 0.9.9). */
    val drawerWallpaperPicture: StateFlow<DrawerWallpaper?> = drawerWallpaper.current

    /** Saves the picture at [uri] as the drawer's wallpaper. */
    fun importDrawerWallpaper(uri: Uri) = viewModelScope.launch { drawerWallpaper.import(uri) }

    fun clearDrawerWallpaper() = viewModelScope.launch { drawerWallpaper.clear() }

    val settings: StateFlow<LauncherSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LauncherSettings(),
    )

    /** Installed icon packs (queried off-main once), for the picker. */
    val iconPacks: StateFlow<List<IconPackInfo>> =
        flow { emit(iconPackRepository.installedPacks()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All launchable apps (sorted), for the hidden-apps manager list. */
    val apps: StateFlow<List<AppItem>> = appRepository.apps
        .map { list -> list.sortedBy { it.label.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Keys currently hidden from the drawer. */
    val hiddenKeys: StateFlow<Set<String>> = repository.hiddenApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setAppHidden(key: String, hidden: Boolean) = update { repository.setAppHidden(key, hidden) }

    /** Creates a new empty folder in the app drawer (named [name]); it's filled from the drawer. */
    fun createDrawerFolder(name: String) = update { repository.createDrawerFolder(name) }

    /** The drawer's folders, for the Folders page; null until the store has answered, so a folder's
     *  page restored after process death is not closed on an empty list it never had. */
    val drawerFolders: StateFlow<List<DrawerFolder>?> = repository.drawerFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun renameDrawerFolder(id: Long, name: String) = update { repository.renameDrawerFolder(id, name) }

    /** Deletes the folder and the icon picked for it (see CustomIconStore.deleteDrawerFolder). */
    fun deleteDrawerFolder(id: Long) = update { customIcons.deleteDrawerFolder(id) }

    fun setAppInDrawerFolder(id: Long, key: String, member: Boolean) = update {
        if (member) repository.addAppsToDrawerFolder(id, listOf(key)) else repository.removeAppFromDrawerFolder(id, key)
    }

    fun setSwipeUp(value: Boolean) = update { repository.setSwipeUpForDrawer(value) }
    fun setSwipeDown(value: Boolean) = update { repository.setSwipeDownForNotifications(value) }
    fun setFolderSwipeOpensFirstApp(value: Boolean) = update { repository.setFolderSwipeOpensFirstApp(value) }
    fun setHideHomeAppsFromDrawer(value: Boolean) = update { repository.setHideHomeAppsFromDrawer(value) }
    fun setWidgetEdgesAlignWithIcons(value: Boolean) = update { repository.setWidgetEdgesAlignWithIcons(value) }
    fun setDockEnabled(value: Boolean) = update { repository.setDockEnabled(value) }
    fun setDockColumns(value: Int) = update { repository.setDockColumns(value) }
    fun setDrawerColumns(value: Int) = update { repository.setDrawerColumns(value) }
    fun setDrawerRows(value: Int) = update { repository.setDrawerRows(value) }

    /** Applies a layout starting preset while preserving installed apps, folders and widgets. */
    fun setLauncherStyle(style: String) = update {
        repository.setLauncherStyle(style)
        when (style) {
            LauncherSettings.STYLE_ONE_UI -> {
                repository.setHomeColumns(4); repository.setDrawerColumns(4); repository.setDrawerRows(6)
                repository.setShowDrawerSearch(true)
            }
            LauncherSettings.STYLE_PIXEL -> {
                repository.setHomeColumns(4); repository.setDrawerColumns(4); repository.setDrawerRows(5)
                repository.setShowDrawerSearch(true)
            }
            LauncherSettings.STYLE_XIAOMI -> {
                repository.setHomeColumns(4); repository.setDrawerColumns(5); repository.setDrawerRows(6)
                repository.setShowDrawerSearch(true)
            }
            LauncherSettings.STYLE_OPPO -> {
                repository.setHomeColumns(4); repository.setDrawerColumns(4); repository.setDrawerRows(6)
                repository.setShowDrawerSearch(true)
            }
            else -> { /* Universal keeps the user's current grid and preferences. */ }
        }
    }
    fun setShowDrawerSearch(value: Boolean) = update { repository.setShowDrawerSearch(value) }
    fun setShowFrequentApps(value: Boolean) = update { repository.setShowFrequentApps(value) }
    fun setDrawerOpensAtTop(value: Boolean) = update { repository.setDrawerOpensAtTop(value) }
    fun setSortDrawerFolders(value: Boolean) = update { repository.setSortDrawerFolders(value) }
    fun setMixDrawerFolders(value: Boolean) = update { repository.setMixDrawerFolders(value) }
    fun setBlurBehindDrawer(value: Boolean) = update { repository.setBlurBehindDrawer(value) }
    fun setDrawerBlurStrength(value: Float) = update { repository.setDrawerBlurStrength(value) }
    fun setDrawerBackgroundColor(argb: Int?) = update { repository.setDrawerBackgroundColor(argb) }
    fun setDrawerLabelColor(argb: Int?) = update { repository.setDrawerLabelColor(argb) }
    fun setDrawerPictureDim(value: Float) = update { repository.setDrawerPictureDim(value) }
    fun setFontFamily(font: String) = update { repository.setFontFamily(font) }
    fun setThemeMode(mode: String) = update { repository.setThemeMode(mode) }
    fun setAccentColor(argb: Int) = update { repository.setAccentColor(argb) }
    fun setThemeAllIcons(value: Boolean) = update { repository.setThemeAllIcons(value) }
    fun setFolderColumns(value: Int) = update { repository.setFolderColumns(value) }
    fun setSectionCollapsed(id: String, collapsed: Boolean) = update { repository.setSettingsSectionCollapsed(id, collapsed) }
    fun setHomeColumns(value: Int) = viewModelScope.launch {
        // Shrinking the grid can leave shortcuts at an out-of-range cellX; repack them back on-screen
        // before the new (smaller) column count reaches the home screen, so nothing draws off-grid.
        val s = repository.settings.first()
        if (value < s.homeColumns) homeLayoutRepository.reflow(value, s.homeRows)
        repository.setHomeColumns(value)
    }

    fun setHomeRows(value: Int) = viewModelScope.launch {
        // Same off-grid guard as the column stepper, on the vertical axis.
        val s = repository.settings.first()
        if (value < s.homeRows) homeLayoutRepository.reflow(s.homeColumns, value)
        repository.setHomeRows(value)
    }
    fun setShowDockLabels(value: Boolean) = update { repository.setShowDockLabels(value) }
    fun setShowHomeLabels(value: Boolean) = update { repository.setShowHomeLabels(value) }
    fun setShowDrawerLabels(value: Boolean) = update { repository.setShowDrawerLabels(value) }
    fun setShowHomeFolderNames(value: Boolean) = update { repository.setShowHomeFolderNames(value) }
    fun setShowDrawerFolderNames(value: Boolean) = update { repository.setShowDrawerFolderNames(value) }
    fun setDockBackgroundOpacity(value: Float) = update { repository.setDockBackgroundOpacity(value) }
    fun setDockBottomSpacing(value: Int) = update { repository.setDockBottomSpacing(value) }
    fun setDrawerBackgroundOpacity(value: Float) = update { repository.setDrawerBackgroundOpacity(value) }
    fun setShowPageIndicator(value: Boolean) = update { repository.setShowPageIndicator(value) }
    fun setHomePageMarker(value: String) = update { repository.setHomePageMarker(value) }
    fun setInfiniteScroll(value: Boolean) = update { repository.setInfiniteScroll(value) }
    fun setShowNotificationDots(value: Boolean) = update { repository.setShowNotificationDots(value) }
    fun setNotificationDotCount(value: Boolean) = update { repository.setNotificationDotCount(value) }
    fun setNotificationDotScale(value: Float) = update { repository.setNotificationDotScale(value) }
    fun setUseThemedIcons(value: Boolean) = update { repository.setUseThemedIcons(value) }
    fun setIconPack(pkg: String) = update { repository.setIconPackPackage(pkg) }
    fun setSearchContacts(value: Boolean) = update { repository.setSearchContacts(value) }

    /** Sets (or clears, for null) the app launched by the left-edge home swipe. */
    fun setLeftSwipeAppKey(key: String?) = update { repository.setLeftSwipeAppKey(key) }

    /** Sets (or clears, for null) the app a tap on the smartspace weather opens. */
    fun setWeatherAppKey(key: String?) = update { repository.setWeatherAppKey(key) }

    /** How fast the home pager settles on a page (normal / fast / fastest). */
    fun setPageSwitchSpeed(speed: String) = update { repository.setPageSwitchSpeed(speed) }

    fun setDesktopLocked(value: Boolean) = update { repository.setDesktopLocked(value) }
    fun setDoubleTapToLock(value: Boolean) = update { repository.setDoubleTapToLock(value) }
    fun setLockSoundsEnabled(value: Boolean) = update { repository.setLockSoundsEnabled(value) }
    internal fun setLockSound(slot: SoundSlot, uri: String) = update {
        if (slot == SoundSlot.Lock) repository.setLockSoundUri(uri) else repository.setUnlockSoundUri(uri)
    }
    fun setLongPressScale(value: Float) = update { repository.setLongPressScale(value) }

    fun setAppLabelTextScale(value: Float) = update { repository.setAppLabelTextScale(value) }
    fun setIconScale(value: Float) = update { repository.setIconScale(value) }
    fun setDrawerIconScale(value: Float) = update { repository.setDrawerIconScale(value) }
    fun setAppLabelColor(argb: Int) = update { repository.setAppLabelColor(argb) }
    fun setTwoLineHomeLabels(value: Boolean) = update { repository.setTwoLineHomeLabels(value) }
    fun setTwoLineDrawerLabels(value: Boolean) = update { repository.setTwoLineDrawerLabels(value) }
    fun setShowStatusBar(value: Boolean) = update { repository.setShowStatusBar(value) }
    fun setShowWeather(value: Boolean) = update { repository.setShowWeather(value) }
    fun setNetworkPlaceNames(value: Boolean) = update { repository.setNetworkPlaceNames(value) }
    fun setWidgetTonalBackground(value: Boolean) = update { repository.setWidgetTonalBackground(value) }
    fun setNotificationWidgetCountStyle(value: String) = update { repository.setNotificationWidgetCountStyle(value) }
    fun setPeoplePrivacy(value: String) = update { repository.setPeoplePrivacy(value) }
    fun setPeopleShowApps(value: Boolean) = update { repository.setPeopleShowApps(value) }
    fun setPeopleBatchEnabled(value: Boolean) = update { repository.setPeopleBatchEnabled(value) }
    fun setPeopleBatchTimes(value: String) = update { repository.setPeopleBatchTimes(value) }
    fun setHideSystemStatusBar(value: Boolean) = update { repository.setHideSystemStatusBar(value) }
    fun setStatusBarScrimOpacity(value: Float) = update { repository.setStatusBarScrimOpacity(value) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}


/** One process-wide lock because the Settings screen and QS switcher own different ViewModels. */
private object AkProfileMutationLock {
    val mutex = Mutex()
}
