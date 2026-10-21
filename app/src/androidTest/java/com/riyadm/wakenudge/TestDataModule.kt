package com.riyadm.wakenudge

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.riyadm.wakenudge.data.db.WakeNudgeDatabase
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.di.DataModule
import com.riyadm.wakenudge.di.DeviceProtected
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Swaps persistence for an in-memory database and a throw-away DataStore so tests never touch (or depend on) the
 * app's real data, while everything above persistence — scheduler, controllers, AlarmManager — is the real thing.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule {
    @Provides @Singleton @DeviceProtected
    fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Provides @Singleton
    fun database(@DeviceProtected context: Context): WakeNudgeDatabase =
        Room.inMemoryDatabaseBuilder(context, WakeNudgeDatabase::class.java).allowMainThreadQueries().build()

    @Provides fun alarmDao(db: WakeNudgeDatabase) = db.alarmDao()
    @Provides fun groupDao(db: WakeNudgeDatabase) = db.groupDao()
    @Provides fun eventDao(db: WakeNudgeDatabase) = db.eventDao()
    @Provides fun timerDao(db: WakeNudgeDatabase) = db.timerDao()
    @Provides fun worldClockDao(db: WakeNudgeDatabase) = db.worldClockDao()
    @Provides fun exceptionDao(db: WakeNudgeDatabase) = db.exceptionDao()
    @Provides fun wakeCheckDao(db: WakeNudgeDatabase) = db.wakeCheckDao()
    @Provides fun diagnosticDao(db: WakeNudgeDatabase) = db.diagnosticDao()

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun dataStore(@DeviceProtected context: Context, @AppScope scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb") }
}
