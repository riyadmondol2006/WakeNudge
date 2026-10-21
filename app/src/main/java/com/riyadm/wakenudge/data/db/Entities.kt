package com.riyadm.wakenudge.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SunriseSettings
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VoiceSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.model.WorldClockCity
import kotlinx.serialization.builtins.ListSerializer

private val missionListSerializer = ListSerializer(MissionConfig.serializer())

private fun encodeMissions(list: List<MissionConfig>): String =
    WakeJson.instance.encodeToString(missionListSerializer, list)

private fun decodeMissions(json: String): List<MissionConfig> =
    runCatching { WakeJson.instance.decodeFromString(missionListSerializer, json) }.getOrDefault(emptyList())

@Entity(tableName = "alarms", indices = [Index("groupId")])
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val repeatDaysMask: Int,
    val oneTimeEpochDay: Long?,
    val groupId: Long?,
    @Embedded(prefix = "snd_") val sound: SoundSettings,
    @Embedded(prefix = "vol_") val volume: VolumeSettings,
    @Embedded(prefix = "vib_") val vibration: VibrationSettings,
    @Embedded(prefix = "snz_") val snooze: SnoozeSettings,
    @Embedded(prefix = "voc_") val voice: VoiceSettings,
    @Embedded(prefix = "sun_") val sunrise: SunriseSettings,
    @Embedded(prefix = "wc_") val wakeCheck: WakeCheckSettings,
    @Embedded(prefix = "ao_") val antiOversleep: AntiOversleepSettings,
    val missionsJson: String,
    val snoozeMissionsJson: String,
    val skipEpochDay: Long?,
    val respectExceptions: Boolean,
    val ignoreVacation: Boolean,
    val upcomingNoticeMinutes: Int,
    val deleteAfterUse: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

fun AlarmEntity.toDomain() = Alarm(
    id = id, label = label, hour = hour, minute = minute, enabled = enabled,
    repeatDaysMask = repeatDaysMask, oneTimeEpochDay = oneTimeEpochDay, groupId = groupId,
    sound = sound, volume = volume, vibration = vibration, snooze = snooze, voice = voice,
    sunrise = sunrise, wakeCheck = wakeCheck, antiOversleep = antiOversleep,
    missions = decodeMissions(missionsJson), snoozeMissions = decodeMissions(snoozeMissionsJson),
    skipEpochDay = skipEpochDay, respectExceptions = respectExceptions, ignoreVacation = ignoreVacation,
    upcomingNoticeMinutes = upcomingNoticeMinutes, deleteAfterUse = deleteAfterUse,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun Alarm.toEntity() = AlarmEntity(
    id = id, label = label, hour = hour, minute = minute, enabled = enabled,
    repeatDaysMask = repeatDaysMask, oneTimeEpochDay = oneTimeEpochDay, groupId = groupId,
    sound = sound, volume = volume, vibration = vibration, snooze = snooze, voice = voice,
    sunrise = sunrise, wakeCheck = wakeCheck, antiOversleep = antiOversleep,
    missionsJson = encodeMissions(missions), snoozeMissionsJson = encodeMissions(snoozeMissions),
    skipEpochDay = skipEpochDay, respectExceptions = respectExceptions, ignoreVacation = ignoreVacation,
    upcomingNoticeMinutes = upcomingNoticeMinutes, deleteAfterUse = deleteAfterUse,
    createdAt = createdAt, updatedAt = updatedAt,
)

@Entity(tableName = "alarm_groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val accent: Int,
    val sortOrder: Int,
)

fun GroupEntity.toDomain() = AlarmGroup(id, name, enabled, accent, sortOrder)
fun AlarmGroup.toEntity() = GroupEntity(id, name, enabled, accent, sortOrder)

@Entity(tableName = "alarm_events", indices = [Index("alarmId", "scheduledAt"), Index("firedAt")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: Long,
    val label: String,
    val scheduledAt: Long,
    val firedAt: Long,
    val endedAt: Long?,
    val outcome: AlarmOutcome,
    val snoozeCount: Int,
    val missionsDone: Int,
    val missionsTotal: Int,
    val backupFired: Boolean,
    val wakeCheck: WakeCheckStatus,
    val wakeCheckReRings: Int,
)

fun EventEntity.toDomain() = AlarmEvent(
    id, alarmId, label, scheduledAt, firedAt, endedAt, outcome, snoozeCount, missionsDone, missionsTotal,
    backupFired, wakeCheck, wakeCheckReRings,
)

fun AlarmEvent.toEntity() = EventEntity(
    id, alarmId, label, scheduledAt, firedAt, endedAt, outcome, snoozeCount, missionsDone, missionsTotal,
    backupFired, wakeCheck, wakeCheckReRings,
)

@Entity(tableName = "timers")
data class TimerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val durationMs: Long,
    val state: TimerState,
    val endsAtMillis: Long,
    val remainingMs: Long,
    val createdAt: Long,
)

fun TimerEntity.toDomain() = CountdownTimer(id, label, durationMs, state, endsAtMillis, remainingMs, createdAt)
fun CountdownTimer.toEntity() = TimerEntity(id, label, durationMs, state, endsAtMillis, remainingMs, createdAt)

@Entity(tableName = "world_clocks")
data class WorldClockEntity(
    @PrimaryKey val zoneId: String,
    val label: String,
    val sortOrder: Int,
)

fun WorldClockEntity.toDomain() = WorldClockCity(zoneId, label, sortOrder)
fun WorldClockCity.toEntity() = WorldClockEntity(zoneId, label, sortOrder)

@Entity(tableName = "exception_dates")
data class ExceptionEntity(
    @PrimaryKey val epochDay: Long,
    val label: String,
)

fun ExceptionEntity.toDomain() = ExceptionDate(epochDay, label)
fun ExceptionDate.toEntity() = ExceptionEntity(epochDay, label)

/** At most one follow-up per alarm, hence the alarm id as primary key. */
@Entity(tableName = "wake_checks")
data class WakeCheckEntity(
    @PrimaryKey val alarmId: Long,
    val eventId: Long,
    val phase: WakeCheck.Phase,
    val promptAt: Long,
    val deadlineAt: Long,
    val checksDone: Int,
    val reRings: Int,
)

fun WakeCheckEntity.toDomain() = WakeCheck(alarmId, eventId, phase, promptAt, deadlineAt, checksDone, reRings)
fun WakeCheck.toEntity() = WakeCheckEntity(alarmId, eventId, phase, promptAt, deadlineAt, checksDone, reRings)

/** Ring buffer of engine diagnostics, shown on the Alarm Health screen. */
@Entity(tableName = "diagnostics", indices = [Index("timestamp")])
data class DiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
)
