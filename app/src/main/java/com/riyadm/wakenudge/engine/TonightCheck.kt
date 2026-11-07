package com.riyadm.wakenudge.engine

import android.app.NotificationManager
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.health.HealthChecker
import com.riyadm.wakenudge.health.HealthFixKind
import com.riyadm.wakenudge.health.HealthSnapshot
import com.riyadm.wakenudge.missions.MissionProbe
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** What could stop tonight's alarm from working, most serious first: the first of a list is the one the notification names. */
enum class TonightProblemKind {
    /** Android holds no trigger for the alarm. Put right silently, so nobody is told. */
    TRIGGER_MISSING,
    SILENCED_BY_DND,
    EXACT_ALARMS_OFF,
    MISSION_NEEDS_PERMISSION,
    MISSION_NEEDS_NFC,
    MISSION_NOT_SET_UP,
    MISSION_NO_HARDWARE,
    NOTIFICATIONS_OFF,
    FULL_SCREEN_OFF,
    BACKGROUND_RESTRICTED,
    LOW_BATTERY,
}

/** [fix] is the Settings screen that puts it right; null means the alarm itself has to change, or there is nothing to open. */
data class TonightProblem(val kind: TonightProblemKind, val mission: MissionType? = null, val fix: HealthFixKind? = null)

/** What the phone says about one kind of mission. The defaults say all is well, which is also the answer when it will not say. */
data class MissionReadiness(val permissionMissing: Boolean = false, val nfcOff: Boolean = false, val hardwareMissing: Boolean = false)

/** What can stop one mission from running, worst first; the evening check and the check on saving share it. */
enum class MissionIssue { NO_HARDWARE, NOT_SET_UP, NEEDS_PERMISSION, NFC_OFF }

object MissionChecks {
    fun issue(mission: com.riyadm.wakenudge.domain.model.MissionConfig, ready: MissionReadiness): MissionIssue? = when {
        ready.hardwareMissing -> MissionIssue.NO_HARDWARE
        !mission.isConfigured -> MissionIssue.NOT_SET_UP
        ready.permissionMissing -> MissionIssue.NEEDS_PERMISSION
        ready.nfcOff -> MissionIssue.NFC_OFF
        else -> null
    }
}

/** A problem the editor finds when an alarm is saved: warned about and fixable there, never a reason to refuse the save. */
enum class PreflightKind { NO_HARDWARE, NOT_SET_UP, NEEDS_CAMERA, NFC_OFF, NEEDS_ACTIVITY, MOTION_WITH_SCREEN_READER }

data class PreflightProblem(val kind: PreflightKind, val mission: MissionType)

/**
 * The check on saving: the same mission checks as tonight's ([MissionChecks]), plus what is better asked for now than
 * found at ring time: the steps mission without the physical-activity permission (it then counts with the motion sensor,
 * which shaking fools), and a mission that needs the phone moved while a screen reader is on.
 */
object PreflightPolicy {
    private val MOTION = setOf(MissionType.SHAKE, MissionType.STEPS, MissionType.SQUATS)

    fun problems(
        alarm: Alarm,
        readiness: (MissionType) -> MissionReadiness,
        activityPermissionGranted: Boolean,
        screenReaderOn: Boolean,
    ): List<PreflightProblem> = buildList {
        for (mission in alarm.missions) {
            when (MissionChecks.issue(mission, readiness(mission.type))) {
                MissionIssue.NO_HARDWARE -> add(PreflightProblem(PreflightKind.NO_HARDWARE, mission.type))
                MissionIssue.NOT_SET_UP -> add(PreflightProblem(PreflightKind.NOT_SET_UP, mission.type))
                MissionIssue.NEEDS_PERMISSION -> add(PreflightProblem(PreflightKind.NEEDS_CAMERA, mission.type))
                MissionIssue.NFC_OFF -> add(PreflightProblem(PreflightKind.NFC_OFF, mission.type))
                null -> Unit
            }
            if (mission.type == MissionType.STEPS && !activityPermissionGranted) add(PreflightProblem(PreflightKind.NEEDS_ACTIVITY, mission.type))
            if (mission.type in MOTION && screenReaderOn) add(PreflightProblem(PreflightKind.MOTION_WITH_SCREEN_READER, mission.type))
        }
    }.distinct()
}

