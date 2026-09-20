package fr.webinfoconcept.secondscreen.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tap à trois doigts (SS-052) : affiche/masque la barre de commandes, sans faux positif. */
class ThreeFingerTapTest {
    private val tap = ThreeFingerTap(maxDurationMs = 350, maxDriftPx = 32f)

    @Test
    fun `three fingers down and a quick lift is a tap, once`() {
        tap.onThreeFingersDown(500f, 400f, 1_000)
        assertTrue(tap.isTracking)

        assertTrue(tap.onFingerUp(1_120))
        assertFalse("un seul tap par geste", tap.onFingerUp(1_130))
        assertFalse(tap.isTracking)
    }

    @Test
    fun `a slow lift is not a tap`() {
        tap.onThreeFingersDown(500f, 400f, 0)

        assertFalse(tap.onFingerUp(351))
    }

    @Test
    fun `exactly the maximum duration still counts`() {
        tap.onThreeFingersDown(500f, 400f, 0)

        assertTrue(tap.onFingerUp(350))
    }

    @Test
    fun `moving the fingers far cancels the tap, a small drift does not`() {
        tap.onThreeFingersDown(500f, 400f, 0)
        tap.onMove(510f, 405f)
        assertTrue(tap.isTracking)
        tap.onMove(560f, 400f) // 60 px > 32 px
        assertFalse(tap.isTracking)
        assertFalse(tap.onFingerUp(100))

        tap.onThreeFingersDown(500f, 400f, 1_000)
        tap.onMove(520f, 410f)
        assertTrue(tap.onFingerUp(1_100))
    }

    @Test
    fun `coming back after a large move does not resurrect the tap`() {
        tap.onThreeFingersDown(500f, 400f, 0)
        tap.onMove(700f, 400f)
        tap.onMove(500f, 400f)

        assertFalse(tap.onFingerUp(100))
    }

    @Test
    fun `cancel, for example a fourth finger, prevents the tap`() {
        tap.onThreeFingersDown(500f, 400f, 0)
        tap.cancel()

        assertFalse(tap.onFingerUp(100))
    }

    @Test
    fun `a lift without a start is not a tap`() {
        assertFalse(tap.onFingerUp(100))
    }

    @Test
    fun `time going backwards is not a tap`() {
        tap.onThreeFingersDown(500f, 400f, 1_000)

        assertFalse(tap.onFingerUp(900))
    }

    @Test
    fun `non finite coordinates never start or keep a tap`() {
        tap.onThreeFingersDown(Float.NaN, 400f, 0)
        assertFalse(tap.isTracking)

        tap.onThreeFingersDown(500f, 400f, 0)
        tap.onMove(Float.NaN, 400f)
        assertFalse(tap.isTracking)
    }

    @Test
    fun `two consecutive taps are two taps`() {
        tap.onThreeFingersDown(500f, 400f, 0); assertTrue(tap.onFingerUp(100))
        tap.onThreeFingersDown(500f, 400f, 1_000); assertTrue(tap.onFingerUp(1_100))
    }

    @Test
    fun `parameters are validated`() {
        assertThrows(IllegalArgumentException::class.java) { ThreeFingerTap(0, 10f) }
        assertThrows(IllegalArgumentException::class.java) { ThreeFingerTap(100, -1f) }
        assertThrows(IllegalArgumentException::class.java) { ThreeFingerTap(100, Float.NaN) }
    }
}
