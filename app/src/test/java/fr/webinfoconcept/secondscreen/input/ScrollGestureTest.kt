package fr.webinfoconcept.secondscreen.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Défilement à deux doigts -> crans de molette (SS-044), logique pure. */
class ScrollGestureTest {

    private val events = mutableListOf<String>()
    private val listener = object : ScrollListener {
        override fun onScrollStart(x: Float, y: Float) { events += "start($x,$y)" }
        override fun onScroll(clicksX: Int, clicksY: Int) { events += "scroll($clicksX,$clicksY)" }
    }
    private val taps = mutableListOf<Pair<Float, Float>>()
    private val drags = mutableListOf<String>()
    private val dragListener = object : DragListener {
        override fun onDragStart(x: Float, y: Float) { drags += "start" }
        override fun onDragMove(x: Float, y: Float) { drags += "move" }
        override fun onDragEnd(x: Float, y: Float) { drags += "end" }
    }

    private fun detector(natural: Boolean = true, stepPx: Float = 40f, scroll: Boolean = true, longPress: LongPress? = null) =
        TouchGestureDetector(
            8f, dragListener = dragListener, longPress = longPress,
            scroll = if (scroll) Scroll(listener, stepPx, natural) else null
        ) { x, y -> taps += x to y }

    /** Pose deux doigts dont le centre est en (cx, cy). */
    private fun TouchGestureDetector.twoFingers(cx: Float = 500f, cy: Float = 400f) {
        onDown(cx - 50f, cy, 0)
        onTwoFingersDown(cx, cy)
    }

    // ------------------------------------------------------------------ sens

    @Test
    fun `natural scrolling - fingers up scroll the wheel down, fingers down scroll it up`() {
        val d = detector()
        d.twoFingers()

        d.onTwoFingersMove(500f, 360f) // 40 px vers le haut
        d.onTwoFingersMove(500f, 400f) // 40 px vers le bas

        assertEquals(listOf("start(500.0,400.0)", "scroll(0,1)", "scroll(0,-1)"), events)
    }

    @Test
    fun `mouse-wheel direction inverts the sign`() {
        val d = detector(natural = false)
        d.twoFingers()

        d.onTwoFingersMove(500f, 360f)
        d.onTwoFingersMove(500f, 400f)

        assertEquals(listOf("start(500.0,400.0)", "scroll(0,-1)", "scroll(0,1)"), events)
    }

    @Test
    fun `natural horizontal scrolling - fingers left scroll right`() {
        val d = detector()
        d.twoFingers()

        d.onTwoFingersMove(460f, 400f) // 40 px vers la gauche
        d.onTwoFingersMove(500f, 400f) // retour

        assertEquals(listOf("start(500.0,400.0)", "scroll(1,0)", "scroll(-1,0)"), events)
    }

    // -------------------------------------------------------------- accumulation

    @Test
    fun `one click per step, the remainder is kept for the next move`() {
        val d = detector(stepPx = 40f)
        d.twoFingers()

        d.onTwoFingersMove(500f, 385f) // 15
        d.onTwoFingersMove(500f, 370f) // 30
        assertEquals(listOf("start(500.0,400.0)"), events)
        d.onTwoFingersMove(500f, 355f) // 45 -> 1 cran, reste 5
        d.onTwoFingersMove(500f, 320f) // reste 5 + 35 = 40 -> 1 cran

        assertEquals(listOf("start(500.0,400.0)", "scroll(0,1)", "scroll(0,1)"), events)
    }

    @Test
    fun `a long move gives several clicks at once and keeps the fraction`() {
        val d = detector(stepPx = 40f)
        d.twoFingers()

        d.onTwoFingersMove(500f, 400f - 130f) // 130 px = 3 crans, reste 10

        assertEquals(listOf("start(500.0,400.0)", "scroll(0,3)"), events)
        d.onTwoFingersMove(500f, 400f - 130f - 30f) // 10 + 30 = 40 -> 1 cran

        assertEquals("scroll(0,1)", events.last())
    }

