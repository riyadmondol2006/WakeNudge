package com.riyadm.wakenudge.health

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.AudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Words are replaced by "<resource id>|<args>" so tests can assert on which message was chosen and with what. */
class FakeHealthResources : HealthResources {
    override fun text(id: Int, vararg args: Any): String = "$id|${args.joinToString(",")}"
    override fun dateTime(millis: Long): String = "T$millis"
    override fun intentFor(kind: HealthFixKind): Intent = Intent("fix:${kind.name}")
}

class HealthEvaluatorTest {

    private val evaluator = HealthEvaluator(FakeHealthResources())

    private fun evaluate(s: HealthSnapshot) = evaluator.evaluate(s)
    private fun List<HealthItem>.item(id: String): HealthItem = single { it.id == id }
    private fun List<HealthItem>.severity(id: String) = item(id).severity
    private fun List<HealthItem>.fixKind(id: String) = item(id).fix?.kind

    private val minute = 60_000L
    private val hour = 60 * minute

    // ---- the happy path -------------------------------------------------------------------------------------------

    @Test fun `a healthy stock phone has no warnings or errors`() {
        val items = evaluate(HealthSnapshot())
        assertTrue(items.toString(), items.none { it.severity >= Severity.WARNING })
        assertEquals(Severity.INFO, HealthReport(items).worstSeverity)
        assertTrue(items.all { it.fix == null })
    }

    @Test fun `item ids are unique and every item has text`() {
        val items = evaluate(
            HealthSnapshot(voiceAlarms = 1, vibrationAlarms = 1, manufacturer = "samsung", headsetNames = listOf("Buds")),
        )
        assertEquals(items.size, items.map { it.id }.toSet().size)
        assertTrue(items.all { it.detail.isNotBlank() && it.title != 0 })
    }

    @Test fun `an empty report is fine`() {
        assertEquals(Severity.OK, HealthReport(emptyList()).worstSeverity)
    }

    @Test fun `worst severity and ordering follow the enum`() {
        val report = HealthReport(evaluate(HealthSnapshot(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE, ringerMode = AudioManager.RINGER_MODE_SILENT)))
        assertEquals(Severity.ERROR, report.worstSeverity)
        assertEquals(Severity.ERROR, report.bySeverity.first().severity)
        assertEquals(1, report.count(Severity.ERROR))
        assertTrue(Severity.OK < Severity.INFO && Severity.INFO < Severity.WARNING && Severity.WARNING < Severity.ERROR)
    }

    // ---- Do Not Disturb ----------------------------------------------------------------------------------------------

    @Test fun `total silence blocks alarms and is an error with a way to the settings`() {
        val items = evaluate(HealthSnapshot(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE, dndAllowsAlarms = false))
        assertEquals(Severity.ERROR, items.severity("dnd"))
        assertEquals(HealthFixKind.DND_SETTINGS, items.fixKind("dnd"))
        assertEquals(R.string.health_fix_dnd, items.item("dnd").fix?.label)
        assertTrue(items.item("dnd").detail.startsWith("${R.string.health_detail_dnd_total_silence}|"))
    }

    @Test fun `priority mode is fine only when it lets alarms through`() {
        val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        assertEquals(Severity.OK, evaluate(HealthSnapshot(interruptionFilter = priority, dndAllowsAlarms = true)).severity("dnd"))
        val blocked = evaluate(HealthSnapshot(interruptionFilter = priority, dndAllowsAlarms = false))
        assertEquals(Severity.ERROR, blocked.severity("dnd"))
        assertTrue(blocked.item("dnd").detail.startsWith("${R.string.health_detail_dnd_priority_blocks}|"))
    }

    @Test fun `alarms only mode lets alarms through`() {
        assertEquals(Severity.OK, evaluate(HealthSnapshot(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS)).severity("dnd"))
        assertNull(evaluate(HealthSnapshot(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS)).fixKind("dnd"))
    }

    // ---- volume and routing ------------------------------------------------------------------------------------------

    @Test fun `a muted alarm stream is flagged with a link to sound settings`() {
        val items = evaluate(HealthSnapshot(alarmVolume = 0))
        assertEquals(Severity.WARNING, items.severity("alarm_volume"))
        assertEquals(HealthFixKind.SOUND, items.fixKind("alarm_volume"))
    }

