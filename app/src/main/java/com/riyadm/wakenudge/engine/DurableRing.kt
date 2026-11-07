package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * What a ring must not lose when the process dies, the database fails or the phone restarts: the alarm as it fired, and
 * everything the ring has counted so far. It lives in [RuntimeState] rather than the database because storage never gets
 * to decide whether an alarm rings: it is readable before the first unlock and survives a corrupt database. It describes a
 * moment rather than the user's setup, so it is not part of a backup.
 *
 * It is written when a ring begins and when something countable happens (a mission step, a mute, a pause), not on every
 * touch, and removed when the ring ends. Timers and test alarms do not have one.
 */
@Serializable
data class DurableRingRecord(
    val alarmJson: String,
    val alarmId: Long,
    val eventId: Long = -1,
    val scheduledAt: Long = 0,
    val kind: RingKind = RingKind.PRIMARY,
    val phase: RingPhase = RingPhase.RINGING,
    val snoozeCount: Int = 0,
    val missionsDone: Int = 0,
    val fallback: Boolean = false,
    val wayOutProgress: Int = 0,
    val mutesUsed: Int = 0,
    val pauses: Int = 0,
    val resumes: Int = 0,
    val pullBacks: Int = 0,
    /** When this ring first started, on the wall clock: what the resume window is counted from. */
    val startedAtWall: Long = 0,
    /** The boot this was last written in, or -1 when the system would not say. */
    val bootCount: Int = -1,
    /** Time since boot at the last write; tells a restart apart when [bootCount] is unavailable. */
    val elapsedAtWrite: Long = 0,
    val versionCode: Int = 0,
) {
    fun alarm(): Alarm? = runCatching { WakeJson.instance.decodeFromString(Alarm.serializer(), alarmJson) }.getOrNull()

    fun toRequest() = RingRequest(alarmId, eventId, scheduledAt, kind, snoozeCount, phase)

    /** Whether [request] is this same ring coming back, rather than another alarm or a later occurrence. */
    fun matches(request: RingRequest): Boolean =
        alarmId == request.alarmId && (eventId == request.eventId || eventId <= 0 || request.eventId <= 0)

    companion object {
        fun of(session: RingSession, bootCount: Int, elapsed: Long, versionCode: Int = BuildConfig.VERSION_CODE) = DurableRingRecord(
            alarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), session.alarm),
            alarmId = session.alarm.id,
            eventId = session.eventId,
            scheduledAt = session.scheduledAt,
            kind = session.kind,
            phase = session.phase,
            snoozeCount = session.snoozeCount,
            missionsDone = session.missionsDone,
            fallback = session.fallback,
            wayOutProgress = session.wayOutProgress,
            mutesUsed = session.mutesUsed,
            pauses = session.pauses,
            resumes = session.resumes,
            pullBacks = session.pullBacks,
            startedAtWall = session.startedAt,
            bootCount = bootCount,
            elapsedAtWrite = elapsed,
            versionCode = versionCode,
        )
    }
}

/** What to do with a ring left behind by a process that is gone. */
sealed interface ResumeDecision {
    /** Ring it again, from [record]. */
    data class Resume(val record: DurableRingRecord) : ResumeDecision

    data class GiveUp(val reason: GiveUpReason) : ResumeDecision
}

enum class GiveUpReason { TooManyRestarts, WindowPassed, ForceStopped }

object ResumePolicy {
    /**
     * How long, in one boot, an alarm that does not keep coming back stays worth ringing again. Nothing brings it back but
     * something else happening to start the app, which could be hours later; it should not ring out of nowhere by then.
     */
    const val SAME_BOOT_LIMIT_MS = 60 * 60_000L

    fun rebooted(record: DurableRingRecord, bootCount: Int, elapsed: Long): Boolean =
        if (bootCount >= 0 && record.bootCount >= 0) bootCount != record.bootCount else elapsed < record.elapsedAtWrite