/**
 * "It didn't ring" is what people leave an alarm app over, so about 8 hours before an alarm it is checked while something
 * can still be done. A pure function of what the phone reports. Nothing here changes anything, and what it finds never
 * decides whether an alarm rings.
 */
object TonightCheckPolicy {
    /** The trigger for the silent look at the alarm's own trigger shortly before it rings, next to the evening check at index 0. */
    const val REARM_INDEX = 1
    const val REARM_BEFORE_MS = 30 * 60_000L

    fun problems(
        snapshot: HealthSnapshot,
        alarm: Alarm,
        alarmAt: Long,
        triggerArmed: Boolean,
        battery: BatteryReading?,
        readiness: (MissionType) -> MissionReadiness,
    ): List<TonightProblem> = buildList {
        if (!triggerArmed) add(TonightProblem(TonightProblemKind.TRIGGER_MISSING))
        if (silenced(snapshot)) add(TonightProblem(TonightProblemKind.SILENCED_BY_DND, fix = HealthFixKind.DND_SETTINGS))
        if (!snapshot.canScheduleExactAlarms) add(TonightProblem(TonightProblemKind.EXACT_ALARMS_OFF, fix = HealthFixKind.EXACT_ALARMS))
        // Every mission is looked at, not only the one tomorrow would pick: a rotation changes its mind every morning.
        for (mission in alarm.missions) {
            when (MissionChecks.issue(mission, readiness(mission.type))) {
                MissionIssue.NO_HARDWARE -> add(TonightProblem(TonightProblemKind.MISSION_NO_HARDWARE, mission.type))
                MissionIssue.NOT_SET_UP -> add(TonightProblem(TonightProblemKind.MISSION_NOT_SET_UP, mission.type))
                MissionIssue.NEEDS_PERMISSION -> add(TonightProblem(TonightProblemKind.MISSION_NEEDS_PERMISSION, mission.type, HealthFixKind.APP_DETAILS))
                MissionIssue.NFC_OFF -> add(TonightProblem(TonightProblemKind.MISSION_NEEDS_NFC, mission.type, HealthFixKind.NFC_SETTINGS))
                null -> Unit
            }
        }
        if (!snapshot.postNotificationsGranted || !snapshot.notificationsEnabled) {
            add(TonightProblem(TonightProblemKind.NOTIFICATIONS_OFF, fix = HealthFixKind.APP_NOTIFICATIONS))
        } else if (snapshot.ringChannelImportance == NotificationManager.IMPORTANCE_NONE) {
            add(TonightProblem(TonightProblemKind.NOTIFICATIONS_OFF, fix = HealthFixKind.RING_CHANNEL))
        }
        if (!snapshot.fullScreenIntentAllowed) add(TonightProblem(TonightProblemKind.FULL_SCREEN_OFF, fix = HealthFixKind.FULL_SCREEN_INTENT))
        if (snapshot.backgroundRestricted == true) add(TonightProblem(TonightProblemKind.BACKGROUND_RESTRICTED, fix = HealthFixKind.APP_DETAILS))
        if (LowBatteryPolicy.shouldWarn(battery, alarmAt, snapshot.nowMillis)) add(TonightProblem(TonightProblemKind.LOW_BATTERY))
    }.distinct().sortedBy { it.kind.ordinal }

    private fun silenced(s: HealthSnapshot): Boolean = when (s.interruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> !s.dndAllowsAlarms || s.alarmStreamMuted
        else -> false
    }

    /** When to re-register the trigger of an alarm at [alarmAt], or null if that moment has passed. */
    fun rearmAt(alarmAt: Long?, nowMs: Long): Long? = alarmAt?.minus(REARM_BEFORE_MS)?.takeIf { it > nowMs }
}

/**
 * Runs the check. It reads, and it tells: alarms are not written, and nothing here touches a ring. The one thing it does to
 * the outside world is register again, with [AlarmScheduler.rearm], a trigger for the time an alarm is already set for.
 * While a ring is on it stays out of the way altogether, and whatever fails here is logged and goes no further.
 */
