package com.riyadm.wakenudge.health

import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.repository.DiagnosticRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportFormatterTest {

    private val formatter = DiagnosticsReportFormatter { id, args -> "$id|${args.joinToString(",")}" }

    private val facts = ReportFacts(
        appVersionName = "1.0.0", appVersionCode = 7, buildType = "release", packageName = "com.riyadm.wakenudge",
        androidRelease = "15", sdkInt = 35, manufacturer = "Google", model = "Pixel",
        totalAlarms = 5, enabledAlarms = 3, groups = 1, missionAlarms = 2, editLockAlarms = 1,
        timeFormat = "H24", vacationOn = false,
    )

    private fun item(id: String, severity: Severity, detail: String) = HealthItem(id, severity, R.string.health_title_dnd, detail, null)

    private fun report(
        items: List<HealthItem> = emptyList(),
        log: List<DiagnosticRecord> = emptyList(),
        snapshot: HealthSnapshot = HealthSnapshot(),
    ) = formatter.format(1_800_000_000_000L, facts, snapshot, items, log) { "title$it" }

    @Test fun `the report states build, device and alarm counts but has room for no labels`() {
        val text = report()
        assertTrue(text.contains("${R.string.health_report_app_version}|1.0.0,7,release"))
        assertTrue(text.contains("${R.string.health_report_device_android}|15,35"))
        assertTrue(text.contains("${R.string.health_report_device_model}|Google,Pixel"))
        assertTrue(text.contains("${R.string.health_report_alarm_counts}|5,3,1"))
        assertTrue(text.contains("${R.string.health_report_generated}|2027-01-15T08:00:00Z"))
    }

    @Test fun `findings are listed worst first`() {
        val text = report(
            items = listOf(
                item("a", Severity.OK, "fine"),
                item("b", Severity.ERROR, "broken"),
                item("c", Severity.WARNING, "iffy"),
            ),
        )
        val order = listOf("broken", "iffy", "fine").map { text.indexOf(it) }
        assertTrue(order.toString(), order.all { it > 0 } && order == order.sorted())
        assertTrue(text.contains("${R.string.health_report_item}|ERROR,title${R.string.health_title_dnd},broken"))
    }

    @Test fun `the log is written newest first as given with timestamps and file shapes scrubbed`() {
        val log = listOf(
            DiagnosticRecord(1_800_000_002_000, "W", "Audio", "Could not prepare content://media/external/audio/media/41: boom"),
            DiagnosticRecord(1_800_000_001_000, "I", "Scheduler", "rescheduleAll(ui): 3 alarms, 3 armed"),
            DiagnosticRecord(1_800_000_000_000, "W", "Audio", "Failed /storage/emulated/0/Music/SongTitle.mp3 and file:///sdcard/x.mp3"),
        )
        val text = report(log = log)
        assertFalse(text.contains("media/external"))
        assertFalse(text.contains("SongTitle"))
        assertFalse(text.contains("sdcard/x"))
        assertTrue(text.contains("2027-01-15T08:00:02Z W Audio: Could not prepare content://…: boom"))
        assertTrue(text.contains("I Scheduler: rescheduleAll(ui): 3 alarms, 3 armed"))
        assertTrue(text.indexOf("08:00:02Z") < text.indexOf("08:00:01Z"))
        assertTrue(text.contains("${R.string.health_report_section_log}|3".uppercase()))
    }

    @Test fun `an empty log says so`() {
        assertTrue(report().contains("${R.string.health_report_log_empty}|"))
    }

    @Test fun `scrub shortens and flattens messages`() {
        assertEquals("a | b", DiagnosticsReportFormatter.scrub("a\n  b"))
        assertEquals(300, DiagnosticsReportFormatter.scrub("x".repeat(1_000)).length)
        assertEquals("content://…", DiagnosticsReportFormatter.scrub("content://com.android.providers.media.documents/document/audio%3A9"))
        assertEquals("/data/…", DiagnosticsReportFormatter.scrub("/data/user/0/com.riyadm.wakenudge/files/x"))
    }

    @Test fun `scheduler facts come from the snapshot`() {
        val text = report(snapshot = HealthSnapshot(recordedTriggers = 2, armedWithOs = 3, expectedArmed = 4, lastServiceStartFailure = "boom content://x/y"))
        assertTrue(text.contains("${R.string.health_report_scheduler_counts}|2,3,4"))
        assertTrue(text.contains("${R.string.health_report_scheduler_service}|boom content://…"))
    }
}
