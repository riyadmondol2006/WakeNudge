package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.missions.photo.MatchHold
import com.riyadm.wakenudge.missions.photo.PhotoMatcher
import com.riyadm.wakenudge.missions.photo.PhotoSignature
import com.riyadm.wakenudge.missions.photo.PhotoSignatureBuilder
import com.riyadm.wakenudge.missions.photo.RgbImage
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SIDE = 64

private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
private fun Int.r() = (this shr 16) and 0xFF
private fun Int.g() = (this shr 8) and 0xFF
private fun Int.b() = this and 0xFF

/** A repeatable stand-in for a room: a soft gradient with a few coloured blocks and discs at fixed positions. */
private fun scene(seed: Int, size: Int = SIDE): RgbImage {
    val random = Random(seed)
    val baseR = random.nextInt(60, 200); val baseG = random.nextInt(60, 200); val baseB = random.nextInt(60, 200)
    val pixels = IntArray(size * size) { i ->
        val x = i % size; val y = i / size
        argb(baseR + x / 4, baseG + y / 4, baseB - (x + y) / 6)
    }
    repeat(7) {
        val x0 = random.nextInt(0, size - 10); val y0 = random.nextInt(0, size - 10)
        val w = random.nextInt(8, size / 2); val h = random.nextInt(8, size / 2)
        val color = argb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        for (y in y0 until minOf(size, y0 + h)) for (x in x0 until minOf(size, x0 + w)) pixels[y * size + x] = color
    }
    repeat(3) {
        val cx = random.nextInt(8, size - 8); val cy = random.nextInt(8, size - 8); val rad = random.nextInt(4, 10)
        val color = argb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        for (y in 0 until size) for (x in 0 until size) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) pixels[y * size + x] = color
    }
    return RgbImage(size, size, pixels)
}

private fun RgbImage.map(transform: (Int) -> Int) = RgbImage(width, height, IntArray(pixels.size) { transform(pixels[it]) })
private fun RgbImage.brightness(delta: Int) = map { argb(it.r() + delta, it.g() + delta, it.b() + delta) }
private fun RgbImage.contrast(factor: Float) = map { argb(((it.r() - 128) * factor + 128).roundToInt(), ((it.g() - 128) * factor + 128).roundToInt(), ((it.b() - 128) * factor + 128).roundToInt()) }
/** The same room under a warm bedside lamp (more red, less blue) or cool morning daylight (the reverse). */
private fun RgbImage.tint(red: Float, blue: Float) = map { argb((it.r() * red).roundToInt(), it.g(), (it.b() * blue).roundToInt()) }
private fun RgbImage.noisy(sigma: Int, random: Random) = map {
    val n = random.nextInt(-sigma, sigma + 1)
    argb(it.r() + n, it.g() + n, it.b() + n)
}
private fun RgbImage.shift(dx: Int, dy: Int) = RgbImage(width, height, IntArray(pixels.size) { i ->
    this[(i % width - dx).coerceIn(0, width - 1), (i / width - dy).coerceIn(0, height - 1)]
})
/** Zooms in around the centre by cropping [percent] off each side. */
private fun RgbImage.zoom(percent: Int): RgbImage {
    val margin = width * percent / 100
    val inner = width - 2 * margin
    return RgbImage(width, height, IntArray(pixels.size) { i ->
        this[margin + (i % width) * inner / width, margin + (i / width) * inner / height]
    })
}
private fun random(seed: Int) = Random(seed)
private fun sig(image: RgbImage) = PhotoSignatureBuilder.build(image)
private fun score(reference: RgbImage, frame: RgbImage) = PhotoMatcher.similarity(sig(reference), sig(frame))

class PhotoMatcherTest {
    private val easy = PhotoMatcher.thresholdFor(Difficulty.EASY)

    @Test fun `thresholds follow the difficulty`() {
        assertEquals(0.60f, PhotoMatcher.thresholdFor(Difficulty.EASY), 1e-6f)
        assertEquals(0.68f, PhotoMatcher.thresholdFor(Difficulty.MEDIUM), 1e-6f)
        assertEquals(0.78f, PhotoMatcher.thresholdFor(Difficulty.HARD), 1e-6f)
    }

