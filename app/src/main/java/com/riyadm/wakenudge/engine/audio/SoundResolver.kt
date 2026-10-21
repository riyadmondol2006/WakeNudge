package com.riyadm.wakenudge.engine.audio

import com.riyadm.wakenudge.domain.model.SoundMode
import com.riyadm.wakenudge.domain.model.SoundSettings
import com.riyadm.wakenudge.domain.model.SoundSource
import kotlin.random.Random

/** What to actually play. [uri] is null for bundled tones, which are addressed by [bundledId]. */
data class ResolvedSound(
    val bundledId: String?,
    val uri: String?,
    /** Bundled tones and ringtones loop; custom songs play through and chain to the next pick. */
    val loop: Boolean,
) {
    val isBundled: Boolean get() = bundledId != null
}

/** Chooses the concrete sound for a ring, including the "random alarm sound" modes. Pure, so it is unit-tested. */
object SoundResolver {

    fun resolve(settings: SoundSettings, random: Random = Random.Default): ResolvedSound = when (settings.mode) {
        SoundMode.SINGLE -> single(settings)
        SoundMode.RANDOM_BUNDLED -> {
            val pool = BundledTones.all.map { it.id }.filter { settings.randomPool.isEmpty() || it in settings.randomPool }
            ResolvedSound(pool.randomOrNull(random) ?: SoundSettings.DEFAULT_TONE_ID, null, loop = true)
        }
        SoundMode.RANDOM_CUSTOM -> {
            val pick = settings.playlist.randomOrNull(random)
            if (pick != null) ResolvedSound(null, pick, loop = settings.playlist.size <= 1) else fallback()
        }
    }

    /** The next track for a playlist after one finishes, avoiding an immediate repeat when possible. */
    fun nextFromPlaylist(settings: SoundSettings, previousUri: String?, random: Random = Random.Default): ResolvedSound? {
        val candidates = settings.playlist.filter { it != previousUri }.ifEmpty { settings.playlist }
        return candidates.randomOrNull(random)?.let { ResolvedSound(null, it, loop = settings.playlist.size <= 1) }
    }

    fun fallback() = ResolvedSound(SoundSettings.DEFAULT_TONE_ID, null, loop = true)

    private fun single(s: SoundSettings): ResolvedSound = when (s.source) {
        SoundSource.BUNDLED -> ResolvedSound(BundledTones.byId(s.bundledId)?.id ?: SoundSettings.DEFAULT_TONE_ID, null, loop = true)
        SoundSource.SYSTEM -> s.uri?.let { ResolvedSound(null, it, loop = true) } ?: fallback()
        SoundSource.CUSTOM -> s.uri?.let { ResolvedSound(null, it, loop = true) } ?: fallback()
    }
}
