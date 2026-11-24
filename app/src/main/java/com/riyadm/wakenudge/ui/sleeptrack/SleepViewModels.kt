package com.riyadm.wakenudge.ui.sleeptrack

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.sleep.NightDetail
import com.riyadm.wakenudge.data.sleep.SleepPrefs
import com.riyadm.wakenudge.data.sleep.SleepRepository
import com.riyadm.wakenudge.data.sleep.SnoreClip
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.sleep.Night
import com.riyadm.wakenudge.domain.sleep.SleepWeeks
import com.riyadm.wakenudge.engine.sleeptrack.ClipPlayer
import com.riyadm.wakenudge.engine.sleeptrack.MovementSensor
import com.riyadm.wakenudge.engine.sleeptrack.SleepCleanup
import com.riyadm.wakenudge.engine.sleeptrack.SleepTrackService
import com.riyadm.wakenudge.engine.sleeptrack.SleepTracker
import com.riyadm.wakenudge.engine.sleeptrack.TrackNotice
import com.riyadm.wakenudge.engine.sleeptrack.TrackStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Listen, share and delete for snore clips; shared by every screen that shows one. */
class ClipActions(
    private val context: Context,
    private val repo: SleepRepository,
    private val player: ClipPlayer,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    val playing: StateFlow<Long?> = player.playing

    /** Returns false when the clip could not be played. */
    fun toggle(clip: SnoreClip): Boolean {
        if (player.playing.value == clip.id) {
            player.stop()
            return true
        }
        return player.play(clip.id, repo.clipFile(clip))
    }

    /** The share sheet for one clip, or null when its file is gone. Only ever opened by the Share button. */
    fun shareIntent(clip: SnoreClip, title: String): Intent? {
        val copy = repo.shareCopy(clip) ?: return null
        val uri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".sleepclips", copy) }.getOrNull() ?: return null
        val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }

    fun delete(clip: SnoreClip, after: () -> Unit = {}) {
        if (player.playing.value == clip.id) player.stop()
        launch {
            repo.deleteClip(clip.id)
            after()
        }
    }

    fun stop() = player.stop()
}

data class TrackUiState(
    val status: TrackStatus = TrackStatus.Idle,
    val lastNight: Night? = null,
    val recordSnoring: Boolean = false,
    val clipCount: Int = 0,
    val notice: TrackNotice? = null,
)

@HiltViewModel
class SleepTrackViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracker: SleepTracker,
    private val repo: SleepRepository,
    private val prefs: SleepPrefs,
    private val cleanup: SleepCleanup,
    private val time: TimeSource,
    settings: SettingsRepository,
) : ViewModel() {
    val hasSensor: Boolean = MovementSensor.available(context)

    val settings: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<TrackUiState> = combine(
        tracker.status, repo.observeLastNight(), prefs.recordSnoring, repo.observeClips { time.nowMillis() }, tracker.notice,
    ) { status, last, snoring, clips, notice -> TrackUiState(status, last, snoring, clips.size, notice) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackUiState(status = tracker.status.value, recordSnoring = prefs.recordSnoring.value))

    init {
        viewModelScope.launch {
            runCatching {
                if (!tracker.isActive) repo.closeStale(runningId = null)
                cleanup.run(runningId = if (tracker.isActive) -1 else null)
            }
        }
    }

    fun setRecordSnoring(on: Boolean) = prefs.setRecordSnoring(on)

    /** Returns false when Android refused to start the service at all. */
    fun start(withMic: Boolean): Boolean {
        val ok = SleepTrackService.start(context, withMic)
        if (!ok) tracker.report(TrackNotice.REFUSED)
        return ok
    }

    fun stop() = SleepTrackService.stop(context)

    fun consumeNotice() = tracker.consumeNotice()

    fun deleteAllClips() {
        viewModelScope.launch { runCatching { repo.deleteAllClips() } }
    }
}

data class SleepWeekState(
    val loaded: Boolean = false,
    val hasAny: Boolean = false,
    val weekStart: LocalDate = LocalDate.MIN,
    val today: LocalDate = LocalDate.MIN,
    val canGoForward: Boolean = false,
    val days: List<LocalDate> = emptyList(),
    val nights: List<Night> = emptyList(),
    val main: Map<LocalDate, Night> = emptyMap(),
    val averages: SleepWeeks.Averages = SleepWeeks.Averages(0, null, null, null),
    val loudest: SnoreClip? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
)

@HiltViewModel
class SleepReportViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repo: SleepRepository,
    private val time: TimeSource,
    player: ClipPlayer,
    settings: SettingsRepository,
) : ViewModel() {
    val clips = ClipActions(context, repo, player) { block -> viewModelScope.launch { runCatching { block() } } }

    /** Weeks back from this one: 0 is this week. */
    private val weeksBack = MutableStateFlow(0)
    private val firstDay = MutableStateFlow<DayOfWeek?>(null)

    val settings: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set by the screen from the locale and the app's "week starts on" setting. */
    fun setFirstDay(day: DayOfWeek) {
        firstDay.value = day
    }

    fun previousWeek() = weeksBack.update { it + 1 }
    fun nextWeek() = weeksBack.update { (it - 1).coerceAtLeast(0) }

    private fun MutableStateFlow<Int>.update(f: (Int) -> Int) {
        value = f(value)
    }

    val state: StateFlow<SleepWeekState> = combine(weeksBack, firstDay) { back, first -> back to first }
        .flatMapLatest { (back, first) ->
            val zone = time.zone()
            val today = time.now().toLocalDate()
            val day = first ?: DayOfWeek.MONDAY
            val start = SleepWeeks.weekStart(today, day).minusWeeks(back.toLong())
            val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            combine(repo.observeNights(from, to), repo.observeHasAny(), repo.observeClips { time.nowMillis() }) { nights, any, clips ->
                val main = SleepWeeks.mainNights(nights, start, zone)
                SleepWeekState(
                    loaded = first != null, hasAny = any, weekStart = start, today = today,
                    canGoForward = SleepWeeks.canGoForward(start, today, day), days = SleepWeeks.days(start),
                    nights = nights.sortedBy { it.endedAt }, main = main, averages = SleepWeeks.averages(main.values),
                    loudest = clips.filter { it.recordedAt in from until to }.maxByOrNull { it.aboveFloorDb }, zone = zone,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepWeekState())

    override fun onCleared() {
        clips.stop()
    }
}

@HiltViewModel
class NightDetailViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repo: SleepRepository,
    player: ClipPlayer,
    settings: SettingsRepository,
) : ViewModel() {
    val clips = ClipActions(context, repo, player) { block -> viewModelScope.launch { runCatching { block() } } }
    private val opened = MutableStateFlow<Long?>(null)
    private val detail = MutableStateFlow<NightDetail?>(null)
    val night: StateFlow<NightDetail?> = detail.asStateFlow()

    val settings: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(sessionId: Long) {
        if (opened.value == sessionId && detail.value != null) return
        opened.value = sessionId
        detail.value = null
        reload()
    }

    private fun reload() {
        val id = opened.value ?: return
        viewModelScope.launch { detail.value = runCatching { repo.night(id) }.getOrNull() }
    }

    fun deleteClip(clip: SnoreClip) = clips.delete(clip) { reload() }

    fun deleteNight(done: () -> Unit) {
        val id = opened.value ?: return
        clips.stop()
        viewModelScope.launch {
            runCatching { repo.deleteNight(id) }
            done()
        }
    }

    override fun onCleared() {
        clips.stop()
    }
}
