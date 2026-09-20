package fr.webinfoconcept.secondscreen.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gestes du mode touchpad (SS-045), logique pure : le doigt déplace le pointeur au lieu de le placer. */
class TouchpadDetectorTest {

    private val events = mutableListOf<String>()
    private val listener = object : TouchpadListener {
        override fun onPointerMove(dx: Float, dy: Float) { events += "move($dx,$dy)" }
        override fun onClick() { events += "click" }
        override fun onRightClick() { events += "right" }
        override fun onDragStart() { events += "dragStart" }
        override fun onDragEnd() { events += "dragEnd" }
    }
    private val scrolls = mutableListOf<String>()
    private val scrollListener = object : ScrollListener {
        override fun onScrollStart(x: Float, y: Float) { scrolls += "start" }
        override fun onScroll(clicksX: Int, clicksY: Int) { scrolls += "scroll($clicksX,$clicksY)" }
    }

    private class FakeScheduler : DelayScheduler {
        var task: Runnable? = null
        var posts = 0
        override fun postDelayed(task: Runnable, delayMs: Long) { this.task = task; posts++ }
        override fun cancel(task: Runnable) { if (this.task === task) this.task = null }
        fun fire(): Boolean { val t = task ?: return false; task = null; t.run(); return true }
    }

    private val scheduler = FakeScheduler()

    private fun detector(
        longPress: Boolean = true,
        scheduled: Boolean = true,
        scroll: Boolean = true,
        doubleTapMs: Long = 300
    ) = TouchpadDetector(
        slopPx = 8f,
        listener = listener,
        maxTapMs = 500,
        doubleTapMs = doubleTapMs,
        longPress = if (longPress) LongPress(500, if (scheduled) scheduler else null) { _, _ -> } else null,
        scroll = if (scroll) Scroll(scrollListener, 40f) else null
    )

    // ============================================================ déplacement relatif

    @Test
    fun `dragging a finger moves the pointer by the same amount, and no button is involved`() {
        val d = detector()
        d.onDown(100f, 100f, 0)
        d.onMove(120f, 100f)
        d.onMove(130f, 110f)
        d.onUp(130f, 110f, 300)

        assertEquals(listOf("move(20.0,0.0)", "move(10.0,10.0)"), events)
    }

    @Test
    fun `nothing moves until the slop is crossed, then the whole path so far is applied at once - no movement is lost`() {
        val d = detector()
        d.onDown(100f, 100f, 0)
        d.onMove(103f, 101f)
        d.onMove(105f, 102f)
        assertTrue("sous le seuil : le pointeur ne bouge pas", events.isEmpty())

        d.onMove(115f, 102f)

        assertEquals(listOf("move(15.0,2.0)"), events) // du point de départ (100,100) à (115,102)
    }

    @Test
    fun `the total distance moved equals the finger travel, whatever the event granularity`() {
        for (step in listOf(1f, 2.5f, 7f, 13f, 40f)) {
            events.clear()
            val d = detector()
            d.onDown(0f, 0f, 0)
            var x = 0f
            while (x + step <= 300f) { x += step; d.onMove(x, 0f) }
            val total = events.sumOf { it.removePrefix("move(").substringBefore(",").toDouble() }
            assertEquals("pas $step", x.toDouble(), total, 0.01)
        }
    }

    @Test
    fun `moving back is a negative move, and a finger that returns to the start still moved the pointer`() {
        val d = detector()
        d.onDown(100f, 100f, 0)
        d.onMove(160f, 100f)
        d.onMove(100f, 100f)
        d.onUp(100f, 100f, 400)

        assertEquals(listOf("move(60.0,0.0)", "move(-60.0,0.0)"), events)
        assertFalse("un mouvement n'est pas un tap", events.contains("click"))
    }

    @Test
    fun `a jump without move events moves the pointer and does not click`() {
        val d = detector()
        d.onDown(0f, 0f, 0)

        assertFalse(d.onUp(300f, 0f, 50))

        assertEquals(listOf("move(300.0,0.0)"), events)
    }

