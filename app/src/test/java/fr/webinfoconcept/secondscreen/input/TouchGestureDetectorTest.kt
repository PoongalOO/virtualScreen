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

    // =========================================================== appui long (SS-043)

    /** Minuteur simulé : le test décide quand la tâche programmée s'exécute. */
    private class FakeScheduler : DelayScheduler {
        var task: Runnable? = null
        var delayMs = -1L
        var posts = 0
        var cancels = 0
        override fun postDelayed(task: Runnable, delayMs: Long) { this.task = task; this.delayMs = delayMs; posts++ }
        override fun cancel(task: Runnable) { if (this.task === task) this.task = null; cancels++ }
        /** Exécute la tâche si elle est encore programmée ; `false` si elle a été annulée. */
        fun fire(): Boolean { val t = task ?: return false; task = null; t.run(); return true }
        /** Exécute la tâche même annulée : un minuteur déjà parti dans la file d'attente du système. */
        fun fireStale(t: Runnable) = t.run()
    }

    private val lpScheduler = FakeScheduler()
    private val lpEvents = mutableListOf<String>()
    private fun lpDetector(
        threshold: Long = 500,
        scheduler: DelayScheduler? = lpScheduler,
        maxTapMs: Long = 500
    ) = TouchGestureDetector(
        8f, maxTapMs, dragListener = object : DragListener {
            override fun onDragStart(x: Float, y: Float) { lpEvents += "dragStart($x,$y)" }
            override fun onDragMove(x: Float, y: Float) { lpEvents += "dragMove($x,$y)" }
            override fun onDragEnd(x: Float, y: Float) { lpEvents += "dragEnd($x,$y)" }
        },
        longPress = LongPress(threshold, scheduler) { x, y -> lpEvents += "long($x,$y)" }
    ) { x, y -> lpEvents += "tap($x,$y)" }

    @Test
    fun `the timer is programmed at the threshold on every down`() {
        val d = lpDetector(threshold = 650)

        d.onDown(10f, 20f, 0)

        assertEquals(650L, lpScheduler.delayMs)
        assertEquals(1, lpScheduler.posts)
        assertTrue(lpScheduler.task != null)
    }

    @Test
    fun `holding still fires one long press while the finger is down, then the release does nothing`() {
        val d = lpDetector()
        d.onDown(100f, 200f, 0)
        d.onMove(103f, 202f) // frémissement sous le seuil

        assertTrue(lpScheduler.fire())
        assertEquals(listOf("long(100.0,200.0)"), lpEvents)
        assertTrue(d.isLongPressed)
        assertFalse(d.isTracking)

        assertFalse(d.onUp(103f, 202f, 900))
        assertEquals("aucun clic gauche parasite au relâchement", listOf("long(100.0,200.0)"), lpEvents)
        assertFalse(d.isLongPressed)
    }

    @Test
    fun `after a long press the rest of the gesture is ignored - no drag, no tap`() {
        val d = lpDetector()
        d.onDown(100f, 100f, 0)
        lpScheduler.fire()

        d.onMove(400f, 400f)
        d.onMove(500f, 500f)
        d.onUp(500f, 500f, 1_500)

        assertEquals(listOf("long(100.0,100.0)"), lpEvents)
    }

    @Test
    fun `the timer firing twice gives a single long press`() {
        val d = lpDetector()
        d.onDown(5f, 5f, 0)
        val task = lpScheduler.task!!

        lpScheduler.fire()
        lpScheduler.fireStale(task)

        assertEquals(1, lpEvents.count { it.startsWith("long") })
    }

    @Test
    fun `lifting before the threshold is a tap and cancels the timer`() {
        val d = lpDetector()
        d.onDown(50f, 60f, 0)

        assertTrue(d.onUp(50f, 60f, 300))

        assertEquals(listOf("tap(50.0,60.0)"), lpEvents)
        assertFalse("minuteur annulé", lpScheduler.fire())
    }

    @Test
    fun `moving beyond the slop before the threshold makes it a drag and cancels the long press`() {
        val d = lpDetector()
        d.onDown(100f, 100f, 0)
        val task = lpScheduler.task!!

        d.onMove(160f, 100f)
        lpScheduler.fireStale(task) // même un minuteur déjà en route ne peut plus rien déclencher
        d.onUp(160f, 100f, 900)

        assertEquals(listOf("dragStart(100.0,100.0)", "dragMove(160.0,100.0)", "dragEnd(160.0,100.0)"), lpEvents)
        assertFalse(lpScheduler.fire())
    }

    @Test
    fun `a second finger and a cancel both cancel the long press`() {
        val d = lpDetector()

        d.onDown(1f, 1f, 0); d.onSecondFingerDown()
        assertFalse(lpScheduler.fire())
        d.onUp(1f, 1f, 800)

        d.onDown(2f, 2f, 1_000); d.onCancel()
        assertFalse(lpScheduler.fire())
        d.onUp(2f, 2f, 1_800)

        assertTrue(lpEvents.isEmpty())
    }

    @Test
    fun `a stale timer cannot fire after the gesture ended`() {
        val d = lpDetector()
        d.onDown(1f, 1f, 0)
        val task = lpScheduler.task!!
        d.onUp(1f, 1f, 100)
        lpEvents.clear()

        lpScheduler.fireStale(task)

        assertTrue(lpEvents.isEmpty())
        assertFalse(d.isLongPressed)
    }

    @Test
    fun `a timer of a previous touch cannot make the next touch a long press`() {
        val d = lpDetector()
        d.onDown(1f, 1f, 0)
        val task = lpScheduler.task!!
        d.onUp(1f, 1f, 100) // tap
        d.onDown(200f, 200f, 300) // nouveau geste, en attente
        lpEvents.clear()

        // le minuteur de l'ancien geste est annulé par onDown/onUp ; celui du nouveau n'a pas expiré
        assertEquals("un seul minuteur programmé à la fois", task, lpScheduler.task)
        d.onUp(200f, 200f, 380)
        assertEquals(listOf("tap(200.0,200.0)"), lpEvents)
    }

    @Test
    fun `without a scheduler a contact held for the threshold is a long press on release`() {
        val d = lpDetector(threshold = 500, scheduler = null)

        d.onDown(100f, 100f, 1_000); d.onUp(100f, 100f, 1_500) // exactement le seuil
        d.onDown(200f, 200f, 3_000); d.onUp(200f, 200f, 3_499) // juste en dessous
        d.onDown(300f, 300f, 5_000); d.onUp(300f, 300f, 7_000) // très long

        assertEquals(listOf("long(100.0,100.0)", "tap(200.0,200.0)", "long(300.0,300.0)"), lpEvents)
    }

    @Test
    fun `a late timer does not lose the long press - the release timestamp decides`() {
        val d = lpDetector()
        d.onDown(100f, 100f, 0)
        // le fil UI était occupé : l'UP arrive avec 620 ms de contact avant que le minuteur ait pu s'exécuter
        d.onUp(100f, 100f, 620)

        assertEquals(listOf("long(100.0,100.0)"), lpEvents)
        assertFalse(lpScheduler.fire())
    }

    @Test
    fun `no dead zone between tap and long press when the threshold is above the default tap limit`() {
        val d = lpDetector(threshold = 800, scheduler = null, maxTapMs = 500)

        d.onDown(1f, 1f, 0); d.onUp(1f, 1f, 600)   // 600 ms : tap (et non « ni l'un ni l'autre »)
        d.onDown(2f, 2f, 1_000); d.onUp(2f, 2f, 1_799)
        d.onDown(3f, 3f, 3_000); d.onUp(3f, 3f, 3_800)

        assertEquals(listOf("tap(1.0,1.0)", "tap(2.0,2.0)", "long(3.0,3.0)"), lpEvents)
    }

    @Test
    fun `a configurable threshold applies to the timer as well`() {
        for (threshold in listOf(200L, 500L, 1_200L, 3_000L)) {
            val s = FakeScheduler()
            val d = lpDetector(threshold = threshold, scheduler = s)
            d.onDown(0f, 0f, 0)
            assertEquals(threshold, s.delayMs)
            d.onCancel()
        }
    }

    @Test
    fun `a long press on release with a jump beyond the slop is a drag, not a long press`() {
        val d = lpDetector(scheduler = null)

        d.onDown(0f, 0f, 0)
        d.onUp(300f, 0f, 900)

        assertEquals(listOf("dragStart(0.0,0.0)", "dragMove(300.0,0.0)", "dragEnd(300.0,0.0)"), lpEvents)
    }

    @Test
    fun `a long press without a drag listener still works and never taps`() {
        val events = mutableListOf<String>()
        val s = FakeScheduler()
        val d = TouchGestureDetector(8f, longPress = LongPress(500, s) { x, y -> events += "long($x,$y)" }) { x, y ->
            events += "tap($x,$y)"
        }
        d.onDown(9f, 9f, 0); s.fire(); d.onUp(9f, 9f, 700)

        assertEquals(listOf("long(9.0,9.0)"), events)
    }

    @Test
    fun `a listener that re-enters the detector cannot cause a second long press`() {
        lateinit var d: TouchGestureDetector
        var count = 0
        val s = FakeScheduler()
        d = TouchGestureDetector(8f, longPress = LongPress(500, s) { _, _ -> count++; d.onUp(1f, 1f, 900); d.onCancel() }) { _, _ -> }

        d.onDown(1f, 1f, 0)
        s.fire()

        assertEquals(1, count)
    }

    @Test
    fun `long press threshold is validated`() {
        assertThrows(IllegalArgumentException::class.java) { LongPress(199) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { LongPress(3_001) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { LongPress(0) { _, _ -> } }
        assertEquals(500L, LongPress.DEFAULT_THRESHOLD_MS)
        LongPress(200) { _, _ -> }
        LongPress(3_000) { _, _ -> }
    }

    /**
     * Propriété : quelle que soit la suite d'événements (minuteur inclus), un même geste (de `onDown` au suivant)
     * produit AU PLUS UNE issue parmi tap, appui long, glissement : jamais un clic gauche en plus d'un clic droit,
     * jamais un tap après un glissement.
     */
    @Test
    fun `random sequences with a timer give at most one outcome per gesture`() {
        val rnd = java.util.Random(99)
        repeat(300) { seq ->
            val s = FakeScheduler()
            var outcomes = 0
            val d = TouchGestureDetector(
                8f,
                dragListener = object : DragListener {
                    override fun onDragStart(x: Float, y: Float) { outcomes++ }
                    override fun onDragMove(x: Float, y: Float) {}
                    override fun onDragEnd(x: Float, y: Float) {}
                },
                longPress = LongPress(500, s.takeIf { seq % 3 != 0 }) { _, _ -> outcomes++ }
            ) { _, _ -> outcomes++ }
            var t = 0L
            repeat(200) {
                t += rnd.nextInt(400)
                val x = (rnd.nextInt(300) - 50).toFloat()
                val y = (rnd.nextInt(300) - 50).toFloat()
                when (rnd.nextInt(7)) {
                    0 -> { d.onDown(x, y, t); outcomes = 0 } // nouveau geste
                    1, 2 -> d.onMove(x, y)
                    3 -> d.onUp(x, y, t)
                    4 -> d.onSecondFingerDown()
                    5 -> d.onCancel()
                    else -> s.fire()
                }
                assertTrue("plusieurs issues pour un même geste (seq $seq)", outcomes <= 1)
            }
        }
    }
}