    @Test fun `a very low alarm stream only matters for alarms that use the system volume`() {
        val low = HealthSnapshot(alarmVolume = 1, alarmVolumeMax = 7)
        assertEquals(Severity.INFO, evaluate(low).severity("alarm_volume"))
        assertEquals(Severity.WARNING, evaluate(low.copy(alarmsUsingSystemVolume = 2)).severity("alarm_volume"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(alarmVolume = 2, alarmVolumeMax = 7)).severity("alarm_volume"))
    }

    @Test fun `devices with fixed volume are not nagged about the slider`() {
        assertEquals(Severity.OK, evaluate(HealthSnapshot(alarmVolume = 0, volumeFixed = true)).severity("alarm_volume"))
    }

    @Test fun `silent ringer mode is informational because alarms ignore it`() {
        assertEquals(Severity.INFO, evaluate(HealthSnapshot(ringerMode = AudioManager.RINGER_MODE_SILENT)).severity("ringer_mode"))
        assertEquals(Severity.INFO, evaluate(HealthSnapshot(ringerMode = AudioManager.RINGER_MODE_VIBRATE)).severity("ringer_mode"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot()).severity("ringer_mode"))
    }

    @Test fun `headphones explain the routing policy with the safety cap`() {
        val speaker = evaluate(HealthSnapshot(headsetNames = listOf("Buds Pro"), audioRoute = AudioRoute.PHONE_SPEAKER)).item("audio_output")
        assertEquals(Severity.INFO, speaker.severity)
        assertTrue(speaker.detail.startsWith("${R.string.health_detail_audio_speaker}|Buds Pro"))

        val capped = evaluate(
            HealthSnapshot(headsetNames = listOf("Buds Pro", "Wired"), audioRoute = AudioRoute.SYSTEM_DEFAULT, hearingSafetyCap = true, headphoneCapPercent = 55),
        ).item("audio_output")
        assertTrue(capped.detail, capped.detail == "${R.string.health_detail_audio_headset_capped}|Buds Pro, Wired,55")

        val uncapped = evaluate(HealthSnapshot(headsetNames = listOf("Buds"), audioRoute = AudioRoute.SYSTEM_DEFAULT, hearingSafetyCap = false)).item("audio_output")
        assertTrue(uncapped.detail.startsWith("${R.string.health_detail_audio_headset_loud}|"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot()).severity("audio_output"))
    }

    // ---- scheduling truth --------------------------------------------------------------------------------------------

    @Test fun `no exact alarm permission links to the right screen for the android version`() {
        val android12 = evaluate(HealthSnapshot(sdkInt = 32, canScheduleExactAlarms = false))
        assertEquals(HealthFixKind.EXACT_ALARMS, android12.fixKind("exact_alarms"))
        val android15 = evaluate(HealthSnapshot(sdkInt = 35, canScheduleExactAlarms = false))
        assertEquals(Severity.WARNING, android15.severity("exact_alarms"))
        assertEquals(HealthFixKind.APP_DETAILS, android15.fixKind("exact_alarms"))
    }

    @Test fun `on android 12 a revoked exact alarm permission is an error, because the alarms themselves ring late`() {
        // There setAlarmClock itself needs the permission; without it alarms are armed inexactly (up to an hour late).
        for (sdk in 31..32) {
            val report = evaluate(HealthSnapshot(sdkInt = sdk, canScheduleExactAlarms = false))
            assertEquals(Severity.ERROR, report.severity("exact_alarms"))
            assertEquals(HealthFixKind.EXACT_ALARMS, report.fixKind("exact_alarms"))
        }
    }

    @Test fun `a do not disturb mode whose own policy mutes alarms is an error even though the default policy allows them`() {
        val mode = evaluate(HealthSnapshot(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, dndAllowsAlarms = true, alarmStreamMuted = true))
        assertEquals(Severity.ERROR, mode.severity("dnd"))
        assertEquals(HealthFixKind.DND_SETTINGS, mode.fixKind("dnd"))
        val fine = evaluate(HealthSnapshot(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY, dndAllowsAlarms = true, alarmStreamMuted = false))
        assertEquals(Severity.OK, fine.severity("dnd"))
    }

