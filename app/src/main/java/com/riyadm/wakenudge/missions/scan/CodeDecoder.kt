package com.riyadm.wakenudge.missions.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** A barcode or QR code read from the camera. */
data class DecodedCode(val text: String, val format: BarcodeFormat)

/**
 * Reads QR codes and common 1D/2D barcodes from the luminance (Y) plane of a camera frame. Tries the frame as is and
 * inverted (light-on-dark codes such as those on dark packaging or shown on a dimmed screen), with the hard-mode
 * search enabled. Not thread-safe: use one instance per analysis thread.
 */
class CodeDecoder {
    private val reader = MultiFormatReader()
    private val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)

    /**
     * @param luminance row-major Y values with no row padding.
     * @param rotationDegrees clockwise rotation the frame needs to be upright (CameraX `rotationDegrees`); 1D barcodes
     * are only found along the scan direction, so the frame is turned upright first.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rotationDegrees: Int = 0): DecodedCode? {
        val upright = LumaRotation.rotate(luminance, width, height, rotationDegrees)
        val source: LuminanceSource = PlanarYUVLuminanceSource(upright.data, upright.width, upright.height, 0, 0, upright.width, upright.height, false)
        return attempt(source) ?: attempt(source.invert())
    }

    private fun attempt(source: LuminanceSource): DecodedCode? = try {
        val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
        DecodedCode(result.text, result.barcodeFormat)
    } catch (_: NotFoundException) {
        null
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

/** Rotates a grayscale buffer by multiples of 90 degrees clockwise. */
object LumaRotation {
    class Frame(val data: ByteArray, val width: Int, val height: Int)

    fun rotate(data: ByteArray, width: Int, height: Int, degrees: Int): Frame = when (((degrees % 360) + 360) % 360) {
        90 -> {
            val out = ByteArray(data.size)
            for (y in 0 until height) for (x in 0 until width) out[x * height + (height - 1 - y)] = data[y * width + x]
            Frame(out, height, width)
        }
        180 -> Frame(ByteArray(data.size) { data[data.size - 1 - it] }, width, height)
        270 -> {
            val out = ByteArray(data.size)
            for (y in 0 until height) for (x in 0 until width) out[(width - 1 - x) * height + y] = data[y * width + x]
            Frame(out, height, width)
        }
        else -> Frame(data, width, height)
    }
}

/** Human-readable names for what was registered, shown in the editor and on the scan screen. */
object CodeLabels {
    private const val MAX_TEXT = 28

    fun formatName(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.QR_CODE -> "QR code"
        BarcodeFormat.DATA_MATRIX -> "Data Matrix"
        BarcodeFormat.AZTEC -> "Aztec"
        BarcodeFormat.PDF_417 -> "PDF417"
        BarcodeFormat.EAN_13 -> "EAN-13"
        BarcodeFormat.EAN_8 -> "EAN-8"
        BarcodeFormat.UPC_A -> "UPC-A"
        BarcodeFormat.UPC_E -> "UPC-E"
        BarcodeFormat.CODE_39 -> "Code 39"
        BarcodeFormat.CODE_93 -> "Code 93"
        BarcodeFormat.CODE_128 -> "Code 128"
        BarcodeFormat.ITF -> "ITF"
        BarcodeFormat.CODABAR -> "Codabar"
        else -> format.name.replace('_', ' ')
    }

    /** Collapses whitespace and cuts long payloads (URLs, vCards) so the label stays on one line. */
    fun shorten(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_TEXT) flat else flat.take(MAX_TEXT - 1).trimEnd() + "…"
    }

    fun label(code: DecodedCode): String = "${formatName(code.format)} · ${shorten(code.text)}"
}
