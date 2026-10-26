package com.riyadm.wakenudge.update

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Inject
import javax.inject.Singleton

/** Answers update lookups from a script instead of api.github.com, so UI tests never touch the network. */
@Singleton
class FakeReleaseClient @Inject constructor() : ReleaseClient {
    @Volatile var next: LookupResult = LookupResult.NoRelease("scripted")
    @Volatile var calls = 0

    override suspend fun latest(): LookupResult {
        calls++
        return next
    }
}

/** Same wiring as the real [UpdateModule], with the fake client and automatic checks enabled (a debug build disables them). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [UpdateModule::class])
abstract class TestUpdateModule {
    @Binds abstract fun checker(impl: UpdateManager): UpdateChecker
    @Binds abstract fun client(impl: FakeReleaseClient): ReleaseClient

    companion object {
        @Provides
        fun environment(): UpdateEnvironment =
            UpdateEnvironment(currentVersion = "1.0.0", autoCheck = true, repoUrl = "https://github.com/riyadmondol2006/WakeNudge")
    }
}