    @Test fun `a blocked backup alarm sound channel is reported, because it is the last resort`() {
        val report = evaluate(HealthSnapshot(fallbackChannelImportance = NotificationManager.IMPORTANCE_NONE))
        assertEquals(Severity.WARNING, report.severity("ring_channel"))
        assertEquals(HealthFixKind.APP_NOTIFICATIONS, report.fixKind("ring_channel"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot()).severity("ring_channel"))
    }

    @Test fun `an os that does not know the next alarm is an error`() {
        val now = HealthSnapshot().nowMillis
        val missing = evaluate(HealthSnapshot(appNextAlarmAt = now + hour, systemNextAlarmAt = null))
        assertEquals(Severity.ERROR, missing.severity("system_next_alarm"))
        assertTrue(missing.item("system_next_alarm").detail.startsWith("${R.string.health_detail_system_missing}|"))
    }

    @Test fun `a system alarm later than ours is a mismatch error`() {
        val now = HealthSnapshot().nowMillis
        val items = evaluate(HealthSnapshot(appNextAlarmAt = now + hour, systemNextAlarmAt = now + 3 * hour))
        assertEquals(Severity.ERROR, items.severity("system_next_alarm"))
        assertEquals("${R.string.health_detail_system_mismatch}|T${now + hour},T${now + 3 * hour}", items.item("system_next_alarm").detail)
    }

    @Test fun `a system alarm within a minute of ours matches`() {
        val now = HealthSnapshot().nowMillis
        assertEquals(Severity.OK, evaluate(HealthSnapshot(appNextAlarmAt = now + hour, systemNextAlarmAt = now + hour + 59_000)).severity("system_next_alarm"))
        assertEquals(Severity.ERROR, evaluate(HealthSnapshot(appNextAlarmAt = now + hour, systemNextAlarmAt = now + hour + 61_000)).severity("system_next_alarm"))
    }

    @Test fun `an earlier system alarm from another app is informational`() {
        val now = HealthSnapshot().nowMillis
        assertEquals(Severity.INFO, evaluate(HealthSnapshot(appNextAlarmAt = now + 5 * hour, systemNextAlarmAt = now + hour)).severity("system_next_alarm"))
    }

    @Test fun `with nothing due the system alarm is not our concern`() {
        val now = HealthSnapshot().nowMillis
        assertEquals(Severity.OK, evaluate(HealthSnapshot(appNextAlarmAt = null, systemNextAlarmAt = null)).severity("system_next_alarm"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(appNextAlarmAt = null, systemNextAlarmAt = now + hour)).severity("system_next_alarm"))
    }

    @Test fun `alarms missing from the os are an error and bookkeeping drift a warning`() {
        val missing = evaluate(HealthSnapshot(enabledAlarms = 3, expectedArmed = 3, armedWithOs = 1, recordedTriggers = 3))
        assertEquals(Severity.ERROR, missing.severity("armed_alarms"))
        assertEquals("${R.string.health_detail_armed_missing}|1,3", missing.item("armed_alarms").detail)

        val drift = evaluate(HealthSnapshot(armedWithOs = 3, recordedTriggers = 2))
        assertEquals(Severity.WARNING, drift.severity("armed_alarms"))
    }

    @Test fun `paused alarms are explained rather than flagged`() {
        val items = evaluate(HealthSnapshot(enabledAlarms = 4, expectedArmed = 1, armedWithOs = 1, recordedTriggers = 1))
        assertEquals(Severity.OK, items.severity("armed_alarms"))
        assertEquals("${R.string.health_detail_armed_ok_paused}|1,3", items.item("armed_alarms").detail)
        assertEquals(Severity.OK, evaluate(HealthSnapshot(enabledAlarms = 0, expectedArmed = 0, armedWithOs = 0, recordedTriggers = 0)).severity("armed_alarms"))
    }

    // ---- permissions -------------------------------------------------------------------------------------------------

    @Test fun `denied notification permission is an error on android 13 and later only`() {
        val denied = evaluate(HealthSnapshot(sdkInt = 34, postNotificationsGranted = false))
        assertEquals(Severity.ERROR, denied.severity("notifications"))
        assertEquals(HealthFixKind.APP_NOTIFICATIONS, denied.fixKind("notifications"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(sdkInt = 31, postNotificationsGranted = false)).severity("notifications"))
    }

