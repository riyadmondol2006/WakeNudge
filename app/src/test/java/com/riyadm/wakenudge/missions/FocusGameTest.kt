package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.missions.focus.FocusGame
import com.riyadm.wakenudge.missions.focus.FocusStimulus
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusGameTest {
    /** Shows shapes until the target comes up, letting the others go by. */
    private fun FocusGame.untilTarget(): FocusStimulus {
        while (true) {
            val s = next()
            if (isTarget(s)) return s
            assertEquals(FocusGame.Result.LET_GO, letGo(s.id))
        }
    }

    private fun FocusGame.untilDistractor(): FocusStimulus {
        while (true) {
            val s = next()
            if (!isTarget(s)) return s
            assertEquals(FocusGame.Result.MISSED, letGo(s.id))
        }
    }

    @Test fun `only the shape asked for counts, and the game ends after every round`() {
        val game = FocusGame(rounds = 2, hitsPerRound = 3, random = Random(1))
        repeat(2) { round ->
            repeat(2) { assertEquals(FocusGame.Result.HIT, game.tap(game.untilTarget().id)) }
            val last = game.tap(game.untilTarget().id)
            assertEquals(if (round == 0) FocusGame.Result.ROUND_COMPLETE else FocusGame.Result.GAME_COMPLETE, last)
        }
        assertTrue(game.finished)
        assertEquals(2, game.completedRounds)
    }

    @Test fun `a wrong tap starts the round again`() {
        val game = FocusGame(rounds = 1, hitsPerRound = 3, random = Random(2))
        game.tap(game.untilTarget().id)
        game.tap(game.untilTarget().id)
        assertEquals(2, game.hits)
        assertEquals(FocusGame.Result.WRONG, game.tap(game.untilDistractor().id))
        assertEquals(0, game.hits)
        assertFalse(game.finished)
    }

    @Test fun `letting a shape go by costs nothing, missing the target gains nothing`() {
        val game = FocusGame(rounds = 1, hitsPerRound = 3, random = Random(3))
        game.tap(game.untilTarget().id)
        game.untilDistractor()
        assertEquals(1, game.hits)
        val target = game.untilTarget()
        assertEquals(FocusGame.Result.MISSED, game.letGo(target.id))
        assertEquals(1, game.hits)
    }

    @Test fun `a tap on a shape that has gone, or a second tap, does nothing`() {
        val game = FocusGame(rounds = 1, hitsPerRound = 3, random = Random(4))
        val first = game.untilTarget()
        assertEquals(FocusGame.Result.HIT, game.tap(first.id))
        assertEquals(FocusGame.Result.IGNORED, game.tap(first.id))
        val next = game.next()
        assertEquals(FocusGame.Result.IGNORED, game.tap(first.id))
        assertEquals(FocusGame.Result.IGNORED, game.letGo(next.id + 1))
        assertEquals(1, game.hits)
    }

    @Test fun `neither colour nor shape alone gives the target away`() {
        val game = FocusGame(rounds = 1, hitsPerRound = 99, random = Random(5))
        var sameColour = 0
        var sameShape = 0
        repeat(2_000) {
            val s = game.next()
            if (!game.isTarget(s)) {
                if (s.cue.color == game.target.color) sameColour++
                if (s.cue.shape == game.target.shape) sameShape++
                assertTrue("a distractor shares one of the two, never both or neither", (s.cue.color == game.target.color) != (s.cue.shape == game.target.shape))
            }
            game.letGo(s.id)
        }
        assertTrue("$sameColour share the colour", sameColour > 300)
        assertTrue("$sameShape share the shape", sameShape > 300)
    }

    @Test fun `the target moves around the board, never twice in one cell, and never keeps anyone waiting long`() {
        val game = FocusGame(rounds = 1, hitsPerRound = 99, random = Random(6))
        val targetCells = mutableSetOf<Int>()
        var previousCell = -1
        var sinceTarget = 0
        repeat(2_000) {
            val s = game.next()
            assertTrue(s.cell in 0 until FocusGame.CELLS)
            assertTrue("never the same cell twice in a row", s.cell != previousCell)
            previousCell = s.cell
            if (game.isTarget(s)) {
                targetCells += s.cell
                sinceTarget = 0
            } else {
                sinceTarget++
                assertTrue("the target comes at least every fourth shape", sinceTarget <= FocusGame.MAX_WITHOUT_TARGET)
            }
            game.letGo(s.id)
        }
        assertEquals("the target turns up in every cell", FocusGame.CELLS, targetCells.size)
    }

    @Test fun `each round asks for a different shape`() {
        repeat(200) { seed ->
            val game = FocusGame(rounds = 5, hitsPerRound = 1, random = Random(seed))
            var last = game.target
            repeat(4) {
                game.tap(game.untilTarget().id)
                assertTrue(game.target != last)
                last = game.target
            }
        }
    }

    @Test fun `it survives being saved and restored mid-round`() {
        val game = FocusGame(rounds = 3, hitsPerRound = 4, random = Random(7))
        game.tap(game.untilTarget().id)
        game.tap(game.untilTarget().id)
        repeat(4) { game.tap(game.untilTarget().id) }
        val back = FocusGame.restore(game.toSaved(), Random(8))
        assertEquals(game.round, back.round)
        assertEquals(game.hits, back.hits)
        assertEquals(game.target, back.target)
        assertEquals(game.finished, back.finished)
    }

    @Test fun `difficulty asks for more hits and gives less time, and stays fair half asleep`() {
        assertEquals(listOf(3, 4, 5), Difficulty.entries.map(FocusGame::hitsFor))
        val shown = Difficulty.entries.map { FocusGame.timing(it).first }
        assertEquals(shown.sortedDescending(), shown)
        assertTrue("never shorter than a second", shown.all { it >= 1_000 })
        // The default of three medium rounds: about 4 / 0.4 shapes a round at 1.5 s each, comfortably under a minute.
        val (on, gap) = FocusGame.timing(Difficulty.MEDIUM)
        val expected = 3 * (FocusGame.hitsFor(Difficulty.MEDIUM) * 100 / FocusGame.TARGET_PERCENT) * (on + gap)
        assertTrue("about ${expected / 1000} s", expected <= 60_000)
        assertEquals(3, MissionType.FOCUS_TAP.defaultCount)
    }
}

class MissionPickerTest {
    @Test fun `the picker lists every mission once, a scan target first`() {
        assertEquals(MissionType.entries.toSet(), MissionInfo.pickerOrder.toSet())
        assertEquals(MissionType.entries.size, MissionInfo.pickerOrder.size)
        assertEquals(MissionType.QR_BARCODE, MissionInfo.pickerOrder.first())
    }

    @Test fun `the missions that wear off or can be faked say so`() {
        for (type in listOf(MissionType.QR_BARCODE, MissionType.MATH, MissionType.PHOTO, MissionType.STEPS)) assertTrue(type.name, MissionInfo.noteRes(type) != null)
    }

    @Test fun `Focus Tap appends to the list, so what older files and rows hold keeps its meaning`() {
        assertEquals(MissionType.entries.last(), MissionType.FOCUS_TAP)
        assertEquals(11, MissionType.entries.size)
    }
}
