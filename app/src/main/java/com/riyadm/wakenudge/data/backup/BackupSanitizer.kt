package com.riyadm.wakenudge.data.backup

import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmGroup
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.AlarmDefaults
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.BedtimeSettings
import com.riyadm.wakenudge.domain.model.ExceptionDate
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.PuzzleKind
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import com.riyadm.wakenudge.domain.model.StopwatchState
import com.riyadm.wakenudge.domain.model.SunriseSettings
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VoiceSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.model.WorldClockCity
import com.riyadm.wakenudge.engine.RingIds
import com.riyadm.wakenudge.engine.audio.BundledTones
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Makes an untrusted [BackupFile] safe to store: every number is clamped into the range the engine and UI support,
 * enums were already coerced by the JSON layer, references are re-linked, strings are bounded and duplicates removed.
 * Valid data passes through unchanged, so a backup of this app restores to exactly what was exported.
 */
object BackupSanitizer {

    class Sanitized(val file: BackupFile, val warnings: List<ImportWarning>)

    /** A document ready to be stored, or the reason it must not be. Pure: this is the whole of "validate an import". */
    sealed interface Prepared {
        data class Ready(val file: BackupFile, val warnings: List<ImportWarning>) : Prepared
        data class Refused(val failure: ImportFailure) : Prepared
    }

    fun prepare(json: String): Prepared = when (val outcome = BackupCodec.parse(json)) {
        is ParseOutcome.Rejected -> Prepared.Refused(outcome.failure)
        is ParseOutcome.Parsed -> {
            val sanitized = sanitize(outcome.file)
            Prepared.Ready(sanitized.file, outcome.warnings + sanitized.warnings)
        }
    }

    const val MAX_ALARMS = 500
    const val MAX_GROUPS = 100
    const val MAX_WORLD_CLOCKS = 100
    const val MAX_EXCEPTION_DATES = 5_000
    const val MAX_HISTORY = 50_000
    const val MAX_LABEL = 60
    const val MAX_URI = 2_048
    const val MAX_PARAM_VALUE = 8_192

    private const val MAX_TONE_ID = 64
    private const val MAX_PLAYLIST = 50
    private const val MAX_RANDOM_POOL = 64
    private const val MAX_CUSTOM_TIMINGS = 64
    private const val MAX_PARAMS = 16
    private const val MAX_PARAM_KEY = 32
    private const val MAX_CALENDARS = 64
    private val EPOCH_DAYS = 0L..LocalDate.of(2200, 12, 31).toEpochDay()

    /** Upper bounds that stop absurd counts without restricting anything a person would choose. */
    private val MAX_MISSION_COUNT = mapOf(
        MissionType.MATH to 20, MissionType.MEMORY to 20, MissionType.TYPING to 10, MissionType.QR_BARCODE to 5,
        MissionType.NFC to 5, MissionType.SHAKE to 500, MissionType.STEPS to 1_000, MissionType.SQUATS to 200,
        MissionType.PUZZLE to 10, MissionType.PHOTO to 5,
    )

    fun sanitize(file: BackupFile): Sanitized {
        val run = Run()
        val groups = run.groups(file.groups)
        val groupIds = groups.mapTo(hashSetOf()) { it.id }
        val alarms = run.alarms(file.alarms, groupIds)
        val settings = run.settings(file.settings)
        val clocks = run.worldClocks(file.worldClocks)
        val dates = run.exceptionDates(file.exceptionDates)
        val history = file.history?.let(run::history)
        run.finish()
        val sanitized = file.copy(
            formatVersion = BackupFormat.VERSION,
            app = BackupFormat.APP_ID,
            appVersion = file.appVersion.take(MAX_LABEL),
            createdAt = file.createdAt.coerceAtLeast(0),
            settings = settings,
            alarms = alarms,
            groups = groups,
            worldClocks = clocks,
            exceptionDates = dates,
            history = history,
        )
        return Sanitized(sanitized, run.warnings)
    }

    private class Run {
        val warnings = mutableListOf<ImportWarning>()
        private var clamped = 0
        private var unknownGroups = 0
        private var renumbered = 0
        private var switchedOff = 0
        private var missionsDropped = 0
        private var deviceBoundSounds = 0

        fun warn(message: Int, vararg args: Any) {
            warnings += ImportWarning(message, args = args.toList())
        }

        fun warnCount(plural: Int, count: Int) {
            warnings += ImportWarning(plural, count)
        }

        fun finish() {
            if (unknownGroups > 0) warnCount(R.plurals.backup_warn_unknown_group, unknownGroups)
            if (renumbered > 0) warnCount(R.plurals.backup_warn_renumbered, renumbered)
            if (switchedOff > 0) warnCount(R.plurals.backup_warn_switched_off, switchedOff)
            if (missionsDropped > 0) warnCount(R.plurals.backup_warn_missions_dropped, missionsDropped)
            if (deviceBoundSounds > 0) warnCount(R.plurals.backup_warn_device_sounds, deviceBoundSounds)
            if (clamped > 0) warnCount(R.plurals.backup_warn_clamped, clamped)
        }

