package com.riyadm.wakenudge.data.backup

import androidx.room.withTransaction
import com.riyadm.wakenudge.data.db.WakeNudgeDatabase
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.ExceptionRepository
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.domain.repository.WorldClockRepository
import com.riyadm.wakenudge.engine.PendingAlarms
import com.riyadm.wakenudge.engine.RingIds
import com.riyadm.wakenudge.engine.TriggerKind
import com.riyadm.wakenudge.engine.notify.Notifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a backup is read from and written to. An interface so the manager's validation and orchestration can be
 * tested against an in-memory store; the Room-backed implementation is the only one the app uses.
 */
interface BackupStore {
    /** The current data as a backup document (metadata left for the manager to fill in). */
    suspend fun read(includeHistory: Boolean): BackupFile

    /**
     * Replaces everything the document carries in one atomic step: either all of it is stored or none of it. A null
     * [BackupFile.history] leaves the stored history alone. Pending follow-ups of the old alarms are retired.
     */
    suspend fun replace(file: BackupFile)
}

@Singleton
class RoomBackupStore @Inject constructor(
    private val db: WakeNudgeDatabase,
    private val settings: SettingsRepository,
    private val alarms: AlarmRepository,
    private val groups: GroupRepository,
    private val worldClocks: WorldClockRepository,
    private val exceptions: ExceptionRepository,
    private val history: HistoryRepository,
    private val wakeChecks: WakeCheckRepository,
    private val pending: PendingAlarms,
    private val notifier: Notifier,
) : BackupStore {

    override suspend fun read(includeHistory: Boolean): BackupFile = db.withTransaction {
        BackupFile(
            settings = settings.current(),
            alarms = alarms.getAlarms(),
            groups = groups.getGroups(),
            worldClocks = worldClocks.getCities(),
            exceptionDates = exceptions.getDates(),
            // Capped so a backup can always be restored: the import refuses files over 16 MB, which about 30,000 events reach.
            history = if (includeHistory) history.recent(MAX_EXPORTED_EVENTS) else null,
        )
    }

    override suspend fun replace(file: BackupFile) {
        val previousIds = alarms.getAlarms().map { it.id }
        val previousSettings = settings.current()
        var settingsWritten = false
        try {
            db.withTransaction {
                groups.replaceAll(file.groups)
                alarms.replaceAll(file.alarms)
                worldClocks.replaceAll(file.worldClocks)
                exceptions.replaceAll(file.exceptionDates)
                if (file.history != null) {
                    history.replaceAll(file.history)
                } else {
                    // The history stays, but its pending snoozes and backups belonged to the alarms just replaced. Ids
                    // start at 1 on every phone, so a restored alarm usually has the id of an old one, and a reschedule
                    // would otherwise re-arm the old alarm's snooze and ring the restored alarm with it.
                    for (e in history.withFollowUps()) {
                        history.update(
                            e.copy(
                                snoozeUntil = 0, pendingBackups = emptyList(),
                                outcome = if (e.outcome == AlarmOutcome.RINGING) AlarmOutcome.MISSED else e.outcome,
                                endedAt = e.endedAt ?: e.firedAt,
                            ),
                        )
                    }
                }
                // Their follow-up state described alarms that were just replaced.
                wakeChecks.clear()
                // Last, so a failure to write the settings rolls the database changes back too. Update-check state stays as
                // this installation has it: a restored file must not switch checks on or off or forget a skipped version.
                settings.update { installed -> file.settings.withUpdateStateOf(installed) }
                settingsWritten = true
            }
        } catch (t: Throwable) {
            // The settings live outside the database and are not rolled back with it: put them back by hand, so a
            // restore that failed changed nothing, as the result says.
            if (settingsWritten) runCatching { settings.replace(previousSettings) }
            throw t
        }
        (previousIds + file.alarms.map { it.id }).distinct().forEach(::retireFollowUps)
    }

    private companion object {
        const val MAX_EXPORTED_EVENTS = 15_000
    }

    /** Snoozes, backups, wake checks and their notifications belong to the alarm that was replaced. */
    private fun retireFollowUps(alarmId: Long) {
        pending.cancel(TriggerKind.SNOOZE, alarmId)
        pending.cancel(TriggerKind.WAKE_CHECK_PROMPT, alarmId)
        pending.cancel(TriggerKind.WAKE_CHECK_DEADLINE, alarmId)
        for (index in 1..RingIds.MAX_BACKUPS) pending.cancel(TriggerKind.BACKUP, alarmId, index)
        notifier.cancelSnoozed(alarmId)
        notifier.cancelUpcoming(alarmId)
        notifier.cancelWakeCheck(alarmId)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds abstract fun store(impl: RoomBackupStore): BackupStore
}
