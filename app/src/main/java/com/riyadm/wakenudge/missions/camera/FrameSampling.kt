package com.riyadm.wakenudge.missions.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import com.riyadm.wakenudge.missions.photo.RgbImage
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Reads CameraX YUV_420_888 frames into the plain arrays the (JVM-testable) matching and decoding code works on. */
internal object FrameSampling {
    /** The Y plane as a tight row-major buffer (row padding removed), still in the sensor's orientation. */
    fun luminance(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val out = ByteArray(width * height)
        if (plane.pixelStride == 1 && rowStride == width) {
            buffer.position(0)
            buffer.get(out, 0, minOf(out.size, buffer.remaining()))
        } else {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                if (plane.pixelStride == 1) {
                    buffer.get(out, row * width, width)
                } else {
                    for (col in 0 until width) out[row * width + col] = buffer.get(row * rowStride + col * plane.pixelStride)
                }
            }
        }
        return out
    }

    /**
     * Downsamples the frame to [outWidth] x [outHeight] RGB, upright (the frame's rotation is applied) and squashed to
     * that size. Luminance is averaged over a small block per output pixel so thin details do not alias; chroma is
     * smooth enough to sample once. Uses BT.601 full-range conversion, matching what camera pipelines emit.
     */
    fun rgb(image: ImageProxy, outWidth: Int, outHeight: Int): RgbImage {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val srcW = image.width
        val srcH = image.height
        val uprightW = if (rotation == 90 || rotation == 270) srcH else srcW
        val uprightH = if (rotation == 90 || rotation == 270) srcW else srcH
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        fun lumaAt(x: Int, y: Int): Int = yBuffer.get(y * yPlane.rowStride + x * yPlane.pixelStride).toInt() and 0xFF

        /** Maps a point of the upright image to the sensor-oriented buffer. */
        fun toSource(u: Float, v: Float): Pair<Int, Int> {
            val (x, y) = when (rotation) {
                90 -> v to srcH - u
                180 -> srcW - u to srcH - v
                270 -> srcW - v to u
                else -> u to v
            }
            return x.toInt().coerceIn(0, srcW - 1) to y.toInt().coerceIn(0, srcH - 1)
        }

        val pixels = IntArray(outWidth * outHeight)
        val subSamples = 3
        for (oy in 0 until outHeight) {
            for (ox in 0 until outWidth) {
                var lumaSum = 0
                for (sy in 0 until subSamples) for (sx in 0 until subSamples) {
                    val u = (ox + (sx + 0.5f) / subSamples) / outWidth * uprightW
                    val v = (oy + (sy + 0.5f) / subSamples) / outHeight * uprightH
                    val (x, y) = toSource(u, v)
                    lumaSum += lumaAt(x, y)
                }
                val luma = lumaSum / (subSamples * subSamples)
                val (cx, cy) = toSource((ox + 0.5f) / outWidth * uprightW, (oy + 0.5f) / outHeight * uprightH)
                val u = (uBuffer.get((cy / 2) * uPlane.rowStride + (cx / 2) * uPlane.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuffer.get((cy / 2) * vPlane.rowStride + (cx / 2) * vPlane.pixelStride).toInt() and 0xFF) - 128
                val r = (luma + 1.402f * v).roundToInt().coerceIn(0, 255)
                val g = (luma - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
                val b = (luma + 1.772f * u).roundToInt().coerceIn(0, 255)
                pixels[oy * outWidth + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RgbImage(outWidth, outHeight, pixels)
    }

    /** Dimensions of the upright frame, for choosing a thumbnail size that keeps the aspect ratio. */
    fun uprightSize(image: ImageProxy): Pair<Int, Int> {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        return if (rotation == 90 || rotation == 270) image.height to image.width else image.width to image.height
    }
}

/** Tiny JPEG thumbnails of the reference photo, small enough to live inside the alarm's stored parameters. */
internal object Thumbnails {
    /** Longest the stored base64 text may be (3 KB), which also bounds the JPEG itself at about 2.3 KB. */
    const val MAX_ENCODED_CHARS = 3 * 1024

    /** Base64 JPEG no longer than [MAX_ENCODED_CHARS], lowering quality and then size until it fits; null if it never does. */
    fun encode(image: RgbImage): String? {
        var bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        var quality = 70
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (encoded.length <= MAX_ENCODED_CHARS) return encoded
            if (quality > 35) {
                quality -= 10
            } else {
                val smallerW = (bitmap.width * 0.8f).roundToInt()
                val smallerH = (bitmap.height * 0.8f).roundToInt()
                if (smallerW < 16 || smallerH < 16) return null
                bitmap = bitmap.scale(smallerW, smallerH)
                quality = 65
            }
        }
    }

    fun decode(encoded: String?): ImageBitmap? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}
