package com.riyadm.wakenudge.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
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
        scope.launch {
            try {
                toggleVacation(surfaceEntry())
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
        if (settings.vacation.enabled) {
            val resume = settings.vacation.resumeEpochDay?.let(LocalDate::ofEpochDay)
            val subtitle = resume
                ?.let { getString(R.string.tile_subtitle_resumes, text.date(it)) }
                ?: getString(R.string.tile_subtitle_vacation)
            return TileState(vacation = true, subtitle = subtitle)
        }
        return TileState(vacation = false, subtitle = nextAlarmSubtitle(entry.outlook().load(), text, entry.time().now()))
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

    private class TileState(val vacation: Boolean, val subtitle: String)

    private companion object {
        const val TAG = "WakeNudge"
    }
}

/**
 * Flips vacation mode, keeping the user's chosen resume day. A resume day that has already passed would make
 * "vacation on" a silent no-op, so enabling clears it (meaning "until I switch it off").
 */
internal suspend fun toggleVacation(entry: SurfaceEntryPoint) {
    val today = entry.time().now().toLocalDate().toEpochDay()
    entry.settings().update { it.copy(vacation = it.vacation.toggled(today)) }
    entry.scheduler().rescheduleAll("tile")
    entry.surfaces().refreshAll()
}

internal fun VacationMode.toggled(todayEpochDay: Long): VacationMode {
    val enabling = !enabled
    val staleResume = resumeEpochDay != null && resumeEpochDay <= todayEpochDay
    return copy(enabled = enabling, resumeEpochDay = if (enabling && staleResume) null else resumeEpochDay)
}
