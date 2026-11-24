package com.riyadm.wakenudge.engine.sounds

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

data class SleepMix(val layers: Map<SleepSound, Float> = mapOf(SleepSound.RAIN to 0.5f), val uri: String? = null, val fileVolume: Float = 0.5f, val minutes: Int = 30)

@Singleton
class SleepMixStore @Inject constructor(private val database: SleepSoundsDatabase) {
    private val mutable = MutableStateFlow(SleepMix())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ready = MutableStateFlow(false)
    private val writes = Channel<SleepMix>(Channel.UNLIMITED)
    @Volatile private var edited = false
    init {
        scope.launch {
            val saved = runCatching { database.mix().load()?.json?.let(::decode) }.getOrNull()
            withContext(Dispatchers.Main.immediate) { if (!edited && saved != null) mutable.value = saved; ready.value = true }
            for (mix in writes) runCatching { database.mix().save(SavedSleepMix(json = encode(mix))) }.onFailure { error.value = true }
        }
    }
    private fun decode(text: String): SleepMix {
        val json = JSONObject(text)
        val layers = SleepSound.entries.mapNotNull { kind ->
            if (json.has(kind.name)) kind to json.getDouble(kind.name).toFloat().coerceIn(0f, 1f) else null
        }.take(3).toMap()
        return SleepMix(layers, json.optString("uri").takeIf { it.startsWith("content:") }, json.optDouble("volume", 0.5).toFloat().coerceIn(0f, 1f), json.optInt("minutes", 30))
    }
    val mix = mutable.asStateFlow()
    val playing = MutableStateFlow(false)
    val remaining = MutableStateFlow(0L)
    val error = MutableStateFlow(false)
    fun save(value: SleepMix) {
        val safe = value.copy(layers = value.layers.entries.take(3).associate { it.key to it.value.coerceIn(0f, 1f) })
        edited = true
        mutable.value = safe
        writes.trySend(safe)
    }
    private fun encode(safe: SleepMix): String {
        val json = JSONObject().put("volume", safe.fileVolume).put("minutes", safe.minutes)
        safe.uri?.let { json.put("uri", it) }
        safe.layers.forEach { (k, v) -> json.put(k.name, v) }
        return json.toString()
    }
}
