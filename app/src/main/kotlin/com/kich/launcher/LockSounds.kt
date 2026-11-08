package com.kich.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.kich.launcher.data.SettingsRepository
import com.kich.launcher.data.di.ApplicationScope
import com.kich.launcher.launcher.system.LockSoundChoice
import com.kich.launcher.launcher.system.LockSoundMonitor
import com.kich.launcher.launcher.system.ShortSoundPlayer
import javax.inject.Inject
import javax.inject.Singleton

/** The lock and unlock sounds the user picked (Settings → Sounds), for as long as the launcher's
 *  process runs — it is the phone's HOME, so that is all the time the phone is in use. */
@Singleton
class LockSounds @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val choice = settings.settings
        .map { LockSoundChoice(it.lockSoundsEnabled, it.lockSoundUri, it.unlockSoundUri) }
        .stateIn(scope, SharingStarted.Eagerly, LockSoundChoice())

    private val monitor = LockSoundMonitor(context, { choice.value }, ShortSoundPlayer(context))

    fun start() = monitor.start()
}
