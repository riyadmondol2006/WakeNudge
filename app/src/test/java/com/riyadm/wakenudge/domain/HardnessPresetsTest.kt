package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.Hardness
import com.riyadm.wakenudge.domain.model.HardnessPresets
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardnessPresetsTest {

    /**
     * The level table, typed in a second time by hand from the plan, field by field, so a typo in [HardnessPresets] fails
     * here instead of shipping. Columns: Easy, Normal, Hard, No mercy.
     */
    private val table: Map<String, List<Any>> = linkedMapOf(
        "snooze.enabled" to listOf(true, true, false, false),
        "ao.ringTimeoutMinutes" to listOf(10, 0, 0, 0),
        "ao.backupEnabled" to listOf(true, true, true, true),
        "ao.backupCount" to listOf(1, 1, 3, 3),
        "ao.backupDelayMinutes" to listOf(10, 10, 10, 10),
        "ao.holdToDismissSeconds" to listOf(0, 0, 0, 0),
        "ao.emergencyStopEnabled" to listOf(true, true, false, false),
        "ao.wayOutDelayMinutes" to listOf(0, 0, 3, 10),
        "ao.wayOutCount" to listOf(10, 10, 10, 20),
        "ao.fallbackCount" to listOf(3, 3, 5, 8),
        "ao.editLockMinutes" to listOf(0, 15, 480, 720),
        "ao.lockOverrideMission" to listOf(false, false, true, true),
        "ao.volumeFloorPercent" to listOf(0, 50, 100, 100),
        "ao.volumeKeyAction" to listOf(VolumeKeyAction.SNOOZE, VolumeKeyAction.NOTHING, VolumeKeyAction.NOTHING, VolumeKeyAction.NOTHING),
        "ao.returnToAlarm" to listOf(true, true, true, true),
        "ao.forceReturn" to listOf(false, true, true, true),
        "ao.muteBudget" to listOf(3, 3, 2, 1),
        "ao.muteSeconds" to listOf(30, 30, 20, 15),
        "ao.calmOnlyOnProgress" to listOf(false, true, true, true),
        "ao.resumeWindowMinutes" to listOf(10, 120, 720, 720),
        "ao.maxResumes" to listOf(3, 3, 5, 10),
        "ao.fullBrightness" to listOf(false, true, true, true),
        "ao.forceSpeaker" to listOf(false, false, true, true),
        "ao.wayOutRemoved" to listOf(false, false, false, false),
        "volume.level" to listOf(80, 80, 100, 100),
        "volume.rampSeconds" to listOf(30, 30, 10, 5),
        "volume.useMaxSystemVolume" to listOf(false, false, true, true),
        "volume.extraLoud" to listOf(false, false, false, true),
        "volume.escalateWhenIgnored" to listOf(true, true, true, true),
        "wakeCheck.enabled" to listOf(false, false, true, true),
    )

    /** Rows that only apply where the level has the thing switched on. */
    private val snoozeDetails = mapOf(Hardness.EASY to (10 to 3), Hardness.NORMAL to (5 to 3))
    private val vibrationOn = setOf(Hardness.NORMAL, Hardness.HARD, Hardness.NO_MERCY)
    private val checks = mapOf(Hardness.HARD to 1, Hardness.NO_MERCY to 2)

    private fun read(a: Alarm, key: String): Any {
        val (group, field) = key.split('.')
        val owner: Any = when (group) {
            "ao" -> a.antiOversleep
            "volume" -> a.volume
            "snooze" -> a.snooze
            "wakeCheck" -> a.wakeCheck
            else -> error(group)
        }
        return owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner)!!
    }

    /** Alarms that look nothing like any level, so every field [HardnessPresets.apply] leaves alone shows up. */
    private val starts: List<Alarm> = listOf(
        Alarm(),
        Alarm(
            label = "odd", hour = 5, minute = 12, repeatDaysMask = Alarm.WEEKDAYS_MASK,
            sound = SoundSettings(mode = SoundMode.RANDOM_BUNDLED),
            missions = listOf(MissionConfig(MissionType.QR_BARCODE, params = mapOf(MissionConfig.PARAM_CODE to "x"))),
            snoozeMissions = listOf(MissionConfig(MissionType.MATH)),
            snooze = SnoozeSettings(enabled = false, minutes = 22, maxCount = 0, decreasing = true),
            volume = VolumeSettings(level = 0, rampSeconds = 0, rampStartPercent = 40, extraLoud = true, extraLoudPercent = 30, useMaxSystemVolume = true, escalateWhenIgnored = false),
            vibration = VibrationSettings(enabled = false),
            wakeCheck = WakeCheckSettings(enabled = true, delayMinutes = 9, responseSeconds = 200, maxChecks = 4, maxReRings = 1),
            antiOversleep = AntiOversleepSettings(
                holdToDismissSeconds = 7, backupEnabled = false, backupCount = 2, backupDelayMinutes = 3, editLockMinutes = 60,
                ringTimeoutMinutes = 25, emergencyStopEnabled = false, volumeFloorPercent = 33, volumeKeyAction = VolumeKeyAction.DISMISS,
                returnToAlarm = false, forceReturn = true, muteBudget = 0, muteSeconds = 60, wayOutDelayMinutes = 30, wayOutCount = 30,
                fallbackCount = 20, calmOnlyOnProgress = true, resumeWindowMinutes = 0, lockOverrideMission = true, fullBrightness = true,
                forceSpeaker = true, torchWhenIgnored = true, maxResumes = 20, rotateMissions = true, wayOutRemoved = true,
            ),
        ),
    )

    @Test fun `every level sets exactly the table, typed in a second time`() {
        HardnessPresets.levels.forEachIndexed { column, level ->
            for (start in starts) {
                val a = HardnessPresets.apply(level, start)
                for ((key, values) in table) assertEquals("$level $key", values[column], read(a, key))
                snoozeDetails[level]?.let { (minutes, max) ->
                    assertEquals("$level snooze minutes", minutes, a.snooze.minutes)
                    assertEquals("$level snooze limit", max, a.snooze.maxCount)
                    assertTrue("$level asks no mission to snooze", a.snoozeMissions.isEmpty())
                }
                if (level in vibrationOn) assertTrue("$level vibrates", a.vibration.enabled) else assertEquals(start.vibration, a.vibration)
                checks[level]?.let { n ->
                    assertEquals(WakeCheckSettings(enabled = true, delayMinutes = 5, responseSeconds = 60, maxChecks = n, maxReRings = 10, requireProof = true), a.wakeCheck)
                }
                if (level == Hardness.NO_MERCY) assertEquals(100, a.volume.extraLoudPercent)
                if (level == Hardness.EASY) assertTrue(a.missions.isEmpty()) else assertTrue("$level has a mission", a.missions.isNotEmpty())
            }
        }
    }

    @Test fun `a level that needs a mission keeps the alarm's own, or gets the Mixed rotation`() {
        val qr = starts[1]
        for (level in listOf(Hardness.NORMAL, Hardness.HARD, Hardness.NO_MERCY)) {
            assertEquals(qr.missions, HardnessPresets.apply(level, qr).missions)
            val empty = HardnessPresets.apply(level, Alarm())
            val math = if (level == Hardness.NORMAL) Difficulty.MEDIUM else Difficulty.HARD
            assertEquals(
                listOf(MissionConfig(MissionType.FOCUS_TAP), MissionConfig(MissionType.MEMORY), MissionConfig(MissionType.MATH, math, count = 3)),
                empty.missions,
            )
            assertTrue("the Mixed rotation changes each morning", empty.antiOversleep.rotateMissions)
            assertFalse("rotation is the user's choice otherwise", HardnessPresets.apply(level, qr.copy(antiOversleep = qr.antiOversleep.copy(rotateMissions = false))).antiOversleep.rotateMissions)
        }
    }

    @Test fun `fields no level owns are kept`() {
        val start = starts[1]
        for (level in HardnessPresets.levels) {
            val a = HardnessPresets.apply(level, start)
            assertEquals(start.label, a.label)
            assertEquals(start.hour to start.minute, a.hour to a.minute)
            assertEquals(start.repeatDaysMask, a.repeatDaysMask)
            assertEquals(start.sound, a.sound)
            assertEquals(start.volume.rampStartPercent, a.volume.rampStartPercent)
            assertEquals(start.snooze.decreasing, a.snooze.decreasing)
            assertEquals(start.antiOversleep.torchWhenIgnored, a.antiOversleep.torchWhenIgnored)
            assertEquals(start.antiOversleep.powerGuard, a.antiOversleep.powerGuard)
            assertEquals(start.vibration.pattern, a.vibration.pattern)
        }
    }

    @Test fun `round trip for each level`() {
        for (level in HardnessPresets.levels) for (start in starts) {
            val a = HardnessPresets.apply(level, start)
            assertEquals("$level from ${start.label}", level, HardnessPresets.levelOf(a))
            assertEquals("applying twice changes nothing", a, HardnessPresets.apply(level, a))
        }
    }

    @Test fun `today's default alarm is Easy`() {
        assertEquals(Hardness.EASY, HardnessPresets.levelOf(Alarm()))
    }

    @Test fun `the odd alarm is Custom, and Custom changes nothing`() {
        assertEquals(Hardness.CUSTOM, HardnessPresets.levelOf(starts[1]))
        assertEquals(starts[1], HardnessPresets.apply(Hardness.CUSTOM, starts[1]))
    }

    /** One changed value for every owned field: each makes the level Custom, and putting it back names the level again. */
    /** One changed value for every owned field. Each is its own inverse (`xor 1`, `!`), so applying it twice restores it. */
    private val flips: Map<String, (Alarm) -> Alarm> = mapOf(
        "holdToDismissSeconds" to { a -> a.ao { copy(holdToDismissSeconds = holdToDismissSeconds xor 1) } },
        "backupEnabled" to { a -> a.ao { copy(backupEnabled = !backupEnabled) } },
        "backupDelayMinutes" to { a -> a.ao { copy(backupDelayMinutes = backupDelayMinutes xor 1) } },
        "backupCount" to { a -> a.ao { copy(backupCount = backupCount xor 1) } },
        "editLockMinutes" to { a -> a.ao { copy(editLockMinutes = editLockMinutes xor 1) } },
        "ringTimeoutMinutes" to { a -> a.ao { copy(ringTimeoutMinutes = ringTimeoutMinutes xor 1) } },
        "emergencyStopEnabled" to { a -> a.ao { copy(emergencyStopEnabled = !emergencyStopEnabled) } },
        "volumeFloorPercent" to { a -> a.ao { copy(volumeFloorPercent = volumeFloorPercent xor 1) } },
        "volumeKeyAction" to { a -> a.ao { copy(volumeKeyAction = if (volumeKeyAction == VolumeKeyAction.SNOOZE) VolumeKeyAction.NOTHING else VolumeKeyAction.SNOOZE) } },
        "returnToAlarm" to { a -> a.ao { copy(returnToAlarm = !returnToAlarm) } },
        "forceReturn" to { a -> a.ao { copy(forceReturn = !forceReturn) } },
        "muteBudget" to { a -> a.ao { copy(muteBudget = muteBudget xor 1) } },
        "muteSeconds" to { a -> a.ao { copy(muteSeconds = muteSeconds xor 1) } },
        "wayOutDelayMinutes" to { a -> a.ao { copy(wayOutDelayMinutes = wayOutDelayMinutes xor 1) } },
        "wayOutCount" to { a -> a.ao { copy(wayOutCount = wayOutCount xor 1) } },
        "fallbackCount" to { a -> a.ao { copy(fallbackCount = fallbackCount xor 1) } },
        "calmOnlyOnProgress" to { a -> a.ao { copy(calmOnlyOnProgress = !calmOnlyOnProgress) } },
        "resumeWindowMinutes" to { a -> a.ao { copy(resumeWindowMinutes = resumeWindowMinutes xor 1) } },
        "lockOverrideMission" to { a -> a.ao { copy(lockOverrideMission = !lockOverrideMission) } },
        "fullBrightness" to { a -> a.ao { copy(fullBrightness = !fullBrightness) } },
        "forceSpeaker" to { a -> a.ao { copy(forceSpeaker = !forceSpeaker) } },
        "maxResumes" to { a -> a.ao { copy(maxResumes = maxResumes xor 1) } },
        "wayOutRemoved" to { a -> a.ao { copy(wayOutRemoved = !wayOutRemoved) } },
        "level" to { a -> a.copy(volume = a.volume.copy(level = a.volume.level xor 1)) },
        "rampSeconds" to { a -> a.copy(volume = a.volume.copy(rampSeconds = a.volume.rampSeconds xor 1)) },
        "extraLoud" to { a -> a.copy(volume = a.volume.copy(extraLoud = !a.volume.extraLoud)) },
        "useMaxSystemVolume" to { a -> a.copy(volume = a.volume.copy(useMaxSystemVolume = !a.volume.useMaxSystemVolume)) },
        "escalateWhenIgnored" to { a -> a.copy(volume = a.volume.copy(escalateWhenIgnored = !a.volume.escalateWhenIgnored)) },
        "snooze.enabled" to { a -> a.copy(snooze = a.snooze.copy(enabled = !a.snooze.enabled)) },
        "wakeCheck.enabled" to { a -> a.copy(wakeCheck = a.wakeCheck.copy(enabled = !a.wakeCheck.enabled)) },
        "vibration.enabled" to { a -> a.copy(vibration = a.vibration.copy(enabled = !a.vibration.enabled)) },
    )

    /** Owned only while the level has the thing on: snooze details, the still-awake details, Extra loud's strength. */
    private val whileOn: Map<String, Pair<Set<Hardness>, (Alarm) -> Alarm>> = mapOf(
        "minutes" to (setOf(Hardness.EASY, Hardness.NORMAL) to { a -> a.copy(snooze = a.snooze.copy(minutes = a.snooze.minutes + 1)) }),
        "maxCount" to (setOf(Hardness.EASY, Hardness.NORMAL) to { a -> a.copy(snooze = a.snooze.copy(maxCount = a.snooze.maxCount + 1)) }),
        "snoozeMissions" to (setOf(Hardness.EASY, Hardness.NORMAL) to { a -> a.copy(snoozeMissions = listOf(MissionConfig(MissionType.MATH))) }),
        "delayMinutes" to (setOf(Hardness.HARD, Hardness.NO_MERCY) to { a -> a.copy(wakeCheck = a.wakeCheck.copy(delayMinutes = 6)) }),
        "responseSeconds" to (setOf(Hardness.HARD, Hardness.NO_MERCY) to { a -> a.copy(wakeCheck = a.wakeCheck.copy(responseSeconds = 90)) }),
        "maxChecks" to (setOf(Hardness.HARD, Hardness.NO_MERCY) to { a -> a.copy(wakeCheck = a.wakeCheck.copy(maxChecks = 3)) }),
        "maxReRings" to (setOf(Hardness.HARD, Hardness.NO_MERCY) to { a -> a.copy(wakeCheck = a.wakeCheck.copy(maxReRings = 5)) }),
        "requireProof" to (setOf(Hardness.HARD, Hardness.NO_MERCY) to { a -> a.copy(wakeCheck = a.wakeCheck.copy(requireProof = false)) }),
        "extraLoudPercent" to (setOf(Hardness.NO_MERCY) to { a -> a.copy(volume = a.volume.copy(extraLoudPercent = 60)) }),
    )

    @Test fun `each owned field flipped gives Custom, and flipping it back gives the level again`() {
        for (level in HardnessPresets.levels) {
            val base = HardnessPresets.apply(level, Alarm())
            for ((name, flip) in flips) {
                if (name == "vibration.enabled" && level == Hardness.EASY) continue
                assertEquals("$level, $name changed", Hardness.CUSTOM, HardnessPresets.levelOf(flip(base)))
                assertEquals("$level, $name back", level, HardnessPresets.levelOf(flip(flip(base))))
            }
            for ((name, rule) in whileOn) {
                val (levels, flip) = rule
                if (level in levels) assertEquals("$level, $name changed", Hardness.CUSTOM, HardnessPresets.levelOf(flip(base)))
                else assertEquals("$level does not look at $name", level, HardnessPresets.levelOf(flip(base)))
            }
        }
    }

    @Test fun `every owned field of the four settings classes has a flip here`() {
        val covered = flips.keys.map { it.substringAfter('.') } + whileOn.keys
        for ((_, owned) in HardnessPresets.OWNED_FIELDS) for (field in owned) assertTrue("no flip for $field", field in covered)
    }

    @Test fun `a field nobody classified fails`() {
        val classes = listOf(AntiOversleepSettings::class.java, VolumeSettings::class.java, WakeCheckSettings::class.java, SnoozeSettings::class.java)
        for (c in classes) {
            val fields = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
            val owned = HardnessPresets.OWNED_FIELDS.getValue(c.simpleName)
            val notOwned = HardnessPresets.NOT_OWNED_FIELDS.getValue(c.simpleName)
            assertEquals("${c.simpleName}: classified twice", emptySet<String>(), owned intersect notOwned)
            assertEquals("${c.simpleName}: every field is owned by the levels or listed as not owned", fields, owned + notOwned)
        }
    }

    @Test fun `fields the levels do not own never make an alarm Custom`() {
        for (level in HardnessPresets.levels) {
            val a = HardnessPresets.apply(level, Alarm())
            val changed = a.copy(
                label = "x", hour = 4, sound = SoundSettings(mode = SoundMode.RANDOM_BUNDLED), respectExceptions = false,
                volume = a.volume.copy(rampStartPercent = 50), snooze = a.snooze.copy(decreasing = true, decreaseStepMinutes = 4),
                antiOversleep = a.antiOversleep.copy(torchWhenIgnored = true, rotateMissions = !a.antiOversleep.rotateMissions, powerGuard = true),
                vibration = a.vibration.copy(pattern = com.riyadm.wakenudge.domain.model.VibrationPattern.SOS, strength = 40),
            )
            assertEquals(level, HardnessPresets.levelOf(changed))
        }
    }

    @Test fun `adding a mission on Easy moves to Normal, removing the last moves Normal to Easy, Hard refuses`() {
        val easy = HardnessPresets.apply(Hardness.EASY, Alarm())
        val added = HardnessPresets.afterMissionChange(easy, easy.copy(missions = listOf(MissionConfig(MissionType.TYPING))))
        assertTrue(added is HardnessPresets.MissionRule.Moved)
        assertEquals(Hardness.NORMAL, HardnessPresets.levelOf(added.alarm))
        assertEquals("the mission added is the one kept", listOf(MissionConfig(MissionType.TYPING)), added.alarm.missions)

        val normal = HardnessPresets.apply(Hardness.NORMAL, Alarm())
        val emptied = HardnessPresets.afterMissionChange(normal, normal.copy(missions = emptyList()))
        assertEquals(Hardness.EASY, (emptied as HardnessPresets.MissionRule.Moved).to)
        assertEquals(Hardness.EASY, HardnessPresets.levelOf(emptied.alarm))

        for (level in listOf(Hardness.HARD, Hardness.NO_MERCY)) {
            val hard = HardnessPresets.apply(level, Alarm())
            val r = HardnessPresets.afterMissionChange(hard, hard.copy(missions = emptyList()))
            assertTrue(r is HardnessPresets.MissionRule.Refused)
            assertEquals(hard, r.alarm)
        }
        val more = HardnessPresets.afterMissionChange(normal, normal.copy(missions = normal.missions.take(1)))
        assertTrue("fewer, but not none, is just a change", more is HardnessPresets.MissionRule.Kept)
        val custom = starts[1]
        assertTrue(HardnessPresets.afterMissionChange(custom, custom.copy(missions = emptyList())) is HardnessPresets.MissionRule.Kept)
    }

    @Test fun `strict alarms are Hard, No mercy, and Custom ones that kept the slow way out`() {
        assertFalse(HardnessPresets.isStrict(HardnessPresets.apply(Hardness.EASY, Alarm())))
        assertFalse(HardnessPresets.isStrict(HardnessPresets.apply(Hardness.NORMAL, Alarm())))
        assertTrue(HardnessPresets.isStrict(HardnessPresets.apply(Hardness.HARD, Alarm())))
        assertTrue(HardnessPresets.isStrict(HardnessPresets.apply(Hardness.NO_MERCY, Alarm())))
        val hardCustom = HardnessPresets.apply(Hardness.HARD, Alarm()).let { it.copy(antiOversleep = it.antiOversleep.copy(editLockMinutes = 60)) }
        assertTrue(HardnessPresets.isStrict(hardCustom))
        assertFalse(HardnessPresets.isStrict(hardCustom.copy(antiOversleep = hardCustom.antiOversleep.copy(emergencyStopEnabled = true))))
    }

    @Test fun `the Mixed rotation is recognised`() {
        assertTrue(HardnessPresets.isMixed(HardnessPresets.apply(Hardness.NORMAL, Alarm()).missions))
        assertTrue(HardnessPresets.isMixed(HardnessPresets.apply(Hardness.HARD, Alarm()).missions))
        assertFalse(HardnessPresets.isMixed(listOf(MissionConfig(MissionType.MATH))))
    }

    @Test fun `picking Easy puts the missions aside, and a harder level gives them back`() {
        val qr = HardnessPresets.apply(Hardness.HARD, starts[1])
        val easy = HardnessPresets.pick(Hardness.EASY, qr, null)
        assertTrue(easy.alarm.missions.isEmpty())
        assertEquals(Hardness.EASY, HardnessPresets.levelOf(easy.alarm))
        assertEquals(qr.missions, easy.setAside!!.missions)
        val back = HardnessPresets.pick(Hardness.NORMAL, easy.alarm, easy.setAside)
        assertEquals("the registered code comes back, not the Mixed rotation", qr.missions, back.alarm.missions)
        assertEquals(qr.antiOversleep.rotateMissions, back.alarm.antiOversleep.rotateMissions)
        assertEquals(Hardness.NORMAL, HardnessPresets.levelOf(back.alarm))
        assertEquals(null, back.setAside)
        val fresh = HardnessPresets.pick(Hardness.HARD, HardnessPresets.apply(Hardness.EASY, Alarm()), null)
        assertTrue(HardnessPresets.isMixed(fresh.alarm.missions))
        assertEquals("Custom is not a level to pick", qr, HardnessPresets.pick(Hardness.CUSTOM, qr, null).alarm)
    }

    @Test fun `a new alarm is Normal with the Mixed rotation, and a new install's sound is Surprise me`() {
        val made = com.riyadm.wakenudge.domain.model.AlarmDefaults().newAlarm()
        assertEquals(Hardness.NORMAL, HardnessPresets.levelOf(made))
        assertTrue(HardnessPresets.isMixed(made.missions))
        assertTrue(made.antiOversleep.rotateMissions)
        assertEquals(SoundMode.RANDOM_BUNDLED, made.sound.mode)
        assertEquals(SoundMode.RANDOM_BUNDLED, com.riyadm.wakenudge.domain.model.AppSettings().defaults.sound.mode)
        for (level in HardnessPresets.levels) {
            assertEquals(level, HardnessPresets.levelOf(com.riyadm.wakenudge.domain.model.AlarmDefaults(level = level).newAlarm()))
        }
    }

    @Test fun `settings saved before levels existed read as Normal for new alarms and keep their sound`() {
        val json = """{"defaults":{"sound":{"mode":"SINGLE","bundledId":"ember"},"snooze":{"enabled":false}}}"""
        val s = com.riyadm.wakenudge.core.WakeJson.instance.decodeFromString(com.riyadm.wakenudge.domain.model.AppSettings.serializer(), json)
        assertEquals(Hardness.NORMAL, s.defaults.level)
        assertEquals("ember", s.defaults.newAlarm().sound.bundledId)
        assertEquals(SoundMode.SINGLE, s.defaults.newAlarm().sound.mode)
        assertFalse("the card about levels is for people who had alarms before them", s.levelsCardSeen)
    }

    private inline fun Alarm.ao(block: AntiOversleepSettings.() -> AntiOversleepSettings) = copy(antiOversleep = antiOversleep.block())
}
