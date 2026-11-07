package com.riyadm.wakenudge.domain.model

import kotlinx.serialization.Serializable

/**
 * How hard an alarm is to get out of. Never stored on the alarm: [HardnessPresets.levelOf] works it out from the concrete
 * settings, which are all the engine ever reads, so a level cannot drift from what rings. [CUSTOM] is any alarm whose
 * details differ from every level.
 */
@Serializable
enum class Hardness { EASY, NORMAL, HARD, NO_MERCY, CUSTOM }

/**
 * The four levels as exact settings (the table in the hard-alarm plan, section 2). [apply] sets every field a level owns
 * and leaves the rest alone; [levelOf] names the level an alarm matches exactly, or [Hardness.CUSTOM].
 *
 * Owned fields are listed in [OWNED_FIELDS], the others in [NOT_OWNED_FIELDS]; a test fails when a settings class gains a
 * field that is in neither, so nobody adds a setting a level forgets about.
 */
object HardnessPresets {

    val levels: List<Hardness> = listOf(Hardness.EASY, Hardness.NORMAL, Hardness.HARD, Hardness.NO_MERCY)

    /** What a level fixes. A null means "not checked" for that level (snooze details while snooze is off, for example). */
    private class Spec(
        /** Null: snooze off. */
        val snooze: Pair<Int, Int>?,
        val ringTimeoutMinutes: Int,
        val backupCount: Int,
        val emergencyStopEnabled: Boolean,
        val wayOutDelayMinutes: Int,
        val wayOutCount: Int,
        val fallbackCount: Int,
        val editLockMinutes: Int,
        val lockOverrideMission: Boolean,
        val volumeFloorPercent: Int,
        val volumeKeyAction: VolumeKeyAction,
        val forceReturn: Boolean,
        val muteBudget: Int,
        val muteSeconds: Int,
        val calmOnlyOnProgress: Boolean,
        val resumeWindowMinutes: Int,
        val maxResumes: Int,
        val fullBrightness: Boolean,
        val forceSpeaker: Boolean,
        val volumeLevel: Int,
        val rampSeconds: Int,
        val useMaxSystemVolume: Boolean,
        /** 0: Extra loud off; otherwise on at this strength. */
        val extraLoud: Int,
        /** Null: vibration not checked. */
        val vibration: Boolean?,
        /** Null: the still-awake check is off. */
        val wakeChecks: Int?,
        /** Null: no mission allowed. Otherwise at least one, and an empty alarm gets the Mixed rotation at this math difficulty. */
        val mixedMath: Difficulty?,
    )

    private val EASY = Spec(
        snooze = 10 to 3, ringTimeoutMinutes = 10, backupCount = 1, emergencyStopEnabled = true,
        wayOutDelayMinutes = 0, wayOutCount = 10, fallbackCount = 3, editLockMinutes = 0, lockOverrideMission = false,
        volumeFloorPercent = 0, volumeKeyAction = VolumeKeyAction.SNOOZE, forceReturn = false, muteBudget = 3, muteSeconds = 30,
        calmOnlyOnProgress = false, resumeWindowMinutes = 10, maxResumes = 3, fullBrightness = false, forceSpeaker = false,
        volumeLevel = 80, rampSeconds = 30, useMaxSystemVolume = false, extraLoud = 0, vibration = null, wakeChecks = null,
        mixedMath = null,
    )
    private val NORMAL = Spec(
        snooze = 5 to 3, ringTimeoutMinutes = AntiOversleepSettings.RING_TIMEOUT_NEVER, backupCount = 1, emergencyStopEnabled = true,
        wayOutDelayMinutes = 0, wayOutCount = 10, fallbackCount = 3, editLockMinutes = 15, lockOverrideMission = false,
        volumeFloorPercent = 50, volumeKeyAction = VolumeKeyAction.NOTHING, forceReturn = true, muteBudget = 3, muteSeconds = 30,
        calmOnlyOnProgress = true, resumeWindowMinutes = 120, maxResumes = 3, fullBrightness = true, forceSpeaker = false,
        volumeLevel = 80, rampSeconds = 30, useMaxSystemVolume = false, extraLoud = 0, vibration = true, wakeChecks = null,
        mixedMath = Difficulty.MEDIUM,
    )
    private val HARD = Spec(
        snooze = null, ringTimeoutMinutes = AntiOversleepSettings.RING_TIMEOUT_NEVER, backupCount = 3, emergencyStopEnabled = false,
        wayOutDelayMinutes = 3, wayOutCount = 10, fallbackCount = 5, editLockMinutes = 480, lockOverrideMission = true,
        volumeFloorPercent = 100, volumeKeyAction = VolumeKeyAction.NOTHING, forceReturn = true, muteBudget = 2, muteSeconds = 20,
        calmOnlyOnProgress = true, resumeWindowMinutes = 720, maxResumes = 5, fullBrightness = true, forceSpeaker = true,
        volumeLevel = 100, rampSeconds = 10, useMaxSystemVolume = true, extraLoud = 0, vibration = true, wakeChecks = 1,
        mixedMath = Difficulty.HARD,
    )
    private val NO_MERCY = Spec(
        snooze = null, ringTimeoutMinutes = AntiOversleepSettings.RING_TIMEOUT_NEVER, backupCount = 3, emergencyStopEnabled = false,
        wayOutDelayMinutes = 10, wayOutCount = 20, fallbackCount = 8, editLockMinutes = 720, lockOverrideMission = true,
        volumeFloorPercent = 100, volumeKeyAction = VolumeKeyAction.NOTHING, forceReturn = true, muteBudget = 1, muteSeconds = 15,
        calmOnlyOnProgress = true, resumeWindowMinutes = 720, maxResumes = 10, fullBrightness = true, forceSpeaker = true,
        volumeLevel = 100, rampSeconds = 5, useMaxSystemVolume = true, extraLoud = 100, vibration = true, wakeChecks = 2,
        mixedMath = Difficulty.HARD,
    )

