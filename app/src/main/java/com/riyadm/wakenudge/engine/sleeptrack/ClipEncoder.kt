package com.riyadm.wakenudge.engine.sleeptrack

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compresses a few seconds of mono 16-bit sound to AAC in an .m4a file with the phone's own encoder. */
object ClipEncoder {
    private const val BIT_RATE = 32_000
    private const val TIMEOUT_US = 10_000L
    private const val GIVE_UP_MS = 10_000L

    /** Returns false (and leaves no file) when the phone's encoder refuses or stalls. */
    fun encode(pcm: ShortArray, sampleRate: Int, out: File): Boolean {
        val ok = runCatching { encodeOrThrow(pcm, sampleRate, out) }.getOrDefault(false)
        if (!ok) out.delete()
        return ok
    }

    private fun encodeOrThrow(pcm: ShortArray, sampleRate: Int, out: File): Boolean {
        if (pcm.isEmpty()) return false
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm)
        bytes.rewind()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var inputDone = false
            var written = 0
            val deadline = SystemClock.elapsedRealtime() + GIVE_UP_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: return false
                        val position = bytes.position()
                        val n = minOf(buffer.remaining(), bytes.remaining())
                        val slice = bytes.duplicate().apply { limit(position + n) }
                        buffer.put(slice)
                        bytes.position(position + n)
                        val ptsUs = (position / 2).toLong() * 1_000_000L / sampleRate
                        inputDone = !bytes.hasRemaining()
                        codec.queueInputBuffer(index, 0, n, ptsUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val data = codec.getOutputBuffer(outIndex)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (data != null && !config && info.size > 0 && muxerStarted) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            muxer.writeSampleData(track, data, info)
                            written++
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return muxerStarted && written > 0
                    }
                }
            }
            return false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
