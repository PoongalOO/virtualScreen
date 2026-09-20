package fr.webinfoconcept.secondscreen.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnaissance du tap (SS-041) : un seul clic, au relâchement, sans faux positif. */
class TapDetectorTest {

    private val taps = mutableListOf<Pair<Float, Float>>()
    private val detector = TapDetector(slopPx = 8f, maxTapMs = 500) { x, y -> taps += x to y }

    @Test
    fun `a quick touch without movement is one tap at the touch position`() {
        detector.onDown(100f, 200f, 1_000)
        assertEquals("rien à l'appui : le clic n'est émis qu'au relâchement", 0, taps.size)
        detector.onUp(100f, 200f, 1_080)

        assertEquals(listOf(100f to 200f), taps)
    }

    @Test
    fun `the tap reports where the finger landed even if it lifted a few pixels away`() {
        detector.onDown(100f, 200f, 0)
        detector.onMove(103f, 204f)
        detector.onUp(105f, 205f, 100)

        assertEquals(listOf(100f to 200f), taps)
    }

    @Test
    fun `a duplicated ACTION_UP does not produce a second click`() {
        detector.onDown(10f, 10f, 0)
        detector.onUp(10f, 10f, 50)
        detector.onUp(10f, 10f, 51)
        detector.onUp(10f, 10f, 52)

        assertEquals(1, taps.size)
        assertFalse(detector.isTracking)
    }

    @Test
    fun `an up without a down is ignored`() {
        detector.onUp(10f, 10f, 50)

        assertEquals(0, taps.size)
    }

    @Test
    fun `two separate taps give two clicks`() {
        detector.onDown(10f, 10f, 0); detector.onUp(10f, 10f, 60)
        detector.onDown(20f, 30f, 200); detector.onUp(20f, 30f, 260)

        assertEquals(listOf(10f to 10f, 20f to 30f), taps)
    }

    @Test
    fun `moving beyond the slop cancels the tap even if the finger comes back`() {
        detector.onDown(100f, 100f, 0)
        detector.onMove(100f, 120f) // 20 px > 8 px
        detector.onMove(100f, 100f)
        detector.onUp(100f, 100f, 100)

        assertEquals(0, taps.size)
    }

    @Test
    fun `slop is inclusive - exactly the slop is still a tap, just above is not`() {
        detector.onDown(0f, 0f, 0)
        detector.onMove(8f, 0f)
        detector.onUp(8f, 0f, 50)
        assertEquals(1, taps.size)

        detector.onDown(0f, 0f, 100)
        detector.onMove(8.01f, 0f)
        detector.onUp(8.01f, 0f, 150)
        assertEquals(1, taps.size)
    }

    @Test
    fun `slop is a distance, not a per-axis limit`() {
        detector.onDown(0f, 0f, 0)
        detector.onUp(7f, 7f, 50) // 9.9 px de distance, chaque axe < 8

        assertEquals(0, taps.size)
    }

    @Test
    fun `an up far from the down cancels the tap even with no move event in between`() {
        detector.onDown(0f, 0f, 0)
        detector.onUp(300f, 0f, 50)

        assertEquals(0, taps.size)
    }

    @Test
    fun `a touch held longer than the tap limit is not a tap - reserved for the long press`() {
        detector.onDown(50f, 50f, 0)
        detector.onUp(50f, 50f, 501)
        assertEquals(0, taps.size)

        detector.onDown(50f, 50f, 1_000)
        detector.onUp(50f, 50f, 1_500) // exactement la limite : encore un tap
        assertEquals(1, taps.size)
    }

    @Test
    fun `a second finger cancels the tap and lifting the first finger does not click`() {
        detector.onDown(100f, 100f, 0)
        detector.onSecondFingerDown()
        detector.onUp(100f, 100f, 60) // le premier doigt se lève

        assertEquals(0, taps.size)
        assertFalse(detector.isTracking)
    }

    @Test
    fun `a cancelled gesture never clicks`() {
        detector.onDown(100f, 100f, 0)
        detector.onCancel()
        detector.onUp(100f, 100f, 60)

        assertEquals(0, taps.size)
    }

    @Test
    fun `a new down after a cancelled gesture is a normal tap again`() {
        detector.onDown(1f, 1f, 0); detector.onSecondFingerDown(); detector.onUp(1f, 1f, 10)
        detector.onDown(5f, 6f, 100); detector.onUp(5f, 6f, 150)

        assertEquals(listOf(5f to 6f), taps)
    }

    @Test
    fun `a down while a gesture is running restarts without clicking for the lost one`() {
        detector.onDown(10f, 10f, 0) // son ACTION_UP a été perdu
        detector.onDown(300f, 300f, 400)
        detector.onUp(300f, 300f, 450)

        assertEquals(listOf(300f to 300f), taps)
    }

    @Test
    fun `a time going backwards is not a tap`() {
        detector.onDown(10f, 10f, 1_000)
        detector.onUp(10f, 10f, 900)

        assertEquals(0, taps.size)
    }

    @Test
    fun `non finite coordinates never produce a tap`() {
        detector.onDown(10f, 10f, 0)
        detector.onUp(Float.NaN, 10f, 50)

        assertEquals(0, taps.size)
    }

    @Test
    fun `a move while idle does nothing`() {
        detector.onMove(500f, 500f)
        assertFalse(detector.isTracking)
        assertTrue(taps.isEmpty())
    }

    @Test
    fun `a listener that re-enters the detector cannot cause a double tap`() {
        lateinit var d: TapDetector
        var count = 0
        d = TapDetector(8f) { _, _ -> count++; d.onUp(1f, 1f, 10) }

        d.onDown(1f, 1f, 0)
        d.onUp(1f, 1f, 10)

        assertEquals(1, count)
    }

    @Test
    fun `invalid parameters are refused`() {
        assertThrows(IllegalArgumentException::class.java) { TapDetector(-1f) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { TapDetector(Float.NaN) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { TapDetector(8f, 0) { _, _ -> } }
    }
}
