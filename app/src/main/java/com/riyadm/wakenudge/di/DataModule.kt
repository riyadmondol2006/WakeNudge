package com.riyadm.wakenudge.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import com.riyadm.wakenudge.core.SystemTimeSource
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.db.WakeNudgeDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceProtected

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingModule {
    @Binds
    abstract fun timeSource(impl: SystemTimeSource): TimeSource
}

/**
 * All persistent state lives in device-protected storage so that an alarm can still ring after a reboot, before the
 * user has unlocked the phone for the first time (Direct Boot). Nothing stored here is sensitive.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @DeviceProtected
    fun deviceProtectedContext(@ApplicationContext context: Context): Context =
        if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

    @Provides
    @Singleton
    fun database(@DeviceProtected context: Context): WakeNudgeDatabase =
        Room.databaseBuilder(context, WakeNudgeDatabase::class.java, WakeNudgeDatabase.NAME).build()

    @Provides fun alarmDao(db: WakeNudgeDatabase) = db.alarmDao()
    @Provides fun groupDao(db: WakeNudgeDatabase) = db.groupDao()
    @Provides fun eventDao(db: WakeNudgeDatabase) = db.eventDao()
    @Provides fun timerDao(db: WakeNudgeDatabase) = db.timerDao()
    @Provides fun worldClockDao(db: WakeNudgeDatabase) = db.worldClockDao()
    @Provides fun exceptionDao(db: WakeNudgeDatabase) = db.exceptionDao()
    @Provides fun wakeCheckDao(db: WakeNudgeDatabase) = db.wakeCheckDao()
    @Provides fun diagnosticDao(db: WakeNudgeDatabase) = db.diagnosticDao()

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun settingsDataStore(@DeviceProtected context: Context, @AppScope scope: CoroutineScope): DataStore<Preferences> =
        // A settings file that cannot be parsed is replaced by defaults instead of failing every read from then on.
        PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = scope) {
            File(context.filesDir, "datastore/settings.preferences_pb")
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun alarms(impl: com.riyadm.wakenudge.data.repo.AlarmRepositoryImpl): com.riyadm.wakenudge.domain.repository.AlarmRepository
    @Binds abstract fun groups(impl: com.riyadm.wakenudge.data.repo.GroupRepositoryImpl): com.riyadm.wakenudge.domain.repository.GroupRepository
    @Binds abstract fun history(impl: com.riyadm.wakenudge.data.repo.HistoryRepositoryImpl): com.riyadm.wakenudge.domain.repository.HistoryRepository
    @Binds abstract fun timers(impl: com.riyadm.wakenudge.data.repo.TimerRepositoryImpl): com.riyadm.wakenudge.domain.repository.TimerRepository
    @Binds abstract fun worldClocks(impl: com.riyadm.wakenudge.data.repo.WorldClockRepositoryImpl): com.riyadm.wakenudge.domain.repository.WorldClockRepository
    @Binds abstract fun exceptions(impl: com.riyadm.wakenudge.data.repo.ExceptionRepositoryImpl): com.riyadm.wakenudge.domain.repository.ExceptionRepository
    @Binds abstract fun wakeChecks(impl: com.riyadm.wakenudge.data.repo.WakeCheckRepositoryImpl): com.riyadm.wakenudge.domain.repository.WakeCheckRepository
    @Binds abstract fun settings(impl: com.riyadm.wakenudge.data.prefs.SettingsRepositoryImpl): com.riyadm.wakenudge.domain.repository.SettingsRepository
    @Binds abstract fun diagnostics(impl: com.riyadm.wakenudge.data.repo.DiagnosticsLogImpl): com.riyadm.wakenudge.domain.repository.DiagnosticsLog
}
