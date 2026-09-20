package fr.webinfoconcept.secondscreen.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Reconnaissance du tap (SS-041) : un seul clic, au relâchement, sans faux positif. */
class TouchGestureDetectorTest {

    private val taps = mutableListOf<Pair<Float, Float>>()
    private val detector = TouchGestureDetector(slopPx = 8f, maxTapMs = 500) { x, y -> taps += x to y }

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
        lateinit var d: TouchGestureDetector
        var count = 0
        d = TouchGestureDetector(8f) { _, _ -> count++; d.onUp(1f, 1f, 10) }

        d.onDown(1f, 1f, 0)
        d.onUp(1f, 1f, 10)

        assertEquals(1, count)
    }

    @Test
    fun `invalid parameters are refused`() {
        assertThrows(IllegalArgumentException::class.java) { TouchGestureDetector(-1f) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { TouchGestureDetector(Float.NaN) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { TouchGestureDetector(8f, 0) { _, _ -> } }
    }

    // =========================================================== glissement (SS-042)

    /** Journal des rappels de glissement, dans l'ordre. */
    private class DragLog : DragListener {
        val events = mutableListOf<String>()
        override fun onDragStart(x: Float, y: Float) { events += "start($x,$y)" }
        override fun onDragMove(x: Float, y: Float) { events += "move($x,$y)" }
        override fun onDragEnd(x: Float, y: Float) { events += "end($x,$y)" }
    }

    private val log = DragLog()
    private val dragTaps = mutableListOf<Pair<Float, Float>>()
    private val dragDetector = TouchGestureDetector(slopPx = 8f, maxTapMs = 500, dragListener = log) { x, y ->
        dragTaps += x to y
    }

    @Test
    fun `crossing the slop starts the drag at the landing position, then moves to the current one`() {
        dragDetector.onDown(100f, 200f, 0)
        dragDetector.onMove(104f, 200f) // sous le seuil : rien
        assertEquals(emptyList<String>(), log.events)

        dragDetector.onMove(120f, 200f) // 20 px > 8 px

        assertEquals(listOf("start(100.0,200.0)", "move(120.0,200.0)"), log.events)
        assertTrue(dragDetector.isDragging)
        assertFalse(dragDetector.isTracking)
    }

    @Test
    fun `every move is reported and the release ends the drag at the last move position`() {
        dragDetector.onDown(0f, 0f, 0)
        dragDetector.onMove(50f, 0f)
        dragDetector.onMove(60f, 5f)
        dragDetector.onMove(70f, 9f)
        dragDetector.onUp(71f, 10f, 400)

        assertEquals(
            listOf("start(0.0,0.0)", "move(50.0,0.0)", "move(60.0,5.0)", "move(70.0,9.0)", "end(70.0,9.0)"),
            log.events
        )
        assertFalse(dragDetector.isDragging)
        assertTrue("un glissement n'est pas un tap", dragTaps.isEmpty())
    }

    @Test
    fun `the release ignores an ACTION_UP position that disagrees with the last move - no jump back`() {
        // GT-P5110, Android 4.2, `input swipe` : l'UP est envoyé à la position de départ.
        dragDetector.onDown(300f, 300f, 0)
        dragDetector.onMove(340f, 300f)
        dragDetector.onMove(481f, 300f)
        dragDetector.onUp(300f, 300f, 300)

        assertEquals("end(481.0,300.0)", log.events.last())
        assertEquals(1, log.events.count { it.startsWith("end") })
    }

    @Test
    fun `a drag can start after a long hold - the tap time limit does not apply to it`() {
        dragDetector.onDown(0f, 0f, 0)
        dragDetector.onMove(30f, 0f) // à t = 3 s dans la vraie vie : le détecteur ne regarde pas l'heure ici
        dragDetector.onUp(30f, 0f, 3_000)

        assertEquals(listOf("start(0.0,0.0)", "move(30.0,0.0)", "end(30.0,0.0)"), log.events)
    }

    @Test
    fun `coming back to the start does not turn the drag into a tap`() {
        dragDetector.onDown(100f, 100f, 0)
        dragDetector.onMove(160f, 100f)
        dragDetector.onMove(100f, 100f)
        dragDetector.onUp(100f, 100f, 200)

        assertTrue(dragTaps.isEmpty())
        assertEquals("end(100.0,100.0)", log.events.last())
    }

    @Test
    fun `a plain tap still clicks and never starts a drag when a listener is present`() {
        dragDetector.onDown(100f, 100f, 0)
        dragDetector.onMove(103f, 102f)
        dragDetector.onUp(103f, 102f, 80)

        assertEquals(listOf(100f to 100f), dragTaps)
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `an up far from the down with no move event is a drag from the start to the end`() {
        dragDetector.onDown(0f, 0f, 0)
        dragDetector.onUp(300f, 0f, 50)

        assertEquals(listOf("start(0.0,0.0)", "move(300.0,0.0)", "end(300.0,0.0)"), log.events)
        assertTrue(dragTaps.isEmpty())
    }

    @Test
    fun `cancel during a drag releases the button at the last known position, once`() {
        dragDetector.onDown(0f, 0f, 0)
        dragDetector.onMove(40f, 40f)
        dragDetector.onCancel()
        dragDetector.onCancel()
        dragDetector.onUp(40f, 40f, 100)

        assertEquals(listOf("start(0.0,0.0)", "move(40.0,40.0)", "end(40.0,40.0)"), log.events)
        assertTrue(dragTaps.isEmpty())
    }

    @Test
    fun `a second finger during a drag releases the button and ignores the rest of the gesture`() {
        dragDetector.onDown(0f, 0f, 0)
        dragDetector.onMove(40f, 0f)
        dragDetector.onSecondFingerDown()
        dragDetector.onMove(80f, 0f)
        dragDetector.onUp(80f, 0f, 100)

        assertEquals(listOf("start(0.0,0.0)", "move(40.0,0.0)", "end(40.0,0.0)"), log.events)
        assertTrue(dragTaps.isEmpty())
    }

    @Test
    fun `a new down while dragging releases the previous drag first - a lost up never sticks the button`() {
        dragDetector.onDown(0f, 0f, 0)
        dragDetector.onMove(40f, 0f)
        dragDetector.onDown(500f, 500f, 300) // l'ACTION_UP du glissement précédent a été perdu

        assertEquals(listOf("start(0.0,0.0)", "move(40.0,0.0)", "end(40.0,0.0)"), log.events)
        dragDetector.onUp(500f, 500f, 350)
        assertEquals(listOf(500f to 500f), dragTaps)
    }

    @Test
    fun `non finite moves are ignored while dragging and before it`() {
        dragDetector.onDown(0f, 0f, 0)
        dragDetector.onMove(Float.NaN, 0f)
        assertTrue(log.events.isEmpty())
        dragDetector.onMove(40f, 0f)
        dragDetector.onMove(Float.POSITIVE_INFINITY, 0f)
        dragDetector.onUp(Float.NaN, Float.NaN, 100) // relâchement illisible : dernière position connue

        assertEquals(listOf("start(0.0,0.0)", "move(40.0,0.0)", "end(40.0,0.0)"), log.events)
    }

    @Test
    fun `two consecutive drags are independent`() {
        dragDetector.onDown(0f, 0f, 0); dragDetector.onMove(20f, 0f); dragDetector.onUp(20f, 0f, 100)
        dragDetector.onDown(200f, 200f, 500); dragDetector.onMove(200f, 260f); dragDetector.onUp(200f, 260f, 600)

        assertEquals(
            listOf(
                "start(0.0,0.0)", "move(20.0,0.0)", "end(20.0,0.0)",
                "start(200.0,200.0)", "move(200.0,260.0)", "end(200.0,260.0)"
            ),
            log.events
        )
    }

    @Test
    fun `without a listener a move beyond the slop only cancels, as before`() {
        detector.onDown(0f, 0f, 0)
        detector.onMove(50f, 0f)
        detector.onMove(60f, 0f)
        detector.onUp(60f, 0f, 100)

        assertTrue(taps.isEmpty())
        assertFalse(detector.isDragging)
    }

    @Test
    fun `a listener that re-enters the detector cannot produce two ends`() {
        lateinit var d: TouchGestureDetector
        val ends = AtomicInteger()
        val listener = object : DragListener {
            override fun onDragStart(x: Float, y: Float) {}
            override fun onDragMove(x: Float, y: Float) {}
            override fun onDragEnd(x: Float, y: Float) { ends.incrementAndGet(); d.onCancel(); d.onUp(x, y, 0) }
        }
        d = TouchGestureDetector(8f, dragListener = listener) { _, _ -> }

        d.onDown(0f, 0f, 0); d.onMove(50f, 0f); d.onUp(50f, 0f, 100)

        assertEquals(1, ends.get())
    }

    /**
     * Propriété : quelle que soit la suite d'événements (au hasard, valides ou non), les rappels de glissement
     * forment toujours start ... end bien parenthésés, sans jamais rien après un end sans nouveau start, et le
     * glissement est toujours fermé dès que le détecteur n'est plus en glissement.
     */
    @Test
    fun `random event sequences always leave the drag callbacks balanced`() {
        val rnd = java.util.Random(42)
        repeat(300) { seed ->
            val events = mutableListOf<String>()
            var open = false
            val d = TouchGestureDetector(8f, dragListener = object : DragListener {
                override fun onDragStart(x: Float, y: Float) { assertFalse("start dans un glissement ouvert (seq $seed)", open); open = true; events += "S" }
                override fun onDragMove(x: Float, y: Float) { assertTrue("move hors glissement (seq $seed)", open); events += "M" }
                override fun onDragEnd(x: Float, y: Float) { assertTrue("end sans start (seq $seed)", open); open = false; events += "E" }
            }) { _, _ -> }
            var t = 0L
            repeat(200) {
                t += rnd.nextInt(300)
                val x = (rnd.nextInt(400) - 100).toFloat()
                val y = (rnd.nextInt(400) - 100).toFloat()
                when (rnd.nextInt(6)) {
                    0 -> d.onDown(x, y, t)
                    1, 2 -> d.onMove(x, y)
                    3 -> d.onUp(x, y, t)
                    4 -> d.onSecondFingerDown()
                    else -> d.onCancel()
                }
                assertEquals("état incohérent (seq $seed)", open, d.isDragging)
            }
            d.onCancel()
            assertFalse("glissement resté ouvert après annulation (seq $seed)", open)
        }
    }
}
