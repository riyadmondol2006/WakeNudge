package com.riyadm.wakenudge.health

import android.content.Context
import android.os.Build
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.repository.AlarmRepository
import com.riyadm.wakenudge.domain.repository.DiagnosticRecord
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.GroupRepository
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Counts and settings that influence reliability, gathered without touching any alarm label. */
data class ReportFacts(
    val appVersionName: String,
    val appVersionCode: Int,
    val buildType: String,
    val packageName: String,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val totalAlarms: Int,
    val enabledAlarms: Int,
    val groups: Int,
    val missionAlarms: Int,
    val editLockAlarms: Int,
    val timeFormat: String,
    val vacationOn: Boolean,
)

/**
 * Lays the support report out as plain text. Pure: it receives already-worded strings through [text] and never sees
 * an alarm label, and it scrubs file and content URIs out of the free-text engine log.
 */
class DiagnosticsReportFormatter(private val text: (id: Int, args: Array<out Any>) -> String) {

    fun format(
        generatedAt: Long,
        facts: ReportFacts,
        snapshot: HealthSnapshot,
        items: List<HealthItem>,
        log: List<DiagnosticRecord>,
        itemTitle: (Int) -> String,
    ): String = buildString {
        fun line(id: Int, vararg args: Any) = appendLine(text(id, args))
        fun gap() = appendLine()
        fun section(id: Int, vararg args: Any) {
            gap()
            appendLine(text(id, args).uppercase())
        }
        fun stamp(millis: Long): String = if (millis > 0) Instant.ofEpochMilli(millis).toString() else text(R.string.health_report_never, emptyArray())

        line(R.string.health_report_title)
        line(R.string.health_report_privacy)
        line(R.string.health_report_generated, stamp(generatedAt))

        section(R.string.health_report_section_app)
        line(R.string.health_report_app_version, facts.appVersionName, facts.appVersionCode, facts.buildType)
        line(R.string.health_report_app_package, facts.packageName)

        section(R.string.health_report_section_device)
        line(R.string.health_report_device_android, facts.androidRelease, facts.sdkInt)
        line(R.string.health_report_device_model, facts.manufacturer, facts.model)

        section(R.string.health_report_section_alarms)
        line(R.string.health_report_alarm_counts, facts.totalAlarms, facts.enabledAlarms, facts.groups)
        line(R.string.health_report_alarm_features, snapshot.voiceAlarms, snapshot.vibrationAlarms, facts.missionAlarms, facts.editLockAlarms)

        section(R.string.health_report_section_settings)
        val on = text(R.string.health_report_on, emptyArray())
        val off = text(R.string.health_report_off, emptyArray())
        line(
            R.string.health_report_settings_line,
            facts.timeFormat, snapshot.audioRoute.name, if (snapshot.hearingSafetyCap) on else off,
            snapshot.headphoneCapPercent, if (facts.vacationOn) on else off,
        )

        section(R.string.health_report_section_health)
        items.sortedByDescending { it.severity }.forEach { item ->
            line(R.string.health_report_item, item.severity.name, itemTitle(item.title), item.detail)
        }

        section(R.string.health_report_section_scheduler)
        line(R.string.health_report_scheduler_counts, snapshot.recordedTriggers, snapshot.armedWithOs, snapshot.expectedArmed)
        line(R.string.health_report_scheduler_reschedule, stamp(snapshot.lastRescheduleAt), snapshot.lastRescheduleReason.ifBlank { "-" })
        line(R.string.health_report_scheduler_ring, stamp(snapshot.lastRingAt))
        line(R.string.health_report_scheduler_watchdog, stamp(snapshot.lastWatchdogAt))
        line(
            R.string.health_report_scheduler_service,
            snapshot.lastServiceStartFailure.ifBlank { text(R.string.health_report_none, emptyArray()) }.let(::scrub),
        )

        section(R.string.health_report_section_log, log.size)
        if (log.isEmpty()) {
            line(R.string.health_report_log_empty)
        } else {
            log.forEach { appendLine("${Instant.ofEpochMilli(it.timestamp)} ${it.level} ${it.tag}: ${scrub(it.message)}") }
        }
    }

    companion object {
        // Greedy up to whitespace, but the match may not end in punctuation that merely follows the address.
        private val URI = Regex("""(content|file)://\S*[^\s.,;:)\]'"]""")
        private val PATH = Regex("""(/storage|/sdcard|/data|/mnt)/\S*[^\s.,;:)\]'"]""")
        private const val MAX_MESSAGE = 300

        /** Free text can carry file names or content URIs (which can name music files); keep only their shape. */
        fun scrub(message: String): String = message
            .replace(URI) { "${it.groupValues[1]}://…" }
            .replace(PATH) { "${it.groupValues[1]}/…" }
            .replace(Regex("""\s*\R\s*"""), " | ")
            .take(MAX_MESSAGE)
    }
}

/**
 * A plain-text support report the user can copy or share by hand: app and device build, the health findings, the
 * scheduler's state and the last 200 engine events. Nothing is sent anywhere, and it holds no alarm labels.
 */
@Singleton
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: HealthChecker,
    private val log: DiagnosticsLog,
    private val alarms: AlarmRepository,
    private val groups: GroupRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    private val resources = AndroidHealthResources(context)
    private val formatter = DiagnosticsReportFormatter { id, args -> context.getString(id, *args) }

    suspend fun buildReport(): String {
        // Bluetooth device names usually contain the owner's name; the report says only that something is connected.
        val genericDevice = context.getString(R.string.health_report_headset)
        val snapshot = checker.snapshot().let { it.copy(headsetNames = it.headsetNames.map { genericDevice }) }
        val items = HealthEvaluator(resources).evaluate(snapshot)

        val all = alarms.getAlarms()
        val current = settings.current()
        val facts = ReportFacts(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            packageName = context.packageName,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            totalAlarms = all.size,
            enabledAlarms = all.count { it.enabled },
            groups = groups.getGroups().size,
            missionAlarms = all.count { it.enabled && it.missions.isNotEmpty() },
            editLockAlarms = all.count { it.enabled && it.antiOversleep.editLockMinutes > 0 },
            timeFormat = current.timeFormat.name,
            vacationOn = current.vacation.enabled,
        )
        return formatter.format(
            generatedAt = time.nowMillis(),
            facts = facts,
            snapshot = snapshot,
            items = items,
            log = log.recent(LOG_ENTRIES),
            itemTitle = context::getString,
        )
    }

    private companion object {
        const val LOG_ENTRIES = 200
    }
}