    @Test fun `a reference taken under a warm lamp still matches the same spot in cool morning light`() {
        // The everyday case: the photo is set up at night with a bedside lamp, the alarm rings in daylight.
        val medium = PhotoMatcher.thresholdFor(Difficulty.MEDIUM)
        var worst = 1f
        for (seed in 0 until 40) {
            val room = scene(seed)
            val s = score(room.tint(red = 1.2f, blue = 0.75f), room.tint(red = 0.9f, blue = 1.15f).brightness(20))
            worst = minOf(worst, s)
            assertTrue("seed $seed: $s", s > medium)
        }
        println("lamp to daylight, worst score: $worst")
    }

    @Test fun `identical pictures score about one`() {
        for (seed in 0 until 20) assertEquals(1f, score(scene(seed), scene(seed)), 0.001f)
    }

    @Test fun `brightness and contrast changes stay above the medium threshold`() {
        val medium = PhotoMatcher.thresholdFor(Difficulty.MEDIUM)
        for (seed in 0 until 20) {
            val reference = scene(seed)
            for ((name, frame) in listOf("+25" to reference.brightness(25), "-25" to reference.brightness(-25), "contrast 0.8" to reference.contrast(0.8f), "contrast 1.25" to reference.contrast(1.25f))) {
                val s = score(reference, frame)
                assertTrue("seed $seed $name: $s", s > medium)
            }
        }
    }

    @Test fun `small lighting changes stay above the hard threshold`() {
        val hard = PhotoMatcher.thresholdFor(Difficulty.HARD)
        for (seed in 0 until 20) {
            val reference = scene(seed)
            for (delta in listOf(-10, 10)) {
                val s = score(reference, reference.brightness(delta))
                assertTrue("seed $seed $delta: $s", s > hard)
            }
        }
    }

    @Test fun `a one pixel shift stays above the easy threshold`() {
        for (seed in 0 until 20) {
            val reference = scene(seed)
            for ((dx, dy) in listOf(1 to 0, 0 to 1, -1 to 1, 2 to -1)) {
                val s = score(reference, reference.shift(dx, dy))
                assertTrue("seed $seed shift $dx,$dy: $s", s > easy)
            }
        }
    }

    @Test fun `sensor noise and a slight zoom still match`() {
        for (seed in 0 until 20) {
            val reference = scene(seed)
            val noisy = score(reference, reference.noisy(10, random(seed)))
            val zoomed = score(reference, reference.zoom(3))
            assertTrue("noise seed $seed: $noisy", noisy > easy)
            assertTrue("zoom seed $seed: $zoomed", zoomed > easy)
        }
    }

