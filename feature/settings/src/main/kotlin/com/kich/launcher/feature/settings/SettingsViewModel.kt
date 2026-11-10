package com.kich.launcher.feature.settings

import androidx.lifecycle.ViewModel
import com.kich.launcher.data.DrawerWallpaperStore
import com.kich.launcher.data.DrawerWallpaper
import android.net.Uri
import android.content.Context
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
import com.kich.launcher.data.AppRepository
import com.kich.launcher.data.CustomIconStore
import com.kich.launcher.data.DrawerFolder
import com.kich.launcher.data.HomeLayoutRepository
import com.kich.launcher.data.IconPackInfo
import com.kich.launcher.data.IconPackRepository
import com.kich.launcher.data.SettingsRepository
import com.kich.launcher.model.AppItem
import com.kich.launcher.model.LauncherSettings
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

    val profiles = _profiles.asStateFlow()
    private val _activeProfileName = MutableStateFlow("User 1")
    val activeProfileName = _activeProfileName.asStateFlow()

    init {
        refreshProfiles()
        viewModelScope.launch {
            if (!profilePrefs.contains("snapshot_user1")) saveProfileSnapshot("user1", repository.settings.first())
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
        if (clean.isBlank() || _profiles.value.size >= 5) return
        val id = "ak_" + java.util.UUID.randomUUID().toString().take(8)
        viewModelScope.launch {
            saveProfileSnapshot(id, repository.settings.first())
            val updated = _profiles.value.map { it.copy(active = false) } + AkWorkspaceProfile(id, clean, true)
            saveProfileList(updated); profilePrefs.edit().putString("active", id).apply(); refreshProfiles()
        }
    }

    fun activateAkProfile(id: String) {
        val oldId = profilePrefs.getString("active", "user1") ?: "user1"
        val snapshot = runCatching { JSONObject(profilePrefs.getString("snapshot_$id", "{}")) }.getOrDefault(JSONObject())
        viewModelScope.launch {
            saveProfileSnapshot(oldId, repository.settings.first())
            if (snapshot.length() > 0) {
                repository.setLauncherStyle(snapshot.optString("style", LauncherSettings.STYLE_UNIVERSAL))
                repository.setHomeColumns(snapshot.optInt("homeColumns", 4))
                repository.setDrawerColumns(snapshot.optInt("drawerColumns", 4))
                repository.setDrawerRows(snapshot.optInt("drawerRows", 6))
                repository.setThemeMode(snapshot.optString("theme", LauncherSettings.THEME_SYSTEM))
                repository.setAccentColor(snapshot.optInt("accent", LauncherSettings.ACCENT_DEFAULT))
                repository.setShowDrawerSearch(snapshot.optBoolean("search", true))
            }
            profilePrefs.edit().putString("active", id).apply(); refreshProfiles()
        }
    }

    private fun saveProfileSnapshot(id: String, s: LauncherSettings) {
        profilePrefs.edit().putString("snapshot_$id", JSONObject()
            .put("style", s.launcherStyle).put("homeColumns", s.homeColumns)
            .put("drawerColumns", s.drawerColumns).put("drawerRows", s.drawerRows)
            .put("theme", s.themeMode).put("accent", s.accentColor)
            .put("search", s.showDrawerSearch).toString()).apply()
    }

    fun deleteAkProfile(id: String) {
        if (id == "user1") return
        val remaining = _profiles.value.filterNot { it.id == id }
        profilePrefs.edit().remove("snapshot_$id").apply()
        saveProfileList(remaining)
        if (profilePrefs.getString("active", "user1") == id) {
            profilePrefs.edit().putString("active", "user1").apply(); activateAkProfile("user1")
        } else refreshProfiles()
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
