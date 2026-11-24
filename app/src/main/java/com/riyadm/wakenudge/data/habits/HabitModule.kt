package com.riyadm.wakenudge.data.habits

import android.content.Context
import androidx.room.Room
import com.riyadm.wakenudge.di.DeviceProtected
import com.riyadm.wakenudge.domain.habits.HabitRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HabitDataModule {
    @Provides
    @Singleton
    fun database(@DeviceProtected context: Context): HabitDatabase =
        Room.databaseBuilder(context, HabitDatabase::class.java, HabitDatabase.NAME).build()

    @Provides
    fun dao(db: HabitDatabase): HabitDao = db.habitDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HabitRepositoryModule {
    @Binds
    abstract fun repository(impl: HabitRepositoryImpl): HabitRepository
}