    @Test
    fun `non finite coordinates are ignored`() {
        val d = detector()
        d.onDown(0f, 0f, 0)
        d.onMove(Float.NaN, 5f)
        d.onMove(50f, 0f)
        d.onMove(Float.POSITIVE_INFINITY, 0f)

        assertEquals(listOf("move(50.0,0.0)"), events)
    }

    // ============================================================ clics

    @Test
    fun `a quick touch without movement is a left click at the pointer`() {
        val d = detector()
        d.onDown(100f, 100f, 0)

        assertTrue(d.onUp(100f, 100f, 80))

        assertEquals(listOf("click"), events)
    }

    @Test
    fun `a few pixels of jitter do not move the pointer before the click`() {
        val d = detector()
        d.onDown(100f, 100f, 0)
        d.onMove(104f, 103f)
        d.onUp(105f, 104f, 90)

        assertEquals(listOf("click"), events)
    }

    @Test
    fun `two quick taps are two clicks, a double click`() {
        val d = detector()
        d.onDown(100f, 100f, 0); d.onUp(100f, 100f, 60)
        d.onDown(102f, 101f, 200); d.onUp(102f, 101f, 260)

        assertEquals(listOf("click", "click"), events)
    }

    @Test
    fun `two taps too far apart in time are independent taps, the second can still be a long press`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 60)
        d.onDown(0f, 0f, 1_000)
        assertTrue("le second toucher est un toucher ordinaire : appui long armé", scheduler.task != null)
        scheduler.fire()

        assertEquals(listOf("click", "right"), events)
    }

    @Test
    fun `a click is emitted once even if the up is duplicated`() {
        val d = detector()
        d.onDown(0f, 0f, 0)

        d.onUp(0f, 0f, 50)
        d.onUp(0f, 0f, 51)

        assertEquals(listOf("click"), events)
    }

    // ============================================================ appui long = clic droit

    @Test
    fun `holding still fires one right click while the finger is down, then ignores the rest`() {
        val d = detector()
        d.onDown(100f, 100f, 0)

        assertTrue(scheduler.fire())
        d.onMove(300f, 300f) // ignoré
        val up = d.onUp(300f, 300f, 1_500)

        assertFalse(up)
        assertEquals(listOf("right"), events)
        assertFalse(d.isLongPressed)
    }

    @Test
    fun `moving before the delay cancels the long press`() {
        val d = detector()
        d.onDown(100f, 100f, 0)
        d.onMove(150f, 100f)

        assertFalse(scheduler.fire())
        d.onUp(150f, 100f, 900)

        assertEquals(listOf("move(50.0,0.0)"), events)
    }

    @Test
    fun `the release timestamp decides when the timer was late or absent`() {
        val d = detector(scheduled = false)
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 500)        // exactement le seuil
        d.onDown(0f, 0f, 2_000); d.onUp(0f, 0f, 2_499)  // juste en dessous

        assertEquals(listOf("right", "click"), events)
    }

    @Test
    fun `without a long press setting a long touch is ignored, and a short one clicks`() {
        val d = detector(longPress = false)
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 501)
        d.onDown(0f, 0f, 2_000); d.onUp(0f, 0f, 2_400)

        assertEquals(listOf("click"), events)
    }

    // ============================================================ toucher puis glisser

    @Test
    fun `tap then touch and move is a drag - the button goes down before the first move, at the pointer`() {
        val d = detector()
        d.onDown(100f, 100f, 0); d.onUp(100f, 100f, 60)       // tap : clic
        d.onDown(400f, 400f, 200)                              // retouche ailleurs sur la dalle : sans importance
        d.onMove(430f, 400f)
        d.onMove(450f, 420f)
        d.onUp(450f, 420f, 700)

        assertEquals(listOf("click", "dragStart", "move(30.0,0.0)", "move(20.0,20.0)", "dragEnd"), events)
        assertTrue(!d.isDragging)
    }

    @Test
    fun `the touch that follows a tap arms no long press`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 60)
        val before = scheduler.posts

        d.onDown(0f, 0f, 150)

        assertEquals(before, scheduler.posts)
        assertFalse(scheduler.fire())
    }

    @Test
    fun `a held second touch that never moves does not click or drag`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 60)
        d.onDown(0f, 0f, 150)

        d.onUp(0f, 0f, 1_500)

        assertEquals(listOf("click"), events)
    }

    @Test
    fun `after a double tap the next touch is a plain move, not a drag`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 60)
        d.onDown(0f, 0f, 150); d.onUp(0f, 0f, 200)      // double tap
        d.onDown(0f, 0f, 300); d.onMove(50f, 0f); d.onUp(50f, 0f, 500)

        assertEquals(listOf("click", "click", "move(50.0,0.0)"), events)
    }

    @Test
    fun `a drag ends at the release and a new touch afterwards is normal`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 60)
        d.onDown(0f, 0f, 100); d.onMove(40f, 0f); d.onUp(40f, 0f, 400)
        events.clear()

        d.onDown(0f, 0f, 2_000); d.onUp(0f, 0f, 2_050)

        assertEquals(listOf("click"), events)
    }

    // ============================================================ le bouton n'est jamais coincé

    @Test
    fun `cancel during a drag releases the button once`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 50)
        d.onDown(0f, 0f, 100); d.onMove(40f, 0f)
        events.clear()

        d.onCancel(); d.onCancel(); d.onUp(40f, 0f, 200)

        assertEquals(listOf("dragEnd"), events)
    }

    @Test
    fun `a second finger during a drag releases the button and does not scroll`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 50)
        d.onDown(0f, 0f, 100); d.onMove(40f, 0f)
        events.clear()

        d.onTwoFingersDown(30f, 30f)
        d.onTwoFingersMove(30f, -100f)

        assertEquals(listOf("dragEnd"), events)
        assertTrue(scrolls.isEmpty())
    }

    @Test
    fun `a new down while dragging releases the previous drag first`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 50)
        d.onDown(0f, 0f, 100); d.onMove(40f, 0f)
        events.clear()

        d.onDown(200f, 200f, 5_000) // l'ACTION_UP a été perdu

        assertEquals(listOf("dragEnd"), events)
    }

    // ============================================================ défilement à deux doigts

    @Test
    fun `two fingers scroll instead of moving the pointer, and lifting them does nothing`() {
        val d = detector()
        d.onDown(450f, 500f, 0)
        d.onTwoFingersDown(500f, 500f)
        d.onTwoFingersMove(500f, 400f) // 100 px vers le haut : 2 crans, reste 20

        assertEquals(listOf("start", "scroll(0,2)"), scrolls)
        d.onCancel(); d.onUp(500f, 400f, 500)

        assertTrue(events.isEmpty())
        assertFalse(d.isScrolling)
    }

    @Test
    fun `two fingers can also start after the pointer has already moved`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onMove(60f, 0f)
        events.clear()

        d.onTwoFingersDown(100f, 100f)
        d.onTwoFingersMove(100f, 40f)

        assertTrue(d.isScrolling)
        assertEquals(listOf("start", "scroll(0,1)"), scrolls)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a scroll is not followed by a click or a drag, and cancels the double tap window`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 50)                 // tap
        d.onDown(0f, 0f, 100); d.onTwoFingersDown(10f, 10f)    // deuxième doigt : défilement
        d.onCancel(); d.onUp(0f, 0f, 150)
        events.clear()

        d.onDown(0f, 0f, 200); d.onMove(50f, 0f); d.onUp(50f, 0f, 300)

        assertEquals("plus de glissement possible : le défilement a effacé le tap", listOf("move(50.0,0.0)"), events)
    }

    @Test
    fun `a third finger ends everything`() {
        val d = detector()
        d.onDown(0f, 0f, 0)
        d.onTwoFingersDown(10f, 10f)

        d.onSecondFingerDown()
        d.onTwoFingersMove(10f, -200f)

        assertFalse(d.isScrolling)
        assertEquals(listOf("start"), scrolls)
    }

    @Test
    fun `without a scroll setting a second finger just cancels`() {
        val d = detector(scroll = false)
        d.onDown(0f, 0f, 0); d.onMove(50f, 0f)
        events.clear()

        d.onTwoFingersDown(10f, 10f)
        d.onTwoFingersMove(10f, 100f)
        d.onUp(50f, 0f, 100)

        assertTrue(events.isEmpty())
        assertFalse(d.isScrolling)
    }

    // ============================================================ divers

    @Test
    fun `a listener that re-enters the detector cannot cause two drag ends`() {
        lateinit var d: TouchpadDetector
        var ends = 0
        d = TouchpadDetector(8f, object : TouchpadListener {
            override fun onPointerMove(dx: Float, dy: Float) {}
            override fun onClick() {}
            override fun onRightClick() {}
            override fun onDragStart() {}
            override fun onDragEnd() { ends++; d.onCancel(); d.onUp(0f, 0f, 0) }
        })
        d.onDown(0f, 0f, 0); d.onUp(0f, 0f, 50)
        d.onDown(0f, 0f, 100); d.onMove(40f, 0f)

        d.onUp(40f, 0f, 200)

        assertEquals(1, ends)
    }

    @Test
    fun `parameters are validated`() {
        assertThrows(IllegalArgumentException::class.java) { TouchpadDetector(-1f, listener) }
        assertThrows(IllegalArgumentException::class.java) { TouchpadDetector(Float.NaN, listener) }
        assertThrows(IllegalArgumentException::class.java) { TouchpadDetector(8f, listener, maxTapMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { TouchpadDetector(8f, listener, doubleTapMs = 0) }
    }

    /**
     * Propriété : quelle que soit la suite d'événements, [TouchpadListener.onDragStart] et [TouchpadListener.onDragEnd]
     * sont toujours bien parenthésés, et un clic (ou un clic droit) n'est jamais mêlé à un déplacement ou à un défilement dans un même toucher.
     */
    @Test
    fun `random event sequences keep drags balanced and never mix a click with anything else in one touch`() {
        val rnd = java.util.Random(45)
        repeat(300) { seq ->
            val sched = FakeScheduler()
            var open = false
            var family = ""
            var violation: String? = null
            // Un clic ou un clic droit exclut tout le reste dans un même toucher ; déplacer puis défiler est permis.
            fun note(kind: String) {
                val compatible = family.isEmpty() || family == kind ||
                    (family in setOf("déplacement", "défilement") && kind in setOf("déplacement", "défilement"))
                if (!compatible) violation = "$family puis $kind (seq $seq)"
                family = kind
            }
            val d = TouchpadDetector(
                8f,
                object : TouchpadListener {
                    override fun onPointerMove(dx: Float, dy: Float) { note("déplacement") }
                    override fun onClick() { note("clic") }
                    override fun onRightClick() { note("droit") }
                    override fun onDragStart() { assertFalse("start dans un glissement ouvert (seq $seq)", open); open = true }
                    override fun onDragEnd() { assertTrue("end sans start (seq $seq)", open); open = false }
                },
                longPress = LongPress(500, sched.takeIf { seq % 3 != 0 }) { _, _ -> },
                scroll = Scroll(object : ScrollListener {
                    override fun onScrollStart(x: Float, y: Float) { note("défilement") }
                    override fun onScroll(clicksX: Int, clicksY: Int) { note("défilement") }
                })
            )
            var t = 0L
            repeat(250) {
                t += rnd.nextInt(400)
                val x = (rnd.nextInt(300) - 50).toFloat()
                val y = (rnd.nextInt(300) - 50).toFloat()
                when (rnd.nextInt(8)) {
                    0 -> { d.onDown(x, y, t); family = "" }
                    1, 2 -> d.onMove(x, y)
                    3 -> d.onUp(x, y, t)
                    4 -> d.onTwoFingersDown(x, y)
                    5 -> d.onTwoFingersMove(x, y)
                    6 -> d.onCancel()
                    else -> sched.fire()
                }
                assertEquals(null, violation)
                assertEquals("état de glissement incohérent (seq $seq)", open, d.isDragging)
            }
            d.onCancel()
            assertFalse("glissement resté ouvert (seq $seq)", open)
        }
    }
}
