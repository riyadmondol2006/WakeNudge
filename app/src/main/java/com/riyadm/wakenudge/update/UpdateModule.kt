package com.riyadm.wakenudge.update

import com.riyadm.wakenudge.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The update machinery: the real manager and the GitHub client. UI tests replace this module with a scripted one. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds abstract fun checker(impl: UpdateManager): UpdateChecker
    @Binds abstract fun client(impl: GithubReleaseClient): ReleaseClient

    companion object {
        @Provides
        fun environment(): UpdateEnvironment = UpdateEnvironment(
            currentVersion = BuildConfig.VERSION_NAME,
            autoCheck = !BuildConfig.DEBUG,
            repoUrl = BuildConfig.REPO_URL,
        )
    }
}
