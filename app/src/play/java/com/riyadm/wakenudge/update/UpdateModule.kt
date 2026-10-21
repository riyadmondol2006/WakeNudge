package com.riyadm.wakenudge.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** The Play build never looks for updates: the Play Store does that, and this build holds no network permission. */
@Singleton
class NoUpdateChecker @Inject constructor() : UpdateChecker {
    override suspend fun checkIfDue() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds abstract fun checker(impl: NoUpdateChecker): UpdateChecker
}
