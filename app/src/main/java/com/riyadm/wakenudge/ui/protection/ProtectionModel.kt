package com.riyadm.wakenudge.ui.protection

/**
 * What the Protection screen knows about the phone, read again every time the screen comes back to the front, since
 * every step is done in a Settings screen the app cannot watch.
 */
data class ProtectionInputs(
    val sdkInt: Int,
    /** "Display over other apps", which bringing the ring screen back needs. */
    val overlayGranted: Boolean,
    /** How far the user got with the overlay steps; see [OverlayStage]. */
    val overlayStage: OverlayStage = OverlayStage.NOT_TRIED,
    val notificationsOk: Boolean,
    val fullScreenOk: Boolean,
    val batteryExempt: Boolean,
    /** Whether any rehearsal has finished on this phone. Device admin waits for one. */
    val anyRehearsalFinished: Boolean,
    val adminActive: Boolean,
    /** An alarm rings or is snoozed right now: the in-app Turn off waits until it is over. */
    val ringingOrSnoozed: Boolean,
)

/** The overlay grant on a sideloaded app may be blocked until "Allow restricted settings" is used, which nothing can detect. */
enum class OverlayStage {
    NOT_TRIED,

    /** The setting was opened once and the grant is still missing: maybe Android blocked it. */
    OPENED,

    /** App info was opened for "Allow restricted settings": next is the setting again. */
    APP_INFO_OPENED,
}

enum class StepId { OVERLAY, BATTERY, NOTIFICATIONS, FULL_SCREEN, REHEARSAL, ADMIN }

enum class StepState {
    DONE,
    TODO,

    /** Waits for something else first: device admin for a finished rehearsal. */
    LOCKED,
}

data class ProtectionStep(val id: StepId, val state: StepState)

/**
 * The device admin row. It is always there: [canTurnOff] is false only while an alarm rings or is snoozed, and the
 * system route (Settings, Security, Device admin apps) is always shown next to it, so nobody can be left without a way to
 * switch it off.
 */
data class AdminRow(val active: Boolean, val canTurnOn: Boolean, val canTurnOff: Boolean)

/** The setup as a pure function of [ProtectionInputs], so every combination is tested on the JVM. */
object ProtectionPolicy {

    /** Android 13 added "restricted settings" for sideloaded apps; before it the overlay grant is never blocked. */
    const val RESTRICTED_SETTINGS_SDK = 33

    fun steps(i: ProtectionInputs): List<ProtectionStep> = listOf(
        ProtectionStep(StepId.OVERLAY, if (i.overlayGranted) StepState.DONE else StepState.TODO),
        ProtectionStep(StepId.BATTERY, if (i.batteryExempt) StepState.DONE else StepState.TODO),
        ProtectionStep(StepId.NOTIFICATIONS, if (i.notificationsOk) StepState.DONE else StepState.TODO),
        ProtectionStep(StepId.FULL_SCREEN, if (i.fullScreenOk) StepState.DONE else StepState.TODO),
        ProtectionStep(StepId.REHEARSAL, if (i.anyRehearsalFinished) StepState.DONE else StepState.TODO),
        ProtectionStep(
            StepId.ADMIN,
            when {
                i.adminActive -> StepState.DONE
                !i.anyRehearsalFinished -> StepState.LOCKED
                else -> StepState.TODO
            },
        ),
    )

    /** Steps not done yet, the locked one included: it is still a step left. */
    fun stepsLeft(i: ProtectionInputs): Int = steps(i).count { it.state != StepState.DONE }

    fun admin(i: ProtectionInputs): AdminRow = AdminRow(
        active = i.adminActive,
        canTurnOn = !i.adminActive && i.anyRehearsalFinished,
        canTurnOff = i.adminActive && !i.ringingOrSnoozed,
    )

    /** Whether the "Allow restricted settings" help is worth showing: a blocked grant is possible and has been met. */
    fun showRestrictedHelp(i: ProtectionInputs): Boolean =
        !i.overlayGranted && i.sdkInt >= RESTRICTED_SETTINGS_SDK && i.overlayStage != OverlayStage.NOT_TRIED
}