@Singleton
class TonightCheck @Inject constructor(
    private val scheduler: AlarmScheduler,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
    private val pending: PendingAlarms,
    private val sessions: RingSessionStore,
    private val runtime: RuntimeState,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
    private val battery: BatteryGauge,
    private val health: HealthChecker,
    private val missions: MissionProbe,
) {
    /**
     * The evening look. A trigger that fires for an alarm already checked does nothing, so restarts and reschedules cannot
     * add wake-ups or repeat a notice. [askedByBedtime] is the bedtime reminder, which is on its way anyway and looks again.
     */
    suspend fun run(askedByBedtime: Boolean = false) = guarded("the check before tonight's alarm") {
        if (ringing()) return@guarded
        val next = scheduler.nextAlarm() ?: return@guarded
        val at = next.at.toInstant().toEpochMilli()
        if (!askedByBedtime && runtime.tonightCheckedFor == at) return@guarded
        val now = time.nowMillis()
        val armed = pending.isScheduled(TriggerKind.ALARM, next.alarm.id)
        if (!armed) rearm(next.alarm.id, at, "at the evening check")
        // Too soon, or more than a day to go: the check is about getting through tonight.
        if (at - now !in LowBatteryPolicy.MIN_AHEAD_MS..LowBatteryPolicy.MAX_AHEAD_MS) return@guarded
        runtime.tonightCheckedFor = at
        val warn = try {
            settings.current().lowBatteryWarning
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.warn("Tonight", "could not read whether to warn: ${t.message}")
            true
        }
        if (!warn) return@guarded
        val found = TonightCheckPolicy.problems(health.snapshot(), next.alarm, at, armed, battery.read(), missions::readiness)
            .filter { it.kind != TonightProblemKind.TRIGGER_MISSING }
        tell(next.alarm, at, found)
    }

    /** The silent look shortly before the alarm: register its trigger again, in case Android lost it. Nobody is told unless that fails. */
    suspend fun rearmBeforeAlarm() = guarded("re-arming the next alarm") {
        if (ringing()) return@guarded
        val next = scheduler.nextAlarm() ?: return@guarded
        val at = next.at.toInstant().toEpochMilli()
        if (at <= time.nowMillis()) return@guarded
        val armed = pending.isScheduled(TriggerKind.ALARM, next.alarm.id)
        rearm(next.alarm.id, at, if (armed) null else "shortly before it was due")
    }

    private fun tell(alarm: Alarm, at: Long, found: List<TonightProblem>) {
        val first = found.firstOrNull()
        when {
            first == null -> notifier.cancelTonightProblem()
            first.kind == TonightProblemKind.LOW_BATTERY -> {
                notifier.cancelTonightProblem()
                val reading = battery.read() ?: return
                diagnostics.warn("Battery", "${reading.percent}% and not charging before the alarm at $at")
                notifier.showLowBattery(reading.percent, java.time.Instant.ofEpochMilli(at).atZone(time.zone()).toLocalTime(), at)
            }
            else -> {
                diagnostics.warn("Tonight", "alarm ${alarm.id} at $at may not work: ${found.joinToString { it.kind.name + (it.mission?.let { m -> " ($m)" } ?: "") }}")
                // Without notifications this posts nothing. Alarm Health still shows that problem at the next look.
                notifier.showTonightProblem(alarm, at, first, found.size - 1)
            }
        }
    }

    private suspend fun rearm(alarmId: Long, at: Long, missingWhen: String?) {
        if (missingWhen != null) diagnostics.warn("Tonight", "alarm $alarmId had no trigger $missingWhen; registering it again")
        if (!scheduler.rearm(alarmId, at)) {
            diagnostics.error("Tonight", "alarm $alarmId could not be registered again: ${pending.lastFailure}")
            notifier.showSchedulingProblem(1)
        }
    }

    private fun ringing() = sessions.current?.phase == RingPhase.RINGING

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.error("Tonight", "$what failed; alarms are not affected", t)
        }
    }
}