    @Test fun `notifications switched off in settings are an error`() {
        val items = evaluate(HealthSnapshot(notificationsEnabled = false))
        assertEquals(Severity.ERROR, items.severity("notifications"))
        assertTrue(items.item("notifications").detail.startsWith("${R.string.health_detail_notif_disabled}|"))
    }

    @Test fun `the ring channel is judged by its importance`() {
        assertEquals(Severity.OK, evaluate(HealthSnapshot()).severity("ring_channel"))
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(ringChannelImportance = null)).severity("ring_channel"))
        val blocked = evaluate(HealthSnapshot(ringChannelImportance = NotificationManager.IMPORTANCE_NONE))
        assertEquals(Severity.ERROR, blocked.severity("ring_channel"))
        assertEquals(HealthFixKind.RING_CHANNEL, blocked.fixKind("ring_channel"))
        val demoted = evaluate(HealthSnapshot(ringChannelImportance = NotificationManager.IMPORTANCE_LOW))
        assertEquals(Severity.WARNING, demoted.severity("ring_channel"))
        assertNotNull(demoted.item("ring_channel").fix)
    }

    @Test fun `full screen alerts only need checking from android 14`() {
        val revoked = evaluate(HealthSnapshot(sdkInt = 34, fullScreenIntentAllowed = false))
        assertEquals(Severity.WARNING, revoked.severity("full_screen"))
        assertEquals(HealthFixKind.FULL_SCREEN_INTENT, revoked.fixKind("full_screen"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(sdkInt = 33, fullScreenIntentAllowed = false)).severity("full_screen"))
    }

    // ---- power management --------------------------------------------------------------------------------------------

    @Test fun `battery optimization is informational on stock android and a warning on aggressive vendors`() {
        val stock = evaluate(HealthSnapshot(ignoringBatteryOptimizations = false))
        assertEquals(Severity.INFO, stock.severity("battery_optimization"))
        assertEquals(HealthFixKind.BATTERY_OPTIMIZATION, stock.fixKind("battery_optimization"))
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(ignoringBatteryOptimizations = false, manufacturer = "Xiaomi")).severity("battery_optimization"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(ignoringBatteryOptimizations = true, manufacturer = "Xiaomi")).severity("battery_optimization"))
    }

    @Test fun `standby buckets rare and below warn and active ones do not`() {
        assertEquals(Severity.OK, evaluate(HealthSnapshot(standbyBucket = HealthSnapshot.STANDBY_ACTIVE)).severity("standby_bucket"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(standbyBucket = HealthSnapshot.STANDBY_FREQUENT)).severity("standby_bucket"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(standbyBucket = HealthSnapshot.STANDBY_EXEMPTED)).severity("standby_bucket"))
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(standbyBucket = HealthSnapshot.STANDBY_RARE)).severity("standby_bucket"))
        val restricted = evaluate(HealthSnapshot(standbyBucket = HealthSnapshot.STANDBY_RESTRICTED))
        assertEquals(Severity.WARNING, restricted.severity("standby_bucket"))
        assertTrue(restricted.item("standby_bucket").detail.startsWith("${R.string.health_detail_standby_restricted}|"))
        assertTrue(evaluate(HealthSnapshot(standbyBucket = null)).none { it.id == "standby_bucket" })
    }

    @Test fun `background restriction is an error and is skipped before android 9`() {
        val restricted = evaluate(HealthSnapshot(backgroundRestricted = true))
        assertEquals(Severity.ERROR, restricted.severity("background_restricted"))
        assertEquals(HealthFixKind.APP_DETAILS, restricted.fixKind("background_restricted"))
        assertTrue(evaluate(HealthSnapshot(backgroundRestricted = null)).none { it.id == "background_restricted" })
    }

    @Test fun `known aggressive vendors get specific advice and others do not`() {
        val xiaomi = evaluate(HealthSnapshot(manufacturer = "Xiaomi")).item("oem_hint")
        assertEquals(Severity.INFO, xiaomi.severity)
        assertEquals("${Oem.XIAOMI.advice}|", xiaomi.detail)
        assertEquals(HealthFixKind.APP_DETAILS, xiaomi.fix?.kind)
        assertTrue(evaluate(HealthSnapshot(manufacturer = "Google")).none { it.id == "oem_hint" })
    }

    @Test fun `vendor names map to their advice`() {
        val expected = mapOf(
            "Xiaomi" to Oem.XIAOMI, "Redmi" to Oem.XIAOMI, "POCO" to Oem.XIAOMI,
            "HUAWEI" to Oem.HUAWEI, "HONOR" to Oem.HUAWEI,
            "OnePlus" to Oem.ONEPLUS,
            "OPPO" to Oem.OPPO, "realme" to Oem.OPPO,
            "vivo" to Oem.VIVO, "iQOO" to Oem.VIVO,
            "samsung" to Oem.SAMSUNG, "asus" to Oem.ASUS, "MEIZU" to Oem.MEIZU,
            "HMD Global" to Oem.HMD, "Nokia" to Oem.HMD,
        )
        expected.forEach { (name, oem) -> assertEquals(name, oem, Oem.of(name)) }
        assertNull(Oem.of("Google"))
        assertNull(Oem.of("Motorola"))
        assertNull(Oem.of(""))
    }

    // ---- clock, storage, history --------------------------------------------------------------------------------------

    @Test fun `manual time or time zone is a warning with a link to date settings`() {
        for (snapshot in listOf(HealthSnapshot(autoTime = false), HealthSnapshot(autoTimeZone = false))) {
            val items = evaluate(snapshot)
            assertEquals(Severity.WARNING, items.severity("date_time"))
            assertEquals(HealthFixKind.DATE_TIME, items.fixKind("date_time"))
        }
        assertEquals(Severity.OK, evaluate(HealthSnapshot(autoTime = null, autoTimeZone = null)).severity("date_time"))
    }

    @Test fun `direct boot readiness is informational unless storage is not protected`() {
        assertEquals(Severity.INFO, evaluate(HealthSnapshot()).severity("direct_boot"))
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(alarmStorageDeviceProtected = false)).severity("direct_boot"))
    }

    @Test fun `a scheduler that never ran with alarms on is a warning`() {
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(lastRescheduleAt = 0, enabledAlarms = 2)).severity("scheduler"))
        assertEquals(Severity.INFO, evaluate(HealthSnapshot(lastRescheduleAt = 0, enabledAlarms = 0)).severity("scheduler"))
        val ran = evaluate(HealthSnapshot(lastRescheduleReason = "system:BOOT"))
        assertTrue(ran.item("scheduler").detail.endsWith("system:BOOT"))
    }

    @Test fun `a watchdog silent for a day is a warning`() {
        val now = HealthSnapshot().nowMillis
        assertEquals(Severity.OK, evaluate(HealthSnapshot(lastWatchdogAt = now - 5 * hour)).severity("watchdog"))
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(lastWatchdogAt = now - 30 * hour)).severity("watchdog"))
        assertEquals(Severity.INFO, evaluate(HealthSnapshot(lastWatchdogAt = 0, lastRescheduleAt = now - hour)).severity("watchdog"))
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(lastWatchdogAt = 0, lastRescheduleAt = now - 20 * hour)).severity("watchdog"))
        val idle = HealthSnapshot(lastWatchdogAt = now - 30 * hour, enabledAlarms = 0, expectedArmed = 0, armedWithOs = 0, recordedTriggers = 0)
        assertEquals("nothing to protect, nothing to worry about", Severity.OK, evaluate(idle).severity("watchdog"))
    }

    @Test fun `a foreground service start failure is surfaced with its message`() {
        val items = evaluate(HealthSnapshot(lastServiceStartFailure = "ForegroundServiceStartNotAllowedException"))
        assertEquals(Severity.WARNING, items.severity("service_start"))
        assertTrue(items.item("service_start").detail.contains("ForegroundServiceStartNotAllowedException"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot()).severity("service_start"))
    }

    @Test fun `the last ring is reported either way`() {
        assertTrue(evaluate(HealthSnapshot(lastRingAt = 0)).item("last_ring").detail.startsWith("${R.string.health_detail_ring_never}|"))
        assertTrue(evaluate(HealthSnapshot()).item("last_ring").detail.startsWith("${R.string.health_detail_ring_last}|"))
    }

    // ---- features that are only checked when used --------------------------------------------------------------------

    @Test fun `text to speech is only checked when an alarm announces`() {
        assertTrue(evaluate(HealthSnapshot(voiceAlarms = 0, ttsAvailable = false)).none { it.id == "tts" })
        assertEquals(Severity.OK, evaluate(HealthSnapshot(voiceAlarms = 1, ttsAvailable = true)).severity("tts"))
        val missing = evaluate(HealthSnapshot(voiceAlarms = 1, ttsAvailable = false))
        assertEquals(Severity.WARNING, missing.severity("tts"))
        assertEquals(HealthFixKind.TTS_SETTINGS, missing.fixKind("tts"))
    }

    @Test fun `the vibrator is only checked when an alarm vibrates`() {
        assertTrue(evaluate(HealthSnapshot(vibrationAlarms = 0, hasVibrator = false)).none { it.id == "vibrator" })
        assertEquals(Severity.INFO, evaluate(HealthSnapshot(vibrationAlarms = 2, hasVibrator = false)).severity("vibrator"))
        assertEquals(Severity.OK, evaluate(HealthSnapshot(vibrationAlarms = 2, hasVibrator = true)).severity("vibrator"))
    }

    // ---- fixes --------------------------------------------------------------------------------------------------------

    @Test fun `every fix carries the intent the resources built for its kind`() {
        val broken = HealthSnapshot(
            sdkInt = 34, postNotificationsGranted = false, ringChannelImportance = NotificationManager.IMPORTANCE_NONE,
            fullScreenIntentAllowed = false, canScheduleExactAlarms = false, ignoringBatteryOptimizations = false,
            interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE, alarmVolume = 0, autoTime = false,
            voiceAlarms = 1, ttsAvailable = false, backgroundRestricted = true, standbyBucket = HealthSnapshot.STANDBY_RARE,
        )
        val fixes = evaluate(broken).mapNotNull { it.fix }
        assertTrue(fixes.size >= 9)
        fixes.forEach { assertNotNull(it.intent) }
        assertEquals(
            setOf(
                HealthFixKind.APP_NOTIFICATIONS, HealthFixKind.RING_CHANNEL, HealthFixKind.FULL_SCREEN_INTENT, HealthFixKind.APP_DETAILS,
                HealthFixKind.BATTERY_OPTIMIZATION, HealthFixKind.DND_SETTINGS, HealthFixKind.SOUND, HealthFixKind.DATE_TIME, HealthFixKind.TTS_SETTINGS,
            ),
            fixes.map { it.kind }.toSet(),
        )
    }

    // ---- what the engine noticed while running ----------------------------------------------------------------------

    @Test fun `recent engine problems are reported and stale ones are forgotten`() {
        val now = HealthSnapshot().nowMillis
        val recent = evaluate(HealthSnapshot(schedulingProblem = "${now - hour}|1 alarm(s) could not be armed: boom", audioProblem = "${now - 2 * hour}|emergency beeper in use"))
        assertEquals(Severity.WARNING, recent.severity("scheduling_problem"))
        assertEquals(Severity.WARNING, recent.severity("audio_problem"))
        assertTrue(recent.item("audio_problem").detail.contains("emergency beeper in use"))

        val old = evaluate(HealthSnapshot(schedulingProblem = "${now - 10 * 24 * hour}|old", audioProblem = "${now - 10 * 24 * hour}|old"))
        assertTrue(old.none { it.id == "scheduling_problem" || it.id == "audio_problem" })
    }

    @Test fun `a malformed problem record is ignored instead of crashing the screen`() {
        val items = evaluate(HealthSnapshot(schedulingProblem = "no timestamp here", audioProblem = "|"))
        assertTrue(items.none { it.id == "scheduling_problem" || it.id == "audio_problem" })
    }

    @Test fun `a ring screen crash is shown for a few days`() {
        val now = HealthSnapshot().nowMillis
        assertEquals(Severity.WARNING, evaluate(HealthSnapshot(lastRingCrashAt = now - hour)).severity("ring_crash"))
        assertTrue(evaluate(HealthSnapshot(lastRingCrashAt = now - 5 * 24 * hour)).none { it.id == "ring_crash" })
        assertTrue(evaluate(HealthSnapshot()).none { it.id == "ring_crash" })
    }

    @Test fun `battery saver is mentioned but never alarming because alarm clocks are exempt`() {
        assertEquals(Severity.INFO, evaluate(HealthSnapshot(batterySaver = true)).severity("battery_saver"))
        assertTrue(evaluate(HealthSnapshot()).none { it.id == "battery_saver" })
    }
}