    @Test
    fun `total clicks equal total travel over the step, whatever the event granularity`() {
        for (granularity in listOf(1f, 3f, 7f, 13f, 40f, 97f)) {
            events.clear()
            val d = detector(stepPx = 40f)
            d.twoFingers()
            var y = 400f
            var travelled = 0f
            while (travelled + granularity <= 1_000f) {
                y -= granularity; travelled += granularity
                d.onTwoFingersMove(500f, y)
            }
            val clicks = events.filter { it.startsWith("scroll") }.sumOf { it.substringAfter(",").trimEnd(')').toInt() }
            assertEquals("granularité $granularity", (travelled / 40f).toInt(), clicks)
        }
    }

    @Test
    fun `small jitter below a step never scrolls`() {
        val d = detector()
        d.twoFingers()

        for (i in 0 until 100) d.onTwoFingersMove(500f + (i % 2) * 3f, 400f + (i % 3) * 2f)

        assertEquals(listOf("start(500.0,400.0)"), events)
    }

    @Test
    fun `moving back and forth does not accumulate clicks`() {
        val d = detector()
        d.twoFingers()

        for (i in 0 until 50) { d.onTwoFingersMove(500f, 380f); d.onTwoFingersMove(500f, 400f) }

        assertEquals(listOf("start(500.0,400.0)"), events)
    }

    // ----------------------------------------------------------------- un seul axe

    @Test
    fun `sideways slip during a vertical scroll never produces a horizontal click`() {
        val d = detector()
        d.twoFingers()
        var x = 500f
        var y = 400f
        for (i in 0 until 60) { x += 3f; y -= 12f; d.onTwoFingersMove(x, y) } // 3 px de dérive latérale par cran de 12

        val scrolls = events.filter { it.startsWith("scroll") }
        assertTrue(scrolls.isNotEmpty())
        assertTrue("aucun cran horizontal : $scrolls", scrolls.all { it.startsWith("scroll(0,") })
    }

    @Test
    fun `a mostly horizontal move scrolls horizontally only`() {
        val d = detector()
        d.twoFingers()
        var x = 500f
        var y = 400f
        for (i in 0 until 40) { x -= 12f; y += 2f; d.onTwoFingersMove(x, y) }

        val scrolls = events.filter { it.startsWith("scroll") }
        assertTrue(scrolls.isNotEmpty())
        assertTrue("aucun cran vertical : $scrolls", scrolls.all { it.endsWith(",0)") })
    }

    @Test
    fun `a click event never carries both axes`() {
        val rnd = java.util.Random(5)
        val d = detector()
        d.twoFingers()
        repeat(2_000) { d.onTwoFingersMove(rnd.nextInt(1_000).toFloat(), rnd.nextInt(800).toFloat()) }

        for (e in events.filter { it.startsWith("scroll") }) {
            val (x, y) = e.removePrefix("scroll(").removeSuffix(")").split(",").map { it.toInt() }
            assertTrue("les deux axes dans $e", x == 0 || y == 0)
        }
    }

    // -------------------------------------------------------------------- bornes

    @Test
    fun `a huge jump is capped and the remainder is forgotten`() {
        val d = detector(stepPx = 40f)
        d.twoFingers()

        d.onTwoFingersMove(500f, 400f - 10_000f)

        assertEquals(listOf("start(500.0,400.0)", "scroll(0,${Scroll.MAX_CLICKS_PER_EVENT})"), events)
        d.onTwoFingersMove(500f, 400f - 10_000f - 10f) // reste oublié : 10 px ne font pas un cran
        assertEquals(2, events.size)
    }

    @Test
    fun `non finite centers are ignored and do not corrupt the state`() {
        val d = detector()
        d.twoFingers()

        d.onTwoFingersMove(Float.NaN, 300f)
        d.onTwoFingersMove(500f, Float.POSITIVE_INFINITY)
        d.onTwoFingersMove(500f, 360f)

        assertEquals(listOf("start(500.0,400.0)", "scroll(0,1)"), events)
    }

    @Test
    fun `a non finite start center means no scroll`() {
        val d = detector()
        d.onDown(500f, 400f, 0)

        d.onTwoFingersDown(Float.NaN, 400f)
        d.onTwoFingersMove(500f, 300f)

        assertTrue(events.isEmpty())
        assertFalse(d.isScrolling)
    }

    // ------------------------------------------------------- jamais de clic parasite