        // ---- primitives -------------------------------------------------------------------------------------

        fun int(value: Int, range: IntRange): Int = value.coerceIn(range).also { if (it != value) clamped++ }

        fun long(value: Long, range: LongRange): Long = value.coerceIn(range).also { if (it != value) clamped++ }

        fun text(value: String, max: Int): String {
            if (value.length <= max) return value
            clamped++
            val cut = value.take(max)
            // Never leave half of a surrogate pair (an emoji) at the end.
            return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
        }

        fun epochDay(value: Long?): Long? = value?.takeIf { it in EPOCH_DAYS }.also { if (value != null && it == null) clamped++ }

        // ---- alarms and their parts -------------------------------------------------------------------------

        fun alarms(source: List<Alarm>, groupIds: Set<Long>): List<Alarm> {
            if (source.size > MAX_ALARMS) warnCount(R.plurals.backup_warn_too_many_alarms, MAX_ALARMS)
            val cleaned = source.take(MAX_ALARMS).map { alarm(it, groupIds) }
            val ids = uniqueIds(cleaned.map { it.id })
            return cleaned.mapIndexed { index, alarm -> alarm.copy(id = ids[index]) }
        }

        /** Keeps every positive, first-seen id; the rest get fresh ones above the largest, in order. */
        private fun uniqueIds(ids: List<Long>): List<Long> {
            val taken = hashSetOf<Long>()
            val keep = ids.map { it > 0 && taken.add(it) }
            var next = (taken.maxOrNull() ?: 0L) + 1
            return ids.mapIndexed { index, id ->
                if (keep[index]) id else next++.also { renumbered++ }
            }
        }

        private fun alarm(a: Alarm, groupIds: Set<Long>): Alarm {
            val mask = a.repeatDaysMask and Alarm.EVERY_DAY_MASK
            if (mask != a.repeatDaysMask) clamped++
            var enabled = a.enabled
            val date = epochDay(a.oneTimeEpochDay)
            if (a.oneTimeEpochDay != null && date == null && mask == 0) {
                // A one-time alarm whose date is nonsense cannot be placed anywhere; leave it off rather than ring at a guess.
                if (enabled) switchedOff++
                enabled = false
            }
            val group = a.groupId?.let { id -> id.takeIf { it in groupIds }.also { if (it == null) unknownGroups++ } }
            val label = text(a.label, MAX_LABEL)
            val sound = sound(a.sound)
            noteDeviceBoundSound(sound)
            return a.copy(
                label = label,
                hour = int(a.hour, 0..23),
                minute = int(a.minute, 0..59),
                enabled = enabled,
                repeatDaysMask = mask,
                oneTimeEpochDay = date,
                groupId = group,
                sound = sound,
                volume = volume(a.volume),
                vibration = vibration(a.vibration),
                snooze = snooze(a.snooze),
                voice = voice(a.voice),
                sunrise = sunrise(a.sunrise),
                wakeCheck = wakeCheck(a.wakeCheck),
                antiOversleep = antiOversleep(a.antiOversleep),
                missions = missions(label, a, a.missions),
                snoozeMissions = missions(label, a, a.snoozeMissions),
                skipEpochDay = epochDay(a.skipEpochDay),
                upcomingNoticeMinutes = int(a.upcomingNoticeMinutes, 0..720),
                createdAt = long(a.createdAt, 0L..Long.MAX_VALUE),
                updatedAt = long(a.updatedAt, 0L..Long.MAX_VALUE),
            )
        }

        private fun displayName(label: String, alarm: Alarm): String =
            label.ifBlank { String.format(Locale.ROOT, "%02d:%02d", alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59)) }

        /** Content URIs name files and ringtones of the device that made the backup; they may not exist here. */
        private fun noteDeviceBoundSound(sound: SoundSettings) {
            val own = if (sound.source == SoundSource.BUNDLED) null else sound.uri
            if ((listOfNotNull(own) + sound.playlist).any { it.startsWith("content://") }) deviceBoundSounds++
        }

        fun sound(s: SoundSettings): SoundSettings {
            val uri = s.uri?.takeIf { it.isNotBlank() && it.length <= MAX_URI }
            if (s.uri != null && uri == null) clamped++
            val playlist = s.playlist.filter { it.isNotBlank() && it.length <= MAX_URI }.take(MAX_PLAYLIST)
            if (playlist.size != s.playlist.size) clamped++
            val pool = s.randomPool.filter { it.length in 1..MAX_TONE_ID }.take(MAX_RANDOM_POOL).toSet()
            val source = if (s.source != SoundSource.BUNDLED && uri == null) SoundSource.BUNDLED else s.source
            val mode = if (s.mode == SoundMode.RANDOM_CUSTOM && playlist.isEmpty()) SoundMode.SINGLE else s.mode
            return s.copy(
                mode = mode,
                source = source,
                bundledId = BundledTones.byId(s.bundledId)?.id ?: SoundSettings.DEFAULT_TONE_ID,
                uri = uri,
                playlist = playlist,
                randomPool = pool,
            )
        }