    private fun spec(level: Hardness): Spec? = when (level) {
        Hardness.EASY -> EASY
        Hardness.NORMAL -> NORMAL
        Hardness.HARD -> HARD
        Hardness.NO_MERCY -> NO_MERCY
        Hardness.CUSTOM -> null
    }

    /** The still-awake check every level that has one uses, apart from how many checks follow a dismissal. */
    private fun wakeCheck(checks: Int) = WakeCheckSettings(
        enabled = true, delayMinutes = 5, responseSeconds = 60, maxChecks = checks, maxReRings = 10, requireProof = true,
    )

    private const val BACKUP_DELAY_MINUTES = 10

    /**
     * The default rotation for a level that needs a mission and has none: Focus Tap, Memory and three sums, one of them
     * each morning. No physical mission, so nobody is shut out of a level by what their body or phone can do.
     */
    fun mixedMissions(math: Difficulty): List<MissionConfig> = listOf(
        MissionConfig(MissionType.FOCUS_TAP),
        MissionConfig(MissionType.MEMORY),
        MissionConfig(MissionType.MATH, difficulty = math, count = 3),
    )

    /** Whether [missions] is the Mixed rotation of some difficulty, so the editor can call it that. */
    fun isMixed(missions: List<MissionConfig>): Boolean =
        Difficulty.entries.any { d -> missions == mixedMissions(d) }

    /**
     * [alarm] with every field [level] owns set to it. Fields no level owns (time, sound, label, rules, which missions)
     * are kept. A level that needs a mission keeps the alarm's missions, or gets the Mixed rotation when it has none;
     * Easy has no mission. [Hardness.CUSTOM] changes nothing.
     */
    fun apply(level: Hardness, alarm: Alarm): Alarm {
        val s = spec(level) ?: return alarm
        val a = alarm.antiOversleep
        val math = s.mixedMath
        val addMixed = math != null && alarm.missions.isEmpty()
        val missions = when {
            math == null -> emptyList()
            addMixed -> mixedMissions(math)
            else -> alarm.missions
        }
        return alarm.copy(
            missions = missions,
            snoozeMissions = if (s.snooze != null) emptyList() else alarm.snoozeMissions,
            snooze = s.snooze?.let { (minutes, max) -> alarm.snooze.copy(enabled = true, minutes = minutes, maxCount = max) }
                ?: alarm.snooze.copy(enabled = false),
            antiOversleep = a.copy(
                ringTimeoutMinutes = s.ringTimeoutMinutes,
                backupEnabled = true, backupCount = s.backupCount, backupDelayMinutes = BACKUP_DELAY_MINUTES,
                holdToDismissSeconds = 0,
                emergencyStopEnabled = s.emergencyStopEnabled,
                wayOutDelayMinutes = s.wayOutDelayMinutes,
                wayOutCount = s.wayOutCount,
                fallbackCount = s.fallbackCount,
                editLockMinutes = s.editLockMinutes,
                lockOverrideMission = s.lockOverrideMission,
                volumeFloorPercent = s.volumeFloorPercent,
                volumeKeyAction = s.volumeKeyAction,
                returnToAlarm = true,
                forceReturn = s.forceReturn,
                muteBudget = s.muteBudget,
                muteSeconds = s.muteSeconds,
                calmOnlyOnProgress = s.calmOnlyOnProgress,
                resumeWindowMinutes = s.resumeWindowMinutes,
                maxResumes = s.maxResumes,
                fullBrightness = s.fullBrightness,
                forceSpeaker = s.forceSpeaker,
                wayOutRemoved = false,
                rotateMissions = if (addMixed) true else a.rotateMissions,
            ),
            volume = alarm.volume.copy(
                level = s.volumeLevel,
                rampSeconds = s.rampSeconds,
                useMaxSystemVolume = s.useMaxSystemVolume,
                extraLoud = s.extraLoud > 0,
                extraLoudPercent = if (s.extraLoud > 0) s.extraLoud else alarm.volume.extraLoudPercent,
                escalateWhenIgnored = true,
            ),
            vibration = s.vibration?.let { alarm.vibration.copy(enabled = it) } ?: alarm.vibration,
            wakeCheck = s.wakeChecks?.let(::wakeCheck) ?: alarm.wakeCheck.copy(enabled = false),
        )
    }

