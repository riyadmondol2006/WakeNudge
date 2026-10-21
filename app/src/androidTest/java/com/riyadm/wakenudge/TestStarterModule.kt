package com.riyadm.wakenudge

import com.riyadm.wakenudge.engine.RingRequest
import com.riyadm.wakenudge.engine.RingStarter
import com.riyadm.wakenudge.engine.StarterModule
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/** Records the rings the engine asks for instead of starting the real foreground service (and its audio). */
@Singleton
class RecordingStarter @Inject constructor() : RingStarter {
    val requests = CopyOnWriteArrayList<RingRequest>()
    override fun start(request: RingRequest) {
        requests += request
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [StarterModule::class])
abstract class TestStarterModule {
    @Binds abstract fun starter(impl: RecordingStarter): RingStarter
}
