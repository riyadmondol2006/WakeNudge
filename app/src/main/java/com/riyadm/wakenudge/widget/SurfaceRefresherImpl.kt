package com.riyadm.wakenudge.widget

import android.content.Context
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurfaceRefresherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SurfaceRefresher {
    override fun refreshAll() = Unit
}
