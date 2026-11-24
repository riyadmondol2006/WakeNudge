package com.riyadm.wakenudge.engine.sounds

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.riyadm.wakenudge.MainActivity
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.domain.scheduling.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Independent media playback; a ring always takes precedence. No alarm engine writes. */
@AndroidEntryPoint
class SleepSoundsService : Service() {
    @Inject lateinit var store: SleepMixStore
    @Inject lateinit var rings: RingSessionStore
    @Inject lateinit var scheduler: AlarmScheduler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null
    private lateinit var audio: AudioManager
    @Volatile private var duck = 1f
    @Volatile private var stopping = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> finish()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck = 0.15f
                    AudioManager.AUDIOFOCUS_GAIN -> duck = 1f
                }
            }, Handler(Looper.getMainLooper())).build()
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { finish() }
    }
    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { rings.session.collectLatest { if (it != null) finish() } }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sleep_sounds_title), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(0))
        if (stopping || rings.session.value != null) { finish(); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            store.error.value = true; finish(); return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                val mix = store.mix.value
                val duration = if (mix.minutes == 0) {
                    val next = scheduler.nextAlarm()
                    if (next == null) { store.error.value = true; finish(); return@launch }
                    next.at.toInstant().toEpochMilli() - System.currentTimeMillis()
                } else mix.minutes * 60000L
                if (duration <= 0) { finish(); return@launch }
                val deadline = SystemClock.elapsedRealtime() + duration
                val pcm = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(9600, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = pcm
                if (rings.session.value != null || stopping) { finish(); return@launch }
                mix.uri?.let { uri ->
                    player = MediaPlayer().apply {
                        setAudioAttributes(attributes); setDataSource(this@SleepSoundsService, android.net.Uri.parse(uri))
                        isLooping = true
                        setOnErrorListener { _, _, _ -> store.error.value = true; finish(); true }
                        setOnPreparedListener { p ->
                            if (!stopping && rings.session.value == null) { p.setVolume(0f, 0f); p.start() }
                        }
                        prepareAsync()
                    }
                }
                store.error.value = false
                store.playing.value = true
                pcm.play()
                val generators = SleepSound.entries.associateWith { SleepGenerator(it, System.nanoTime().toInt() xor it.ordinal) }
                var lastMinute = -1L
                var lastSample = 0f
                while (isActive && !stopping) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0 || rings.session.value != null) break
                    store.remaining.value = remaining
                    val gain = SleepEnvelope.gain(remaining) * duck
                    player?.let { runCatching { it.setVolume(store.mix.value.fileVolume * gain, store.mix.value.fileVolume * gain) } }
                    val minute = (remaining + 59999) / 60000
                    if (minute != lastMinute) { manager.notify(ID, notification(minute)); lastMinute = minute }
                    withContext(Dispatchers.IO) {
                        val out = FloatArray(480)
                        val scratch = FloatArray(480)
                        val layers = store.mix.value.layers
                        for ((kind, volume) in layers) {
                            generators.getValue(kind).fill(scratch)
                            for (i in out.indices) out[i] += scratch[i] * volume / 3f
                        }
                        for (i in out.indices) {
                            val target = out[i] * gain
                            lastSample += (target - lastSample).coerceIn(-0.02f, 0.02f)
                            out[i] = lastSample
                        }
                        if (!stopping && rings.session.value == null) {
                            check(pcm.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING) >= 0)
                        }
                    }
                }
            } catch (_: Exception) { if (!stopping) store.error.value = true }
            finally { finish() }
        }
        return START_NOT_STICKY
    }
    private fun notification(minutes: Long): Notification {
        val stop = PendingIntent.getService(this, ID, Intent(this, SleepSoundsService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, ID + 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(getString(R.string.sleep_sounds_title)).setContentText(getString(R.string.sounds_remaining, minutes))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.sounds_stop), stop).build()
    }
    private fun finish() {
        if (stopping) return
        stopping = true
        // Pause immediately on the main thread; release only once the writer has stopped.
        runCatching { track?.pause() }
        runCatching { player?.release() }; player = null
        audio.abandonAudioFocusRequest(focus)
        store.playing.value = false
        store.remaining.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        job?.cancel()
        stopSelf()
    }
    override fun onDestroy() {
        finish()
        scope.cancel()
        val old = track; track = null
        val writer = job
        CoroutineScope(Dispatchers.IO).launch {
            writer?.join()
            runCatching { old?.release() }
        }
        unregisterReceiver(noisy)
        super.onDestroy()
    }
    companion object {
        const val ID = 730001
        const val CHANNEL = "sleep_sounds_v1"
        const val STOP = "com.riyadm.wakenudge.sounds.STOP"
    }
}