    @Test
    fun `lifting the fingers after a scroll produces no tap, no click, no drag`() {
        val d = detector()
        d.twoFingers()
        d.onTwoFingersMove(500f, 300f)

        d.onCancel()                     // ACTION_POINTER_UP : le nombre de doigts change
        d.onUp(450f, 400f, 900)          // ACTION_UP du dernier doigt

        assertTrue(taps.isEmpty())
        assertTrue(drags.isEmpty())
        assertFalse(d.isScrolling)
    }

    @Test
    fun `a two finger tap without movement sends nothing`() {
        val d = detector()
        d.twoFingers()
        d.onCancel(); d.onUp(450f, 400f, 80)

        assertEquals(listOf("start(500.0,400.0)"), events)
        assertTrue(taps.isEmpty())
    }

    @Test
    fun `the last finger going up during a scroll also ends it without a tap`() {
        val d = detector()
        d.twoFingers()
        d.onTwoFingersMove(500f, 300f)

        assertFalse(d.onUp(500f, 300f, 700))

        assertTrue(taps.isEmpty())
        assertFalse(d.isScrolling)
    }

    @Test
    fun `after a scroll ends, moves do not scroll again until the next down`() {
        val d = detector()
        d.twoFingers()
        d.onCancel()
        events.clear()

        d.onTwoFingersMove(500f, 300f)
        d.onMove(500f, 300f)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a third finger ends the scroll`() {
        val d = detector()
        d.twoFingers()
        d.onTwoFingersMove(500f, 360f)
        events.clear()

        d.onSecondFingerDown() // troisième doigt
        d.onTwoFingersMove(500f, 300f)

        assertTrue(events.isEmpty())
        assertFalse(d.isScrolling)
    }

    @Test
    fun `a new gesture after a scroll is a normal tap`() {
        val d = detector()
        d.twoFingers(); d.onTwoFingersMove(500f, 300f); d.onCancel(); d.onUp(450f, 300f, 500)

        d.onDown(10f, 20f, 1_000)
        d.onUp(10f, 20f, 1_060)

        assertEquals(listOf(10f to 20f), taps)
    }

    @Test
    fun `move events of one finger are ignored during a scroll`() {
        val d = detector()
        d.twoFingers()

        d.onMove(900f, 900f)

        assertEquals(listOf("start(500.0,400.0)"), events)
        assertTrue(drags.isEmpty())
        assertTrue(d.isScrolling)
    }

    // ---------------------------------------------------- interaction avec les autres gestes

    @Test
    fun `a second finger during a drag releases the button and does not open a scroll`() {
        val d = detector()
        d.onDown(0f, 0f, 0); d.onMove(60f, 0f)
        assertEquals(listOf("start", "move"), drags)

        d.onTwoFingersDown(300f, 300f)
        d.onTwoFingersMove(300f, 200f)

        assertEquals(listOf("start", "move", "end"), drags)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a second finger after the finger moved beyond the slop is not a scroll`() {
        val d = TouchGestureDetector(8f, scroll = Scroll(listener)) { _, _ -> }
        d.onDown(0f, 0f, 0)
        d.onMove(60f, 0f) // sans écouteur de glissement : geste annulé

        d.onTwoFingersDown(300f, 300f)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a second finger after a long press is ignored`() {
        val fired = mutableListOf<String>()
        val scheduler = object : DelayScheduler {
            var task: Runnable? = null
            override fun postDelayed(task: Runnable, delayMs: Long) { this.task = task }
            override fun cancel(task: Runnable) { if (this.task === task) this.task = null }
        }
        val d = detector(longPress = LongPress(500, scheduler) { x, y -> fired += "long($x,$y)" })
        d.onDown(100f, 100f, 0)
        scheduler.task!!.run()

        d.onTwoFingersDown(300f, 300f)
        d.onTwoFingersMove(300f, 200f)

        assertEquals(listOf("long(100.0,100.0)"), fired)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `starting a scroll cancels the pending long press`() {
        val scheduler = object : DelayScheduler {
            var task: Runnable? = null
            override fun postDelayed(task: Runnable, delayMs: Long) { this.task = task }
            override fun cancel(task: Runnable) { if (this.task === task) this.task = null }
        }
        val fired = mutableListOf<String>()
        val d = detector(longPress = LongPress(500, scheduler) { x, y -> fired += "long($x,$y)" })
        d.onDown(100f, 100f, 0)
        val task = scheduler.task!!

        d.onTwoFingersDown(150f, 100f)
        task.run() // même un minuteur déjà parti ne peut rien déclencher

        assertTrue(fired.isEmpty())
        assertTrue(d.isScrolling)
    }

    @Test
    fun `without a scroll setting a second finger just cancels, as before`() {
        val d = detector(scroll = false)
        d.onDown(100f, 100f, 0)

        d.onTwoFingersDown(150f, 100f)
        d.onTwoFingersMove(150f, 20f)
        d.onUp(100f, 100f, 60)

        assertTrue(taps.isEmpty())
        assertTrue(events.isEmpty())
        assertFalse(d.isScrolling)
    }

    @Test
    fun `two independent scrolls do not share leftover distance`() {
        val d = detector(stepPx = 40f)
        d.twoFingers(); d.onTwoFingersMove(500f, 370f); d.onCancel() // 30 px, pas de cran
        events.clear()
        d.onUp(450f, 400f, 300)

        d.twoFingers(); d.onTwoFingersMove(500f, 370f) // 30 px seulement : pas de cran non plus

        assertEquals(listOf("start(500.0,400.0)"), events)
    }

    // ------------------------------------------------------------------ paramètres

    @Test
    fun `step is validated`() {
        assertThrows(IllegalArgumentException::class.java) { Scroll(listener, 7.9f) }
        assertThrows(IllegalArgumentException::class.java) { Scroll(listener, 400.1f) }
        assertThrows(IllegalArgumentException::class.java) { Scroll(listener, Float.NaN) }
        Scroll(listener, 8f)
        Scroll(listener, 400f)
        assertEquals(40f, Scroll.DEFAULT_STEP_PX)
    }

    @Test
    fun `a smaller step scrolls faster`() {
        val fast = mutableListOf<Int>()
        val slow = mutableListOf<Int>()
        for ((step, out) in listOf(20f to fast, 80f to slow)) {
            val d = TouchGestureDetector(8f, scroll = Scroll(object : ScrollListener {
                override fun onScrollStart(x: Float, y: Float) {}
                override fun onScroll(clicksX: Int, clicksY: Int) { out += clicksY }
            }, step)) { _, _ -> }
            d.onDown(450f, 400f, 0); d.onTwoFingersDown(500f, 400f)
            for (i in 1..40) d.onTwoFingersMove(500f, 400f - i * 10f) // 400 px au total
        }
        assertEquals(20, fast.sum())
        assertEquals(5, slow.sum())
    }

    /**
     * Propriété : quelle que soit la suite d'événements, un même geste produit au plus UNE famille d'issues (tap, appui
     * long, glissement ou défilement), jamais deux, et un défilement ne clique jamais.
     */
    @Test
    fun `random sequences with scrolling give at most one outcome family per gesture`() {
        val rnd = java.util.Random(2024)
        repeat(300) { seq ->
            var family = ""
            var violated: String? = null
            fun note(kind: String) { if (family.isNotEmpty() && family != kind) violated = "$family puis $kind (seq $seq)"; family = kind }
            val d = TouchGestureDetector(
                8f,
                dragListener = object : DragListener {
                    override fun onDragStart(x: Float, y: Float) { note("glissement") }
                    override fun onDragMove(x: Float, y: Float) {}
                    override fun onDragEnd(x: Float, y: Float) {}
                },
                scroll = Scroll(object : ScrollListener {
                    override fun onScrollStart(x: Float, y: Float) { note("défilement") }
                    override fun onScroll(clicksX: Int, clicksY: Int) { note("défilement"); assertTrue(clicksX == 0 || clicksY == 0) }
                })
            ) { _, _ -> note("tap") }
            var t = 0L
            repeat(250) {
                t += rnd.nextInt(300)
                val x = (rnd.nextInt(400) - 50).toFloat()
                val y = (rnd.nextInt(400) - 50).toFloat()
                when (rnd.nextInt(8)) {
                    0 -> { d.onDown(x, y, t); family = "" }
                    1 -> d.onMove(x, y)
                    2 -> d.onUp(x, y, t)
                    3 -> d.onTwoFingersDown(x, y)
                    4, 5 -> d.onTwoFingersMove(x, y)
                    6 -> d.onSecondFingerDown()
                    else -> d.onCancel()
                }
                assertEquals(null, violated)
            }
        }
    }
}
