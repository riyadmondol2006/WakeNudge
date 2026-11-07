package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.engine.RingSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * When the torch flashes for an ignored alarm, for people who may not hear it: only on an alarm set to
 * ([com.riyadm.wakenudge.domain.model.AntiOversleepSettings.torchWhenIgnored]), only once it got louder for being ignored,
 * never during an Emergency pause or a mute, never while a camera mission has the camera, and never faster than once a
 * second: on for half a second, off for half a second, well below the rates that can set off a seizure.
 */
object TorchFlashPolicy {
    const val HALF_PERIOD_MS = 500L

    fun shouldFlash(session: RingSession?, elapsedNow: Long, cameraInUse: Boolean): Boolean =
        session != null && !session.isTimer && session.phase == RingPhase.RINGING &&
            session.alarm.antiOversleep.torchWhenIgnored && session.intensified &&
            !session.pausedAt(elapsedNow) && session.silencedUntil <= elapsedNow && session.muteStartedAt == 0L &&
            !cameraInUse

    /** Whether a ring may flash at all, so nothing runs for the many rings that never will. */
    fun mayFlash(session: RingSession?): Boolean =
        session != null && !session.isTimer && session.alarm.antiOversleep.torchWhenIgnored
}

/** The phone's torch, or nothing if it has none. Returns false when the torch could not be set (the camera is in use). */
fun interface Torch {
    fun set(on: Boolean): Boolean
}

/** Set while a mission shows the camera: it needs the camera, and with it the flash, for itself. */
object CameraInUse {
    private val count = MutableStateFlow(0)
    val inUse: StateFlow<Int> = count.asStateFlow()

    fun acquire() = count.update { it + 1 }
    fun release() = count.update { (it - 1).coerceAtLeast(0) }

    private inline fun MutableStateFlow<Int>.update(f: (Int) -> Int) {
        while (true) {
            val v = value
            if (compareAndSet(v, f(v))) return
        }
    }
}

/**
 * Flashes the torch while [TorchFlashPolicy] says so. It watches the ring session and the camera, so the ring service
 * needs to know nothing about it; anything that goes wrong turns the torch off and stops, and never touches the ring.
 * It only ever switches off a torch it switched on.
 */
@Singleton
class TorchFlasher internal constructor(
    private val sessions: RingSessionStore,
    private val time: TimeSource,
    private val torch: Torch,
    private val log: DiagnosticsLog,
    private val cameraInUse: StateFlow<Int>,
) {
    @Inject constructor(
        sessions: RingSessionStore,
        time: TimeSource,
        @ApplicationContext context: Context,
        log: DiagnosticsLog,
    ) : this(sessions, time, CameraTorch(context), log, CameraInUse.inUse)

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            combine(sessions.session, cameraInUse) { s, camera -> s to (camera > 0) }.collectLatest { (s, camera) ->
                if (!TorchFlashPolicy.mayFlash(s)) return@collectLatest
                var lit = false
                try {
                    while (true) {
                        val want = TorchFlashPolicy.shouldFlash(sessions.current, time.elapsedMillis(), camera) && !lit
                        if (want != lit) {
                            if (!torch.set(want) && want) {
                                // The torch is busy or gone: try again later, never faster.
                                delay(TorchFlashPolicy.HALF_PERIOD_MS * 2)
                                continue
                            }
                            lit = want
                        }
                        delay(TorchFlashPolicy.HALF_PERIOD_MS)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    log.warn("Torch", "flashing stopped: ${t.message}")
                } finally {
                    if (lit) runCatching { torch.set(false) }
                }
            }
        }
    }
}

/** The back camera's flash through [CameraManager.setTorchMode], which needs no camera permission. */
private class CameraTorch(private val context: Context) : Torch {
    private val id: String? by lazy {
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java) ?: return@runCatching null
            manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
    }

    override fun set(on: Boolean): Boolean {
        val camera = id ?: return false
        return runCatching { context.getSystemService(CameraManager::class.java)?.setTorchMode(camera, on); true }.getOrDefault(false)
    }
}
