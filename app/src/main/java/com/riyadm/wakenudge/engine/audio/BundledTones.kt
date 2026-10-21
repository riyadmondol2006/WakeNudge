package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.riyadm.wakenudge.R

enum class ToneCharacter { GENTLE, STANDARD, LOUD }

/** [lufs] is the integrated loudness the tone was normalized to by tools/generate_tones.py. */
data class BundledTone(
    val id: String,
    @StringRes val nameRes: Int,
    @RawRes val rawRes: Int,
    val character: ToneCharacter,
    val lufs: Double,
)

object BundledTones {
    val all: List<BundledTone> = listOf(
        BundledTone("sunrise", R.string.tone_sunrise, R.raw.tone_sunrise, ToneCharacter.GENTLE, -17.0),
        BundledTone("ember", R.string.tone_ember, R.raw.tone_ember, ToneCharacter.GENTLE, -18.0),
        BundledTone("chime", R.string.tone_chime, R.raw.tone_chime, ToneCharacter.GENTLE, -16.0),
        BundledTone("kalimba", R.string.tone_kalimba, R.raw.tone_kalimba, ToneCharacter.GENTLE, -16.0),
        BundledTone("bell", R.string.tone_bell, R.raw.tone_bell, ToneCharacter.STANDARD, -15.0),
        BundledTone("marimba", R.string.tone_marimba, R.raw.tone_marimba, ToneCharacter.STANDARD, -15.0),
        BundledTone("pulse", R.string.tone_pulse, R.raw.tone_pulse, ToneCharacter.STANDARD, -14.0),
        BundledTone("radar", R.string.tone_radar, R.raw.tone_radar, ToneCharacter.STANDARD, -14.0),
        BundledTone("digital", R.string.tone_digital, R.raw.tone_digital, ToneCharacter.LOUD, -13.0),
        BundledTone("classic", R.string.tone_classic, R.raw.tone_classic, ToneCharacter.LOUD, -13.0),
        BundledTone("beacon", R.string.tone_beacon, R.raw.tone_beacon, ToneCharacter.LOUD, -12.0),
        BundledTone("siren", R.string.tone_siren, R.raw.tone_siren, ToneCharacter.LOUD, -11.0),
    )

    /** Layer that joins the main tone after 40 s of being ignored. Not user-selectable. */
    val INTENSIFY = BundledTone("intensify", R.string.tone_intensify, R.raw.tone_intensify, ToneCharacter.LOUD, -10.0)

    /** Distinct tone for backup alarms. Not user-selectable. */
    val BACKUP = BundledTone("backup", R.string.tone_backup, R.raw.tone_backup, ToneCharacter.LOUD, -10.0)

    val default: BundledTone get() = all.first()

    fun byId(id: String?): BundledTone? = all.firstOrNull { it.id == id }

    fun uri(context: Context, tone: BundledTone): Uri = Uri.parse("android.resource://${context.packageName}/${tone.rawRes}")
}
