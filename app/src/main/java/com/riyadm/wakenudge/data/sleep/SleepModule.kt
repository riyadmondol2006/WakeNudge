package com.riyadm.wakenudge.data.sleep

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sleep tracking's storage, kept apart from the alarm database and its module. Built only when sleep tracking is first
 * used (nothing injects it at app start), so a process woken by an alarm before the first unlock never opens it.
 */
@Module
@InstallIn(SingletonComponent::class)
object SleepModule {
    @Provides
    @Singleton
    fun sleepDatabase(@ApplicationContext context: Context): SleepDatabase =
        // The application context is credential-encrypted storage, on purpose; see SleepDatabase.
        Room.databaseBuilder(context, SleepDatabase::class.java, SleepDatabase.NAME).build()

    @Provides fun sleepDao(db: SleepDatabase): SleepDao = db.sleepDao()
}
