package com.riyadm.wakenudge.engine.sounds

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SleepSoundsModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): SleepSoundsDatabase =
        Room.databaseBuilder(context, SleepSoundsDatabase::class.java, "sleep_sounds.db").build()
}