        private fun volume(v: VolumeSettings) = v.copy(
            level = int(v.level, 0..100),
            rampSeconds = int(v.rampSeconds, 0..VolumeSettings.MAX_RAMP_SECONDS),
            rampStartPercent = int(v.rampStartPercent, 0..100),
            extraLoudPercent = int(v.extraLoudPercent, 1..100),
        )

        private fun vibration(v: VibrationSettings): VibrationSettings {
            val timings = v.customTimings.take(MAX_CUSTOM_TIMINGS).map { long(it, 0L..10_000L) }
            // A custom pattern needs at least one pause/pulse pair that actually vibrates.
            val usableCustom = timings.size >= 2 && timings.any { it > 0 }
            val pattern = if (v.pattern == VibrationPattern.CUSTOM && !usableCustom) VibrationPattern.PULSE else v.pattern
            return v.copy(strength = int(v.strength, 1..100), customTimings = timings, pattern = pattern)
        }

        private fun snooze(s: SnoozeSettings) = s.copy(
            minutes = int(s.minutes, 1..60),
            maxCount = int(s.maxCount, 0..99),
            decreaseStepMinutes = int(s.decreaseStepMinutes, 1..30),
        )

        private fun voice(v: VoiceSettings) = v.copy(repeatSeconds = int(v.repeatSeconds, 5..600))

        private fun sunrise(s: SunriseSettings) = s.copy(leadMinutes = int(s.leadMinutes, 1..120))

        private fun wakeCheck(w: WakeCheckSettings) = w.copy(
            delayMinutes = int(w.delayMinutes, 1..30),
            responseSeconds = int(w.responseSeconds, 10..600),
            maxChecks = int(w.maxChecks, 1..5),
            maxReRings = int(w.maxReRings, 0..10),
        )

        private fun antiOversleep(a: AntiOversleepSettings) = a.copy(
            holdToDismissSeconds = int(a.holdToDismissSeconds, 0..30),
            backupDelayMinutes = int(a.backupDelayMinutes, 1..60),
            backupCount = int(a.backupCount, 0..RingIds.MAX_BACKUPS),
            editLockMinutes = int(a.editLockMinutes, 0..120),
            ringTimeoutMinutes = int(a.ringTimeoutMinutes, 1..60),
        )

        private fun missions(label: String, owner: Alarm, source: List<MissionConfig>): List<MissionConfig> {
            if (source.size > MissionConfig.MAX_MISSIONS) missionsDropped += source.size - MissionConfig.MAX_MISSIONS
            return source.take(MissionConfig.MAX_MISSIONS).map { mission ->
                val cleaned = mission.copy(
                    count = int(mission.count, 1..(MAX_MISSION_COUNT[mission.type] ?: 20)),
                    params = params(mission),
                )
                if (!cleaned.isConfigured) {
                    val message = when (cleaned.type) {
                        MissionType.QR_BARCODE -> R.string.backup_warn_mission_qr
                        MissionType.NFC -> R.string.backup_warn_mission_nfc
                        else -> R.string.backup_warn_mission_photo
                    }
                    warn(message, displayName(label, owner))
                }
                cleaned
            }
        }

        private fun params(mission: MissionConfig): Map<String, String> {
            // A value that is too long is dropped, never cut: a truncated code or signature would silently never match.
            val kept = mission.params.entries
                .filter { (key, value) -> key.length in 1..MAX_PARAM_KEY && value.length <= MAX_PARAM_VALUE }
                .take(MAX_PARAMS)
                .associate { it.key to it.value }
            if (kept.size != mission.params.size) clamped++
            val puzzle = kept[MissionConfig.PARAM_PUZZLE]
            return if (puzzle != null && PuzzleKind.entries.none { it.name == puzzle }) {
                clamped++
                kept - MissionConfig.PARAM_PUZZLE
            } else {
                kept
            }
        }

        // ---- everything else --------------------------------------------------------------------------------

        fun groups(source: List<AlarmGroup>): List<AlarmGroup> {
            val cleaned = source.take(MAX_GROUPS).map { group ->
                group.copy(
                    name = text(group.name, MAX_LABEL),
                    accent = int(group.accent, 0..99),
                    sortOrder = int(group.sortOrder, -10_000..10_000),
                )
            }
            val ids = uniqueIds(cleaned.map { it.id })
            return cleaned.mapIndexed { index, group -> group.copy(id = ids[index]) }
        }

