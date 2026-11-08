package com.kich.launcher.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import coil3.ImageLoader
import coil3.memory.MemoryCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.kich.launcher.data.AppIconFetcher
import com.kich.launcher.data.AppIconKeyer
import com.kich.launcher.data.AppLabelStore
import com.kich.launcher.data.AndroidProfileSystem
import com.kich.launcher.data.AppRowResolver
import com.kich.launcher.data.CustomIconFetcher
import com.kich.launcher.data.CustomIconKeyer
import com.kich.launcher.data.CustomIconSource
import com.kich.launcher.data.IconChoicesSource
import com.kich.launcher.data.LauncherAppsSource
import com.kich.launcher.data.ProfileSystem
import com.kich.launcher.data.RoomAppLabelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.kich.launcher.data.local.HomeItemDao
import com.kich.launcher.data.local.LauncherDatabase
import javax.inject.Singleton
import com.kich.launcher.model.IconChoices

private val Context.settingsDataStore by preferencesDataStore(
    name = "launcher_settings",
    // A corrupted preferences file (interrupted write, power loss) otherwise throws
    // CorruptionException on every read — for the HOME app that's an unrecoverable crash loop.
    // Resetting to defaults loses only settings; the Room-backed home layout is untouched.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE home_items ADD COLUMN spanX INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE home_items ADD COLUMN spanY INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE home_items ADD COLUMN appWidgetId INTEGER")
        db.execSQL("ALTER TABLE home_items ADD COLUMN widgetProvider TEXT")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE home_items ADD COLUMN builtinType TEXT")
    }
}

/** v8: the app-label table (AppLabelEntity) beside the home layout; the statement is the one Room
 *  generates for the entity, validated by AppLabelMigrationTest. */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_labels` (`key` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`locale` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun provideIconChoices(source: IconChoicesSource): IconChoices = source

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        source: LauncherAppsSource,
        custom: CustomIconSource,
    ): ImageLoader = ImageLoader.Builder(context)
        // A smaller in-memory icon cache than Coil's default: easier on low-RAM phones.
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
        .components {
            add(AppIconKeyer())
            add(AppIconFetcher.Factory(source, custom))
            add(CustomIconKeyer())
            add(CustomIconFetcher.Factory(custom))
        }
        .build()

    @Provides
    @Singleton
    fun provideLauncherDatabase(@ApplicationContext context: Context): LauncherDatabase =
        Room.databaseBuilder(context, LauncherDatabase::class.java, "launcher.db")
            // The schema is exported (core/data/schemas); a future version bump must add a real
            // Migration here via .addMigrations(...). Destructive fallback is allowed ONLY from the
            // pre-v5 dev versions (1–4, which never shipped a migration) so an old test install resets
            // instead of crashing; v5 onward is migrated, so the user's home layout survives upgrades.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4)
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .build()

    @Provides
    fun provideHomeItemDao(database: LauncherDatabase): HomeItemDao = database.homeItemDao()

    @Provides
    @Singleton
    fun provideAppLabelStore(database: LauncherDatabase): AppLabelStore = RoomAppLabelStore(database.appLabelDao())

    @Provides
    fun provideAppRowResolver(source: LauncherAppsSource): AppRowResolver = source

    @Provides
    fun provideProfileSystem(impl: AndroidProfileSystem): ProfileSystem = impl
}
