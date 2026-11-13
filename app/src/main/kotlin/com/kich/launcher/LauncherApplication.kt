package com.kich.launcher

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kich.launcher.data.CustomIconStore
import com.kich.launcher.data.PendingWidgetIds
import com.kich.launcher.data.SharedPreferencesPendingIdStorage
import com.kich.launcher.data.di.ApplicationScope
import javax.inject.Inject

@HiltAndroidApp
class LauncherApplication : Application(), SingletonImageLoader.Factory {

    /** Hilt-provided ImageLoader with the app-icon fetcher/keyer (see DataModule). */
    @Inject
    lateinit var imageLoader: ImageLoader

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    @Inject
    lateinit var lockSounds: LockSounds

    @Inject
    lateinit var customIcons: CustomIconStore

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        val startedAt = System.currentTimeMillis()
        super.onCreate()
        // Before any activity, so the home screen's first sweep already knows the widget ids another
        // app's request held when a previous process died with the system's bind dialog open.
        PendingWidgetIds.shared.persistIn(SharedPreferencesPendingIdStorage(this))
        lockSounds.start()
        // AK profile locks re-engage when the screen turns off. A dynamically registered receiver in
        // the Application — no service, nothing running while the screen is on.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                com.kich.launcher.data.lock.ProfileLockManager.get(context).onScreenOff()
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        // Gallery pictures an earlier run left unused (see CustomIconStore.deleteUnusedPictures).
        scope.launch(Dispatchers.IO) { runCatching { customIcons.deleteUnusedPictures(savedBefore = startedAt) } }
    }
}