        fun settings(s: AppSettings): AppSettings {
            if (s.calendarExceptions.enabled && s.calendarExceptions.calendarIds.isNotEmpty()) {
                warn(R.string.backup_warn_calendars)
            }
            return s.copy(
                vacation = VacationMode(s.vacation.enabled, epochDay(s.vacation.resumeEpochDay)),
                calendarExceptions = s.calendarExceptions.copy(calendarIds = s.calendarExceptions.calendarIds.take(MAX_CALENDARS).toSet()),
                bedtime = bedtime(s.bedtime),
                defaults = AlarmDefaults(
                    sound = sound(s.defaults.sound),
                    volume = volume(s.defaults.volume),
                    vibration = vibration(s.defaults.vibration),
                    snooze = snooze(s.defaults.snooze),
                    antiOversleep = antiOversleep(s.defaults.antiOversleep),
                ),
                headphoneCapPercent = int(s.headphoneCapPercent, 10..100),
                upcomingNoticeMinutes = int(s.upcomingNoticeMinutes, 0..720),
                timerSound = sound(s.timerSound),
                // A running stopwatch is a moment in time, not configuration.
                stopwatch = StopwatchState(),
                emergencyStopHoldSeconds = int(s.emergencyStopHoldSeconds, 1..10),
            ).withoutUpdateState() // Installation state (see AppSettings.withUpdateStateOf); a file must not dictate it.
        }

        private fun bedtime(b: BedtimeSettings): BedtimeSettings {
            val mask = b.daysMask and Alarm.EVERY_DAY_MASK
            if (mask != b.daysMask) clamped++
            return b.copy(
                hour = int(b.hour, 0..23),
                minute = int(b.minute, 0..59),
                daysMask = mask,
                reminderLeadMinutes = int(b.reminderLeadMinutes, 0..240),
                sleepGoalMinutes = int(b.sleepGoalMinutes, 120..1_080),
                fallAsleepMinutes = int(b.fallAsleepMinutes, 0..120),
            )
        }

        fun worldClocks(source: List<WorldClockCity>): List<WorldClockCity> {
            val seen = hashSetOf<String>()
            val result = mutableListOf<WorldClockCity>()
            for (city in source) {
                val zoneOk = city.zoneId.length <= MAX_LABEL && runCatching { ZoneId.of(city.zoneId) }.isSuccess
                if (zoneOk && result.size < MAX_WORLD_CLOCKS && seen.add(city.zoneId)) {
                    result += city.copy(label = text(city.label, MAX_LABEL), sortOrder = int(city.sortOrder, -10_000..10_000))
                }
            }
            if (result.size != source.size) warnCount(R.plurals.backup_warn_clocks_skipped, source.size - result.size)
            return result
        }

        fun exceptionDates(source: List<ExceptionDate>): List<ExceptionDate> {
            val seen = hashSetOf<Long>()
            val result = mutableListOf<ExceptionDate>()
            for (date in source) {
                if (date.epochDay in EPOCH_DAYS && result.size < MAX_EXCEPTION_DATES && seen.add(date.epochDay)) {
                    result += date.copy(label = text(date.label, MAX_LABEL))
                }
            }
            if (result.size != source.size) warnCount(R.plurals.backup_warn_dates_skipped, source.size - result.size)
            return result
        }

        fun history(source: List<AlarmEvent>): List<AlarmEvent> {
            val newestFirst = if (source.size > MAX_HISTORY) {
                warnCount(R.plurals.backup_warn_history_truncated, MAX_HISTORY)
                source.sortedByDescending { it.firedAt }.take(MAX_HISTORY)
            } else {
                source
            }
            val seen = hashSetOf<Long>()
            return newestFirst.map { event ->
                val firedAt = long(event.firedAt, 0L..Long.MAX_VALUE)
                val total = int(event.missionsTotal, 0..20)
                // An event still marked as ringing belongs to a session that no longer exists; close it as missed.
                val open = event.outcome == AlarmOutcome.RINGING
                event.copy(
                    id = event.id.takeIf { it > 0 && seen.add(it) } ?: 0,
                    label = text(event.label, MAX_LABEL),
                    scheduledAt = long(event.scheduledAt, 0L..Long.MAX_VALUE),
                    firedAt = firedAt,
                    endedAt = if (open) event.endedAt ?: firedAt else event.endedAt?.let { long(it, 0L..Long.MAX_VALUE) },
                    outcome = if (open) AlarmOutcome.MISSED else event.outcome,
                    snoozeCount = int(event.snoozeCount, 0..999),
                    missionsDone = int(event.missionsDone, 0..total),
                    missionsTotal = total,
                    wakeCheckReRings = int(event.wakeCheckReRings, 0..99),
                )
            }
        }
    }
}
