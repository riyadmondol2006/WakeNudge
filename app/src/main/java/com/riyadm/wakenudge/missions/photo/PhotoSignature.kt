package com.riyadm.wakenudge.missions.photo

import java.util.Base64
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A picture as packed ARGB pixels, row-major. Pure data so the matching code runs (and is tested) on the JVM. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Pixel count must equal width x height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/**
 * A compact, privacy-preserving fingerprint of a scene: a 32x32 normalised grayscale layout (what is where) and a
 * 4x4x4 RGB colour histogram (which colours). It is a few hundred bytes, cannot be turned back into a recognisable
 * photo, and is all the PHOTO mission needs to decide whether the camera is looking at the same place again.
 *
 * @property gray [GRID] x [GRID] luminance after a light blur, normalised to mean 128 and spread 40 so brightness and
 * contrast changes between the reference and a live frame largely cancel out.
 * @property histogram [BINS] fractions summing to 1, indexed `r*16 + g*4 + b` on 2-bit channels.
 */
class PhotoSignature(val gray: ByteArray, val histogram: FloatArray) {
    init {
        require(gray.size == GRID * GRID && histogram.size == BINS)
    }

    fun encode(): String {
        val bytes = ByteArray(1 + gray.size + BINS * 2)
        bytes[0] = VERSION
        gray.copyInto(bytes, 1)
        for (i in 0 until BINS) {
            val v = (histogram[i] * 65535f).roundToInt().coerceIn(0, 65535)
            bytes[1 + gray.size + i * 2] = (v shr 8).toByte()
            bytes[2 + gray.size + i * 2] = v.toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        const val GRID = 32
        const val BINS = 64
        private const val VERSION: Byte = 1

        /** Returns null for anything that is not a signature written by [encode] (corrupted or from another version). */
        fun decode(text: String): PhotoSignature? {
            val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
            if (bytes.size != 1 + GRID * GRID + BINS * 2 || bytes[0] != VERSION) return null
            val gray = bytes.copyOfRange(1, 1 + GRID * GRID)
            val histogram = FloatArray(BINS) { i ->
                val hi = bytes[1 + gray.size + i * 2].toInt() and 0xFF
                val lo = bytes[2 + gray.size + i * 2].toInt() and 0xFF
                ((hi shl 8) or lo) / 65535f
            }
            return PhotoSignature(gray, histogram)
        }
    }
}

/** Turns pictures into [PhotoSignature]s. Camera frames and the reference photo go through exactly this code. */
object PhotoSignatureBuilder {
    /** Below this luminance spread (0-255 scale, measured on the 32x32 grid) a scene is too flat to recognise reliably. */
    const val MIN_DETAIL = 12f

    fun build(image: RgbImage): PhotoSignature {
        val grid = blurredGrid(image)
        return PhotoSignature(normalise(grid), histogram(image))
    }

    /** Standard deviation of the blurred luminance grid: low for a covered lens, a blank wall or a dark room. */
    fun detailOf(image: RgbImage): Float {
        val grid = blurredGrid(image)
        val mean = grid.average().toFloat()
        return sqrt(grid.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / grid.size)
    }

    private fun blurredGrid(image: RgbImage): FloatArray {
        val g = PhotoSignature.GRID
        val luminance = FloatArray(image.width * image.height) {
            val p = image.pixels[it]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val grid = FloatArray(g * g)
        for (oy in 0 until g) {
            val y0 = oy * image.height / g
            val y1 = maxOf(y0 + 1, ((oy + 1) * image.height + g - 1) / g)
            for (ox in 0 until g) {
                val x0 = ox * image.width / g
                val x1 = maxOf(x0 + 1, ((ox + 1) * image.width + g - 1) / g)
                var sum = 0f
                for (y in y0 until y1) for (x in x0 until x1) sum += luminance[y * image.width + x]
                grid[oy * g + ox] = sum / ((y1 - y0) * (x1 - x0))
            }
        }
        return blur(grid, g)
    }

    /** Separable [1 2 1]/4 blur with clamped edges: removes sensor noise and makes small shifts matter less. */
    private fun blur(grid: FloatArray, side: Int): FloatArray {
        val horizontal = FloatArray(grid.size)
        for (y in 0 until side) for (x in 0 until side) {
            val l = grid[y * side + (x - 1).coerceAtLeast(0)]
            val r = grid[y * side + (x + 1).coerceAtMost(side - 1)]
            horizontal[y * side + x] = (l + 2 * grid[y * side + x] + r) / 4f
        }
        val out = FloatArray(grid.size)
        for (y in 0 until side) for (x in 0 until side) {
            val u = horizontal[(y - 1).coerceAtLeast(0) * side + x]
            val d = horizontal[(y + 1).coerceAtMost(side - 1) * side + x]
            out[y * side + x] = (u + 2 * horizontal[y * side + x] + d) / 4f
        }
        return out
    }

    private fun normalise(grid: FloatArray): ByteArray {
        val mean = grid.average().toFloat()
        val std = sqrt(grid.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / grid.size)
        return ByteArray(grid.size) {
            if (std < 1e-3f) 128.toByte() else (128f + (grid[it] - mean) / std * 40f).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    /**
     * 4x4x4 colour histogram with soft binning: every pixel is shared between the neighbouring bins of each channel
     * (trilinear interpolation), so a brightness change that nudges a colour across a bin edge shifts the histogram a
     * little instead of throwing the pixel into a different bin outright.
     */
    private fun histogram(image: RgbImage): FloatArray {
        val counts = FloatArray(PhotoSignature.BINS)
        val low = IntArray(3)
        val high = IntArray(3)
        val weightHigh = FloatArray(3)
        for (p in image.pixels) {
            for (channel in 0 until 3) {
                val value = (p shr (16 - 8 * channel)) and 0xFF
                val position = ((value + 0.5f) / 64f - 0.5f).coerceIn(0f, 3f)
                low[channel] = position.toInt()
                high[channel] = minOf(low[channel] + 1, 3)
                weightHigh[channel] = position - low[channel]
            }
            for (corner in 0 until 8) {
                var weight = 1f
                var bin = 0
                for (channel in 0 until 3) {
                    val useHigh = (corner shr channel) and 1 == 1
                    weight *= if (useHigh) weightHigh[channel] else 1f - weightHigh[channel]
                    bin = bin * 4 + if (useHigh) high[channel] else low[channel]
                }
                counts[bin] += weight
            }
        }
        for (i in counts.indices) counts[i] /= image.pixels.size
        return counts
    }
}