    /** Whether [alarm] has exactly [level]'s settings. */
    fun matches(level: Hardness, alarm: Alarm): Boolean {
        val s = spec(level) ?: return false
        val a = alarm.antiOversleep
        val v = alarm.volume
        val missionsOk = if (s.mixedMath == null) alarm.missions.isEmpty() else alarm.missions.isNotEmpty()
        val snoozeOk = when (val sn = s.snooze) {
            null -> !alarm.snooze.enabled
            else -> alarm.snooze.enabled && alarm.snooze.minutes == sn.first && alarm.snooze.maxCount == sn.second && alarm.snoozeMissions.isEmpty()
        }
        val wakeOk = when (val n = s.wakeChecks) {
            null -> !alarm.wakeCheck.enabled
            else -> alarm.wakeCheck == wakeCheck(n)
        }
        val loudOk = if (s.extraLoud == 0) !v.extraLoud else v.extraLoud && v.extraLoudPercent == s.extraLoud
        return missionsOk && snoozeOk && wakeOk && loudOk &&
            a.ringTimeoutMinutes == s.ringTimeoutMinutes &&
            a.backupEnabled && a.backupCount == s.backupCount && a.backupDelayMinutes == BACKUP_DELAY_MINUTES &&
            a.holdToDismissSeconds == 0 &&
            a.emergencyStopEnabled == s.emergencyStopEnabled &&
            a.wayOutDelayMinutes == s.wayOutDelayMinutes &&
            a.wayOutCount == s.wayOutCount &&
            a.fallbackCount == s.fallbackCount &&
            a.editLockMinutes == s.editLockMinutes &&
            a.lockOverrideMission == s.lockOverrideMission &&
            a.volumeFloorPercent == s.volumeFloorPercent &&
            a.volumeKeyAction == s.volumeKeyAction &&
            a.returnToAlarm &&
            a.forceReturn == s.forceReturn &&
            a.muteBudget == s.muteBudget &&
            a.muteSeconds == s.muteSeconds &&
            a.calmOnlyOnProgress == s.calmOnlyOnProgress &&
            a.resumeWindowMinutes == s.resumeWindowMinutes &&
            a.maxResumes == s.maxResumes &&
            a.fullBrightness == s.fullBrightness &&
            a.forceSpeaker == s.forceSpeaker &&
            !a.wayOutRemoved &&
            v.level == s.volumeLevel &&
            v.rampSeconds == s.rampSeconds &&
            v.useMaxSystemVolume == s.useMaxSystemVolume &&
            v.escalateWhenIgnored &&
            (s.vibration == null || alarm.vibration.enabled == s.vibration)
    }

    /** The level [alarm] matches exactly, or [Hardness.CUSTOM]. */
    fun levelOf(alarm: Alarm): Hardness = levels.firstOrNull { matches(it, alarm) } ?: Hardness.CUSTOM

    /**
     * Whether saving [alarm] should offer a rehearsal: Hard and No mercy, and a Custom alarm that kept their slow way out,
     * since a changed detail does not make it any easier to get out of.
     */
    fun isStrict(alarm: Alarm): Boolean = when (levelOf(alarm)) {
        Hardness.HARD, Hardness.NO_MERCY -> true
        Hardness.CUSTOM -> alarm.missions.isNotEmpty() && !alarm.antiOversleep.emergencyStopEnabled
        Hardness.EASY, Hardness.NORMAL -> false
    }

