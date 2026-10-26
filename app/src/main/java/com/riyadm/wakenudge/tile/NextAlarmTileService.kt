package com.riyadm.wakenudge.tile

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.widget.AlarmOutlook
import com.riyadm.wakenudge.widget.SurfaceEntryPoint
import com.riyadm.wakenudge.widget.SurfaceText
import com.riyadm.wakenudge.widget.surfaceEntry
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile that doubles as the vacation-mode switch: active while alarms are on, inactive while vacation
 * mode pauses them. The subtitle (Android 10+) shows when the next alarm rings. It is an "active tile": the system
 * only binds it when [com.riyadm.wakenudge.widget.SurfaceRefresherImpl] requests a listening state (or the user taps
 * it), which is why the subtitle never uses relative days that would go stale while the tile is not bound.
 */
class NextAlarmTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileAdded() = refresh()

    override fun onStartListening() = refresh()

    override fun onClick() {
        // Pausing every alarm is not something to do from a locked phone half asleep: ask for the unlock first.
        if (isLocked) unlockAndRun { confirmThenToggle() } else confirmThenToggle()
    }

    /**
     * Resuming is one tap. Pausing asks first: the tile sits where a finger pulling down the shade lands, and a stray
     * tap would otherwise leave tomorrow's alarm silent with nothing on screen to say so.
     */
    private fun confirmThenToggle() {
        scope.launch {
            val state = try {
                withContext(Dispatchers.Default) { loadState(surfaceEntry()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "reading the tile state failed", t)
                return@launch
            }
            if (state.vacation) {
                toggle()
                return@launch
            }
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val theme = if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            val message = state.nextTime?.let { getString(R.string.tile_pause_text_next, it) } ?: getString(R.string.tile_pause_text)
            val dialog = AlertDialog.Builder(this@NextAlarmTileService, theme)
                .setTitle(R.string.tile_pause_title)
                .setMessage(message)
                .setPositiveButton(R.string.tile_pause_confirm) { _, _ -> toggle() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            // A system that will not show it (the panel closed under us) must not leave the tile doing nothing.
            runCatching { showDialog(dialog) }.onFailure {
                Log.w(TAG, "the tile could not show its confirmation; pausing directly", it)
                toggle()
            }
        }
    }

    private fun toggle() {
        scope.launch {
            try {
                // Finishes even if the system unbinds the tile half-way: a vacation flag saved without the reschedule
                // that goes with it would leave alarms that look paused but are armed, or the other way round.
                val locked = withContext(NonCancellable) { toggleVacation(surfaceEntry()) }
                if (locked != null) {
                    Toast.makeText(applicationContext, resources.getQuantityString(R.plurals.widget_locked_minutes, locked, locked), Toast.LENGTH_LONG).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "toggling vacation mode from the tile failed", t)
            }
            render()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch { render() }
    }

    private suspend fun render() {
        val state = try {
            withContext(Dispatchers.Default) { loadState(surfaceEntry()) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "reading the tile state failed", t)
            return
        }
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_alarm)
        tile.state = if (state.vacation) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = state.subtitle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = state.subtitle
            tile.contentDescription = getString(if (state.vacation) R.string.tile_description_vacation else R.string.tile_description)
        }
        tile.updateTile()
    }

    private suspend fun loadState(entry: SurfaceEntryPoint): TileState {
        val settings = entry.settings().current()
        val text = SurfaceText(this, settings.timeFormat)
        // A vacation whose resume day has come is over even before the scheduler clears the flag.
        if (settings.vacation.activeOn(entry.time().now().toLocalDate().toEpochDay())) {
            val resume = settings.vacation.resumeEpochDay?.let(LocalDate::ofEpochDay)
            val subtitle = resume
                ?.let { getString(R.string.tile_subtitle_resumes, text.date(it)) }
                ?: getString(R.string.tile_subtitle_vacation)
            return TileState(vacation = true, subtitle = subtitle)
        }
        val outlook = entry.outlook().load()
        return TileState(
            vacation = false,
            subtitle = nextAlarmSubtitle(outlook, text, entry.time().now()),
            nextTime = outlook.next?.let { text.time(it.at.toLocalTime()) },
        )
    }

    private fun nextAlarmSubtitle(outlook: AlarmOutlook, text: SurfaceText, now: java.time.ZonedDateTime): String {
        val next = outlook.next ?: return getString(R.string.tile_subtitle_no_alarms)
        val time = text.time(next.at.toLocalTime())
        // Within a day the time alone is unambiguous; further out the weekday is added.
        return if (Duration.between(now, next.at) < Duration.ofHours(24)) {
            time
        } else {
            getString(R.string.tile_subtitle_next_day, text.shortWeekday(next.at.dayOfWeek), time)
        }
    }

    private class TileState(val vacation: Boolean, val subtitle: String, val nextTime: String? = null)

    private companion object {
        const val TAG = "WakeNudge"
    }
}

/**
 * Flips vacation mode, keeping the user's chosen resume day. A resume day that has already passed would make
 * "vacation on" a silent no-op, so enabling clears it (meaning "until I switch it off").
 *
 * Pausing is refused while an alarm is inside its last-minute edit protection, as switching that alarm off would be
 * from the app or a widget; the result is then the minutes left on the lock, otherwise null.
 */
internal suspend fun toggleVacation(entry: SurfaceEntryPoint): Int? {
    val today = entry.time().now().toLocalDate().toEpochDay()
    val pausing = !entry.settings().current().vacation.activeOn(today)
    if (pausing) {
        val actions = entry.alarmActions()
        val locked = entry.alarms().getAlarms().filter { it.enabled && !it.ignoreVacation }.firstNotNullOfOrNull { actions.lockedMinutes(it) }
        if (locked != null) return locked
    }
    entry.settings().update { it.copy(vacation = it.vacation.toggled(today)) }
    entry.scheduler().rescheduleAll("tile")
    entry.surfaces().refreshAll()
    return null
}

internal fun VacationMode.toggled(todayEpochDay: Long): VacationMode {
    // What the tile shows: a vacation past its resume day counts as off, so a tap on it pauses again.
    val enabling = !activeOn(todayEpochDay)
    val staleResume = resumeEpochDay != null && resumeEpochDay <= todayEpochDay
    return copy(enabled = enabling, resumeEpochDay = if (enabling && staleResume) null else resumeEpochDay)
}
