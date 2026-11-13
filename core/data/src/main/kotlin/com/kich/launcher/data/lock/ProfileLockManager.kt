package com.kich.launcher.data.lock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI needs: which profile is active, and whether it is behind its lock screen. */
data class LockUiState(
    val profileId: String,
    val lockEnabled: Boolean,
    val locked: Boolean,
    val fingerprint: Boolean,
)

/**
 * Which AK profile is unlocked. Invariant: AT MOST ONE profile is ever unlocked — the active one.
 *
 * The unlock marker lives in the SAME SharedPreferences file as the active-profile id
 * ("ak_workspace_profiles"), so a profile switch changes both in one atomic commit
 * ([markerClearedEditor]): the profile being left is locked in the very write that activates the
 * next one, and no ordering of crashes, recreations or rapid switches can leave two unlocked.
 * A marker is honoured only for the boot it was written in, so a restart locks everything.
 *
 * This is AK's own authentication. It never touches Android's device lock.
 */
class ProfileLockManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val clock: LockClock = AndroidLockClock(appContext)
    val store = ProfileLockStore(appContext, clock = clock)

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    private val _returnRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the screen turned off and the launcher should go back to User 1 (see [onScreenOff]). */
    val returnToFirstRequests: SharedFlow<Unit> = _returnRequests

    /** Global option: switch to User 1 whenever the screen turns off. On by default. */
    var returnToFirstOnScreenOff: Boolean
        get() = prefs.getBoolean(KEY_RETURN_TO_FIRST, true)
        set(value) { prefs.edit().putBoolean(KEY_RETURN_TO_FIRST, value).apply() }

    /**
     * Screen turned off: lock the active profile, and (if enabled) ask the launcher to switch to
     * User 1. The request is also stored, so if no launcher Activity is alive to act on it now, the
     * next launcher start does. Switching itself stays in the one place that owns it (the
     * SettingsViewModel's serialised profile switch); this only asks.
     */
    fun onScreenOff() {
        lockActive()
        if (returnToFirstOnScreenOff && activeProfileId() != FIRST_PROFILE) {
            prefs.edit().putBoolean(KEY_PENDING_RETURN, true).commit()
            _returnRequests.tryEmit(Unit)
        }
    }

    /** True once if a return to User 1 is pending and still needed; clears the request. */
    @Synchronized
    fun consumePendingReturn(): Boolean {
        val pending = prefs.getBoolean(KEY_PENDING_RETURN, false)
        if (pending) prefs.edit().remove(KEY_PENDING_RETURN).commit()
        return pending && activeProfileId() != FIRST_PROFILE
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun activeProfileId(): String = prefs.getString(KEY_ACTIVE, "user1")
        ?.takeIf(ProfileLockStore::isValidId) ?: "user1"

    fun isLocked(profileId: String = activeProfileId()): Boolean = compute(profileId).locked

    /** Re-reads the persisted state. Cheap; call from onStart/onResume. */
    fun refresh() { _state.value = compute() }

    /** Locks the active profile (screen off). Synchronous so it is durable before the screen sleeps. */
    fun lockActive() {
        prefs.edit().remove(KEY_UNLOCKED_ID).remove(KEY_UNLOCKED_BOOT_COUNT).remove(KEY_UNLOCKED_BOOT_AT).commit()
        refresh()
    }

    /**
     * Marks [profileId] unlocked after a successful authentication. Refused unless it is still the
     * active profile — an answer that arrives after the user switched away must not unlock anything.
     */
    fun markUnlocked(profileId: String): Boolean {
        if (activeProfileId() != profileId) return false
        val boot = clock.boot()
        val ok = prefs.edit()
            .putString(KEY_UNLOCKED_ID, profileId)
            .putInt(KEY_UNLOCKED_BOOT_COUNT, boot.count)
            .putLong(KEY_UNLOCKED_BOOT_AT, boot.startedAtWall)
            .commit()
        refresh()
        return ok
    }

    suspend fun unlockWithPin(profileId: String, pin: CharArray): PinCheck {
        val result = store.verifyPin(profileId, pin)
        if (result == PinCheck.Success && !markUnlocked(profileId)) return PinCheck.NotConfigured
        return result
    }

    /** Call only after [BiometricUnlock] confirmed a Keystore-authorised success. */
    fun unlockWithBiometric(profileId: String): Boolean {
        store.recordExternalSuccess(profileId)
        return markUnlocked(profileId)
    }

    private fun compute(profileId: String = activeProfileId()): LockUiState {
        val cfg = store.config(profileId)
        val unlocked = prefs.getString(KEY_UNLOCKED_ID, null) == profileId &&
            BootIdentity(prefs.getInt(KEY_UNLOCKED_BOOT_COUNT, -1), prefs.getLong(KEY_UNLOCKED_BOOT_AT, 0L))
                .sameBootAs(clock.boot())
        return LockUiState(
            profileId = profileId,
            lockEnabled = cfg.enabled,
            locked = cfg.enabled && !unlocked,
            fingerprint = cfg.enabled && cfg.fingerprint && cfg.bioBlob != null,
        )
    }

    companion object {
        const val PREFS = "ak_workspace_profiles"
        const val KEY_ACTIVE = "active"
        const val FIRST_PROFILE = "user1"
        private const val KEY_RETURN_TO_FIRST = "lock_return_to_first_on_screen_off"
        private const val KEY_PENDING_RETURN = "lock_pending_return_to_first"
        private const val KEY_UNLOCKED_ID = "lock_unlocked_id"
        private const val KEY_UNLOCKED_BOOT_COUNT = "lock_unlocked_boot_count"
        private const val KEY_UNLOCKED_BOOT_AT = "lock_unlocked_boot_at"

        @Volatile private var instance: ProfileLockManager? = null
        fun get(context: Context): ProfileLockManager = instance ?: synchronized(this) {
            instance ?: ProfileLockManager(context).also { instance = it }
        }

        /**
         * Use for EVERY write that changes the active profile: adds "clear the unlock marker" to
         * the same editor, so leaving a profile and locking it are one atomic commit.
         */
        fun markerClearedEditor(editor: SharedPreferences.Editor): SharedPreferences.Editor =
            editor.remove(KEY_UNLOCKED_ID).remove(KEY_UNLOCKED_BOOT_COUNT).remove(KEY_UNLOCKED_BOOT_AT)
    }
}