    @Test fun `unrelated noise is clearly below the easy threshold`() {
        for (seed in 0 until 20) {
            val random = random(seed + 100)
            val noise = RgbImage(SIDE, SIDE, IntArray(SIDE * SIDE) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) })
            val s = score(scene(seed), noise)
            assertTrue("seed $seed: $s", s < easy - 0.25f)
        }
    }

    @Test fun `a different scene is clearly below the easy threshold`() {
        for (seed in 0 until 30) {
            val s = score(scene(seed), scene(seed + 1000))
            assertTrue("seed $seed: $s", s < easy - 0.1f)
        }
    }

    @Test fun `the same palette arranged differently does not pass`() {
        // Colour alone must not be enough: same blocks, different layout.
        var worst = 0f
        for (seed in 0 until 30) {
            val reference = scene(seed)
            val shuffled = RgbImage(SIDE, SIDE, IntArray(SIDE * SIDE) { reference.pixels[(it * 37 + 11) % reference.pixels.size] })
            worst = maxOf(worst, score(reference, shuffled))
        }
        assertTrue("worst $worst", worst < easy)
    }

    @Test fun `a blank frame never matches a detailed reference`() {
        val blank = RgbImage(SIDE, SIDE, IntArray(SIDE * SIDE) { argb(20, 20, 20) })
        for (seed in 0 until 20) assertTrue(score(scene(seed), blank) < easy - 0.3f)
    }

    @Test fun `flat pictures are reported as having no detail`() {
        assertTrue(PhotoSignatureBuilder.detailOf(RgbImage(SIDE, SIDE, IntArray(SIDE * SIDE) { argb(10, 10, 10) })) < PhotoSignatureBuilder.MIN_DETAIL)
        for (seed in 0 until 10) assertTrue(PhotoSignatureBuilder.detailOf(scene(seed)) > PhotoSignatureBuilder.MIN_DETAIL)
    }

    @Test fun `similarity is symmetric enough and bounded`() {
        for (seed in 0 until 10) {
            val a = sig(scene(seed)); val b = sig(scene(seed + 50))
            val ab = PhotoMatcher.similarity(a, b); val ba = PhotoMatcher.similarity(b, a)
            assertTrue(ab in 0f..1f && ba in 0f..1f)
            assertEquals(ab, ba, 0.05f)
        }
    }

    @Test fun `signatures survive encoding and decoding`() {
        val original = sig(scene(3))
        val text = original.encode()
        assertTrue("short enough to store in the alarm: ${text.length}", text.length < 2000)
        val decoded = PhotoSignature.decode(text)
        assertNotNull(decoded)
        assertArrayEquals(original.gray, decoded!!.gray)
        for (i in original.histogram.indices) assertEquals(original.histogram[i], decoded.histogram[i], 0.0001f)
        assertEquals(1f, PhotoMatcher.similarity(original, decoded), 0.001f)
    }

    @Test fun `garbage is not a signature`() {
        assertNull(PhotoSignature.decode(""))
        assertNull(PhotoSignature.decode("not base64 !!!"))
        assertNull(PhotoSignature.decode("AAAA"))
    }

    @Test fun `histogram sums to one and detects colour`() {
        val red = RgbImage(8, 8, IntArray(64) { argb(250, 10, 10) })
        val signature = sig(red)
        assertEquals(1f, signature.histogram.sum(), 0.001f)
        // Pure red sits in the bin for (high red, low green, low blue) with only a little spill into its neighbours.
        assertTrue(signature.histogram[(3 shl 4) or (0 shl 2) or 0] > 0.8f)
    }

    @Test fun `non-square and tiny sources are accepted`() {
        val wide = RgbImage(160, 90, IntArray(160 * 90) { argb(it % 160, it / 160, 90) })
        assertEquals(PhotoSignature.GRID * PhotoSignature.GRID, sig(wide).gray.size)
        val tiny = RgbImage(5, 3, IntArray(15) { argb(it * 10, 0, 0) })
        assertEquals(PhotoSignature.GRID * PhotoSignature.GRID, sig(tiny).gray.size)
    }
}

class MatchHoldTest {
    private val second = 1_000_000_000L

    @Test fun `a score must stay above the threshold for a full second`() {
        val hold = MatchHold(0.7f)
        var passed = false
        for (i in 0..9) passed = hold.update(i * 100_000_000L, 0.9f)
        assertFalse("0.9 s is not enough", passed)
        assertTrue(hold.progress in 0.85f..0.95f)
        assertTrue(hold.update(10 * 100_000_000L, 0.9f))
        assertEquals(1f, hold.progress, 1e-6f)
    }

    @Test fun `a dip below the threshold restarts the hold`() {
        val hold = MatchHold(0.7f)
        for (i in 0..8) hold.update(i * 100_000_000L, 0.8f)
        assertFalse(hold.update(900_000_000L, 0.5f))
        assertEquals(0f, hold.progress, 1e-6f)
        // The hold starts over from the first good frame after the dip.
        for (i in 10..19) assertFalse(hold.update(i * 100_000_000L, 0.8f))
        assertTrue(hold.update(2_000_000_000L, 0.8f))
    }

    @Test fun `a stalled camera does not count the silence as a match`() {
        val hold = MatchHold(0.7f)
        for (i in 0..5) assertFalse(hold.update(i * 100_000_000L, 0.9f))
        // 0.5 s of good frames, then the camera stalls for 5 s: the silence must not complete the hold.
        assertFalse(hold.update(5 * second, 0.9f))
        for (i in 1..9) assertFalse(hold.update(5 * second + i * 100_000_000L, 0.9f))
        assertTrue(hold.update(6 * second, 0.9f))
    }

    @Test fun `below the threshold from the start never passes`() {
        val hold = MatchHold(0.78f)
        for (i in 0..50) assertFalse(hold.update(i * 100_000_000L, 0.77f))
    }
}
