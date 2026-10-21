package com.riyadm.wakenudge.missions

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.riyadm.wakenudge.missions.nfc.NfcTagIds
import com.riyadm.wakenudge.missions.scan.CodeDecoder
import com.riyadm.wakenudge.missions.scan.CodeLabels
import com.riyadm.wakenudge.missions.scan.DecodedCode
import com.riyadm.wakenudge.missions.scan.LumaRotation
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renders a barcode into a camera-like luminance buffer: dark modules on a light quiet zone, scaled up. */
private class Rendered(val data: ByteArray, val width: Int, val height: Int)

private fun render(text: String, format: BarcodeFormat, scale: Int = 4, invert: Boolean = false, rotate90: Boolean = false): Rendered {
    val hints = mapOf(EncodeHintType.MARGIN to 4)
    val matrix: BitMatrix = MultiFormatWriter().encode(text, format, if (format == BarcodeFormat.QR_CODE) 0 else 220, if (format == BarcodeFormat.QR_CODE) 0 else 90, hints)
    val w = matrix.width * scale
    val h = matrix.height * scale
    val data = ByteArray(w * h) { i ->
        val dark = matrix[(i % w) / scale, (i / w) / scale]
        val value = if (dark != invert) 20 else 235
        value.toByte()
    }
    return if (rotate90) {
        val rotated = LumaRotation.rotate(data, w, h, 90)
        Rendered(rotated.data, rotated.width, rotated.height)
    } else {
        Rendered(data, w, h)
    }
}

class CodeDecoderTest {
    private val decoder = CodeDecoder()

    @Test fun `decodes a QR code`() {
        val image = render("wakenudge-bathroom-7", BarcodeFormat.QR_CODE)
        val code = decoder.decode(image.data, image.width, image.height)
        assertEquals(DecodedCode("wakenudge-bathroom-7", BarcodeFormat.QR_CODE), code)
    }

    @Test fun `decodes 1D barcodes`() {
        val code128 = render("COFFEE-4711", BarcodeFormat.CODE_128)
        assertEquals("COFFEE-4711", decoder.decode(code128.data, code128.width, code128.height)?.text)
        val ean = render("5901234123457", BarcodeFormat.EAN_13)
        assertEquals(DecodedCode("5901234123457", BarcodeFormat.EAN_13), decoder.decode(ean.data, ean.width, ean.height))
    }

    @Test fun `decodes light-on-dark codes`() {
        val image = render("inverted-code", BarcodeFormat.QR_CODE, invert = true)
        assertEquals("inverted-code", decoder.decode(image.data, image.width, image.height)?.text)
    }

    @Test fun `rotates 1D barcodes upright using the frame rotation`() {
        // The camera delivered the buffer turned a quarter turn; telling the decoder the rotation makes the bars horizontal again.
        val sideways = render("ROTATED-1", BarcodeFormat.CODE_128, rotate90 = true)
        assertEquals("ROTATED-1", decoder.decode(sideways.data, sideways.width, sideways.height, rotationDegrees = 270)?.text)
    }

    @Test fun `noise and blank frames decode to nothing`() {
        val random = Random(1)
        val noise = ByteArray(320 * 240) { random.nextInt(256).toByte() }
        assertNull(decoder.decode(noise, 320, 240))
        assertNull(decoder.decode(ByteArray(320 * 240) { 128.toByte() }, 320, 240))
    }

    @Test fun `the decoder can be reused for many frames`() {
        val a = render("first", BarcodeFormat.QR_CODE)
        val b = render("second", BarcodeFormat.QR_CODE)
        repeat(3) {
            assertEquals("first", decoder.decode(a.data, a.width, a.height)?.text)
            assertEquals("second", decoder.decode(b.data, b.width, b.height)?.text)
            assertNull(decoder.decode(ByteArray(100 * 100), 100, 100))
        }
    }

    @Test fun `frame rotation by quarter turns maps pixels correctly`() {
        // 3 wide x 2 high:  1 2 3 / 4 5 6
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)
        val cw = LumaRotation.rotate(data, 3, 2, 90)
        assertEquals(2, cw.width); assertEquals(3, cw.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), cw.data)
        val half = LumaRotation.rotate(data, 3, 2, 180)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), half.data)
        val ccw = LumaRotation.rotate(data, 3, 2, 270)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), ccw.data)
        assertArrayEquals(data, LumaRotation.rotate(data, 3, 2, 0).data)
        assertArrayEquals(data, LumaRotation.rotate(data, 3, 2, 360).data)
        assertArrayEquals(data, LumaRotation.rotate(LumaRotation.rotate(data, 3, 2, 90).data, 2, 3, 270).data)
    }

    @Test fun `labels show the format and a shortened payload`() {
        assertEquals("QR code · hello", CodeLabels.label(DecodedCode("hello", BarcodeFormat.QR_CODE)))
        val long = CodeLabels.label(DecodedCode("https://example.com/a/very/long/path/that/keeps/going", BarcodeFormat.QR_CODE))
        assertTrue(long, long.length < 45 && long.endsWith("…"))
        assertEquals("EAN-13 · 5901234123457", CodeLabels.label(DecodedCode("5901234123457", BarcodeFormat.EAN_13)))
        assertEquals("a b", CodeLabels.shorten("  a \n  b  "))
        assertEquals("Code 128", CodeLabels.formatName(BarcodeFormat.CODE_128))
    }
}

class NfcTagIdsTest {
    private val id = byteArrayOf(0x04, 0xA1.toByte(), 0x0B, 0xC3.toByte(), 0x00, 0x7F, 0xE5.toByte())

    @Test fun `ids are upper case hex`() = assertEquals("04A10BC3007FE5", NfcTagIds.toHex(id))

    @Test fun `matching ignores case and separators`() {
        assertTrue(NfcTagIds.matches("04A10BC3007FE5", NfcTagIds.toHex(id)))
        assertTrue(NfcTagIds.matches("04:a1:0b:c3:00:7f:e5", "04A10BC3007FE5"))
        assertTrue(NfcTagIds.matches("04-A1-0B-C3-00-7F-E5", "04a10bc3007fe5"))
    }

    @Test fun `different or empty ids do not match`() {
        assertFalse(NfcTagIds.matches("04A10BC3007FE5", "04A10BC3007FE6"))
        assertFalse(NfcTagIds.matches("", ""))
        assertFalse(NfcTagIds.matches("04A1", "04A10B"))
    }

    @Test fun `random UIDs are recognised`() {
        assertTrue(NfcTagIds.isRandomUid(byteArrayOf(0x08, 1, 2, 3)))
        assertFalse(NfcTagIds.isRandomUid(byteArrayOf(0x04, 1, 2, 3)))
        assertFalse(NfcTagIds.isRandomUid(id))
    }

    @Test fun `pretty form groups bytes`() = assertEquals("04:A1:0B", NfcTagIds.pretty("04a10b"))
}
