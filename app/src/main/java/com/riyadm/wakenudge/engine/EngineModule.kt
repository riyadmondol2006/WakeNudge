package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.domain.usecase.ActiveRingQuery
import com.riyadm.wakenudge.widget.SurfaceRefresherImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds abstract fun scheduler(impl: AlarmSchedulerImpl): AlarmScheduler
    @Binds abstract fun surfaces(impl: SurfaceRefresherImpl): SurfaceRefresher
    @Binds abstract fun activeRing(impl: ActiveRingQueryImpl): ActiveRingQuery
}

/** Kept in its own module so instrumented tests can swap the real service launcher for a recording fake. */
@Module
@InstallIn(SingletonComponent::class)
abstract class StarterModule {
    @Binds abstract fun starter(impl: RingServiceLauncher): RingStarter
}

@Singleton
class ActiveRingQueryImpl @Inject constructor(
    private val store: RingSessionStore,
    private val pending: PendingAlarms,
) : ActiveRingQuery {
    override suspend fun isActiveOrSnoozed(alarmId: Long): Boolean =
        store.current?.alarm?.id == alarmId || pending.isScheduled(TriggerKind.SNOOZE, alarmId)
}