    /**
     * Only a restart of the phone uses up one of the alarm's [maxResumes] and is held to the window: a process that merely died (the system, or
     * Stop in Active apps) comes back for as long as the ring is unresolved if the alarm [keepsComingBack], and for
     * [SAME_BOOT_LIMIT_MS] or its window if not. [triggersWiped] says Android has forgotten every trigger of this app with
     * no restart and no update, which is what a force-stop looks like: that ring comes back too when the app next runs, but
     * only within [SAME_BOOT_LIMIT_MS] or its window, whichever is longer.
     */
    fun afterProcessStart(
        record: DurableRingRecord,
        window: Int,
        maxResumes: Int,
        keepsComingBack: Boolean,
        now: Long,
        elapsed: Long,
        bootCount: Int,
        versionCode: Int,
        triggersWiped: Boolean,
    ): ResumeDecision {
        if (rebooted(record, bootCount, elapsed)) {
            // There is always a limit and it is never 0: a stuck phone needs a way out, and a restart always comes back once.
            if (record.resumes >= maxResumes.coerceIn(1, AntiOversleepSettings.MAX_RESUMES_LIMIT)) return ResumeDecision.GiveUp(GiveUpReason.TooManyRestarts)
            // A clock set back makes the age negative: that is no reason to lose the alarm.
            if (now - record.startedAtWall > window * 60_000L) return ResumeDecision.GiveUp(GiveUpReason.WindowPassed)
            return ResumeDecision.Resume(record.copy(resumes = record.resumes + 1, bootCount = bootCount, elapsedAtWrite = elapsed, versionCode = versionCode))
        }
        if (record.versionCode != versionCode) return ResumeDecision.Resume(record.copy(versionCode = versionCode, elapsedAtWrite = elapsed))
        // A force-stop is a way to silence a ring, so it does not end one: it comes back when the app next runs (Android does
        // not let it sooner), as a ring the system killed would, but never long after, even on an alarm that keeps coming back.
        if ((triggersWiped || !keepsComingBack) && now - record.startedAtWall > maxOf(SAME_BOOT_LIMIT_MS, window * 60_000L)) {
            return ResumeDecision.GiveUp(if (triggersWiped) GiveUpReason.ForceStopped else GiveUpReason.WindowPassed)
        }
        return ResumeDecision.Resume(record)
    }
}

/**
 * Reads and writes the record and the rings queued behind it. Nothing here throws, whatever state the storage is in: a
 * corrupt record counts as no record, and a failed read or write is logged while the ring carries on.
 */
class DurableRingStore(private val runtime: RuntimeState, private val diagnostics: DiagnosticsLog) {
    private val queueSerializer = ListSerializer(RingRequest.serializer())

    fun ring(): DurableRingRecord? = guarded("reading", null) {
        val json = runtime.durableRingJson ?: return@guarded null
        val record = runCatching { WakeJson.instance.decodeFromString(DurableRingRecord.serializer(), json) }.getOrNull()?.takeIf { it.alarm() != null }
        if (record == null) {
            diagnostics.warn("Ring", "the saved ring could not be read; treating it as gone")
            runtime.durableRingJson = null
        }
        record
    }

    fun writeRing(record: DurableRingRecord) = guarded("writing", Unit) {
        runtime.durableRingJson = WakeJson.instance.encodeToString(DurableRingRecord.serializer(), record)
    }

    fun clearRing() = guarded("clearing", Unit) { runtime.durableRingJson = null }

    fun queue(): List<RingRequest> = guarded("reading the queue of", emptyList()) {
        val json = runtime.durableQueueJson ?: return@guarded emptyList()
        runCatching { WakeJson.instance.decodeFromString(queueSerializer, json) }.getOrDefault(emptyList())
    }

    fun writeQueue(requests: List<RingRequest>) = guarded("writing the queue of", Unit) {
        runtime.durableQueueJson = if (requests.isEmpty()) null else WakeJson.instance.encodeToString(queueSerializer, requests)
    }

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        diagnostics.error("Ring", "$what the saved ring failed; the alarm carries on", t)
        fallback
    }
}