    /** Missions put aside when an alarm was made Easy, so picking a harder level again gives them back. */
    data class SetAside(val missions: List<MissionConfig>, val rotate: Boolean)

    data class Picked(val alarm: Alarm, val setAside: SetAside?)

    /**
     * Picking [level] in the editor. Easy has no mission, so an alarm's missions are put aside rather than lost (a
     * registered code or tag is work to set up again); a later pick of a level that needs a mission gets them back
     * instead of the Mixed rotation.
     */
    fun pick(level: Hardness, alarm: Alarm, setAside: SetAside?): Picked = when {
        level == Hardness.CUSTOM -> Picked(alarm, setAside)
        level == Hardness.EASY -> Picked(
            apply(level, alarm),
            if (alarm.missions.isNotEmpty()) SetAside(alarm.missions, alarm.antiOversleep.rotateMissions) else setAside,
        )
        alarm.missions.isEmpty() && setAside != null -> Picked(
            apply(level, alarm.copy(missions = setAside.missions, antiOversleep = alarm.antiOversleep.copy(rotateMissions = setAside.rotate))),
            null,
        )
        else -> Picked(apply(level, alarm), setAside)
    }

    /** The answer to a change of an alarm's missions under the level rule. */
    sealed interface MissionRule {
        val alarm: Alarm

        /** Nothing to say: the change stands as made. */
        data class Kept(override val alarm: Alarm) : MissionRule

        /** A mission was added to an Easy alarm, or the last removed from a Normal one: the alarm moved to [to]. */
        data class Moved(override val alarm: Alarm, val to: Hardness) : MissionRule

        /** Hard and No mercy need a mission: the last one stays. [alarm] is the alarm as it was. */
        data class Refused(override val alarm: Alarm, val level: Hardness) : MissionRule
    }

    /**
     * The rule for missions: adding one to an Easy alarm moves it to Normal; removing the last one moves Normal to Easy;
     * on Hard and No mercy removing the last one is refused. A Custom alarm keeps what it was given.
     */
    fun afterMissionChange(before: Alarm, after: Alarm): MissionRule {
        val had = before.missions.isNotEmpty()
        val has = after.missions.isNotEmpty()
        if (had == has) return MissionRule.Kept(after)
        return when (val level = levelOf(before)) {
            Hardness.EASY -> MissionRule.Moved(apply(Hardness.NORMAL, after), Hardness.NORMAL)
            Hardness.NORMAL -> MissionRule.Moved(apply(Hardness.EASY, after), Hardness.EASY)
            Hardness.HARD, Hardness.NO_MERCY -> MissionRule.Refused(before, level)
            Hardness.CUSTOM -> MissionRule.Kept(after)
        }
    }

    /** Fields a level sets, by settings class. `wayOutRemoved` is owned in that every level has it off. */
    val OWNED_FIELDS: Map<String, Set<String>> = mapOf(
        "AntiOversleepSettings" to setOf(
            "holdToDismissSeconds", "backupEnabled", "backupDelayMinutes", "backupCount", "editLockMinutes", "ringTimeoutMinutes",
            "emergencyStopEnabled", "volumeFloorPercent", "volumeKeyAction", "returnToAlarm", "forceReturn", "muteBudget",
            "muteSeconds", "wayOutDelayMinutes", "wayOutCount", "fallbackCount", "calmOnlyOnProgress", "resumeWindowMinutes",
            "lockOverrideMission", "fullBrightness", "forceSpeaker", "maxResumes", "wayOutRemoved",
        ),
        "VolumeSettings" to setOf("level", "rampSeconds", "extraLoud", "extraLoudPercent", "useMaxSystemVolume", "escalateWhenIgnored"),
        "WakeCheckSettings" to setOf("enabled", "delayMinutes", "responseSeconds", "maxChecks", "maxReRings", "requireProof"),
        "SnoozeSettings" to setOf("enabled", "minutes", "maxCount"),
    )

    /**
     * Fields no level touches. `powerGuard` belongs to a feature that was not built; nothing reads it and nothing shows it.
     * `torchWhenIgnored` and `rotateMissions` are the user's own choice at any level.
     */
    val NOT_OWNED_FIELDS: Map<String, Set<String>> = mapOf(
        "AntiOversleepSettings" to setOf("powerGuard", "torchWhenIgnored", "rotateMissions"),
        "VolumeSettings" to setOf("rampStartPercent"),
        "WakeCheckSettings" to emptySet(),
        "SnoozeSettings" to setOf("decreasing", "decreaseStepMinutes"),
    )
}
