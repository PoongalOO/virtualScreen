package fr.webinfoconcept.secondscreen.render

import fr.webinfoconcept.secondscreen.rfb.testutil.allocatedBytesOfCurrentThread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/** DirtyRegion (SS-031) : boîte englobante, bornes, threads, absence d'allocation. */
class DirtyRegionTest {

    private fun take(region: DirtyRegion): IntArray? = IntArray(4).let { if (region.take(it)) it else null }

    @Test
    fun `starts empty and take reports nothing`() {
        val region = DirtyRegion(100, 50)

        assertTrue(region.isEmpty())
        val out = intArrayOf(9, 9, 9, 9)
        assertFalse(region.take(out))
        assertArrayEquals("out intact", intArrayOf(9, 9, 9, 9), out)
    }

    @Test
    fun `a single rectangle is returned as is with exclusive right and bottom`() {
        val region = DirtyRegion(100, 50)

        region.add(10, 5, 20, 8)

        assertFalse(region.isEmpty())
        assertArrayEquals(intArrayOf(10, 5, 30, 13), take(region))
    }

    @Test
    fun `take clears the region`() {
        val region = DirtyRegion(100, 50)
        region.add(0, 0, 5, 5)

        take(region)

        assertTrue(region.isEmpty())
        assertEquals(null, take(region))
    }

    @Test
    fun `several rectangles give their bounding box`() {
        val region = DirtyRegion(100, 50)

        region.add(10, 10, 5, 5)   // (10,10)-(15,15)
        region.add(60, 30, 10, 10) // (60,30)-(70,40)
        region.add(20, 12, 3, 3)   // inclus dans la boîte

        assertArrayEquals(intArrayOf(10, 10, 70, 40), take(region))
    }

    @Test
    fun `adding after a take starts a new region`() {
        val region = DirtyRegion(100, 50)
        region.add(0, 0, 100, 50)
        take(region)

        region.add(40, 20, 2, 2)

        assertArrayEquals(intArrayOf(40, 20, 42, 22), take(region))
    }

    @Test
    fun `empty and negative sized rectangles are ignored`() {
        val region = DirtyRegion(100, 50)

        region.add(10, 10, 0, 5)
        region.add(10, 10, 5, 0)
        region.add(10, 10, -3, 5)
        region.add(10, 10, 5, -3)

        assertTrue(region.isEmpty())
    }

    @Test
    fun `rectangles are clamped to the screen`() {
        val region = DirtyRegion(100, 50)

        region.add(90, 40, 50, 50) // dépasse à droite et en bas
        assertArrayEquals(intArrayOf(90, 40, 100, 50), take(region))

        region.add(-20, -10, 30, 20) // dépasse à gauche et en haut
        assertArrayEquals(intArrayOf(0, 0, 10, 10), take(region))
    }

    @Test
    fun `rectangles entirely outside the screen are ignored`() {
        val region = DirtyRegion(100, 50)

        region.add(100, 0, 10, 10)   // juste à droite
        region.add(0, 50, 10, 10)    // juste en dessous
        region.add(-30, 0, 30, 10)   // juste à gauche (finit en x = 0)
        region.add(0, -10, 10, 10)   // juste au-dessus

        assertTrue(region.isEmpty())
    }

    @Test
    fun `hostile values cannot overflow`() {
        val region = DirtyRegion(100, 50)

        region.add(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue("x + w déborde", region.isEmpty())

        region.add(1, 1, Int.MAX_VALUE, Int.MAX_VALUE)
        assertArrayEquals("borné à l'écran", intArrayOf(1, 1, 100, 50), take(region))

        region.add(Int.MIN_VALUE, Int.MIN_VALUE, 10, 10)
        assertTrue(region.isEmpty())

        region.add(Int.MIN_VALUE, 0, Int.MAX_VALUE, 5)
        assertTrue("finit avant l'écran", region.isEmpty())
    }

    @Test
    fun `addAll marks the whole screen`() {
        val region = DirtyRegion(1280, 800)

        region.addAll()

        assertArrayEquals(intArrayOf(0, 0, 1280, 800), take(region))
    }

    @Test
    fun `clear forgets the pending region`() {
        val region = DirtyRegion(100, 50)
        region.add(1, 1, 5, 5)

        region.clear()

        assertTrue(region.isEmpty())
    }

    @Test
    fun `the returned box is always inside the screen for random input`() {
        val random = Random(77)
        val region = DirtyRegion(64, 48)
        val out = IntArray(4)
        repeat(20_000) {
            region.add(random.nextInt(300) - 100, random.nextInt(300) - 100, random.nextInt(200) - 20, random.nextInt(200) - 20)
            if (random.nextInt(4) == 0 && region.take(out)) {
                assertTrue("gauche/haut : ${out.toList()}", out[0] >= 0 && out[1] >= 0)
                assertTrue("droite/bas : ${out.toList()}", out[2] <= 64 && out[3] <= 48)
                assertTrue("boîte non vide : ${out.toList()}", out[0] < out[2] && out[1] < out[3])
            }
        }
    }

    @Test(timeout = 20_000)
    fun `concurrent writers and a reader never lose a rectangle`() {
        // Le décodeur (plusieurs threads ici, pour être plus sévère) ajoute ; le rendu prend. Tout rectangle ajouté
        // doit se retrouver dans l'union des boîtes prises : sinon un pixel modifié ne serait jamais dessiné.
        val region = DirtyRegion(2_000, 2_000)
        val writers = 4
        val perWriter = 5_000
        val taken = java.util.Collections.synchronizedList(mutableListOf<IntArray>())
        val done = java.util.concurrent.atomic.AtomicInteger()

        val reader = Thread {
            val out = IntArray(4)
            while (done.get() < writers || !region.isEmpty()) {
                if (region.take(out)) taken += out.copyOf() else Thread.yield()
            }
        }
        val threads = (0 until writers).map { w ->
            Thread {
                for (i in 0 until perWriter) region.add(w * 400 + i % 300, (i * 7) % 1_900, 3, 3)
                done.incrementAndGet()
            }
        }
        reader.start(); threads.forEach { it.start() }
        threads.forEach { it.join() }
        reader.join()

        for (w in 0 until writers) for (i in 0 until perWriter) {
            val x = w * 400 + i % 300
            val y = (i * 7) % 1_900
            val covered = taken.any { it[0] <= x && it[1] <= y && it[2] >= x + 3 && it[3] >= y + 3 }
            assertTrue("rectangle ($x,$y) perdu", covered)
        }
    }

    @Test
    fun `add and take allocate nothing`() {
        assumeTrue("mesure d'allocation indisponible", allocatedBytesOfCurrentThread() != null)
        val region = DirtyRegion(1280, 800)
        val out = IntArray(4)
        repeat(5_000) { region.add(it % 1000, it % 700, 16, 16); region.take(out) } // échauffement

        val before = allocatedBytesOfCurrentThread()!!
        repeat(20_000) { region.add(it % 1000, it % 700, 16, 16); region.add(5, 5, 100, 100); region.take(out) }
        val allocated = allocatedBytesOfCurrentThread()!! - before

        assertTrue("$allocated octets alloués", allocated < 8_192)
    }

    // ================================================== rectangles distincts (SS-062)

    private fun takeRects(region: DirtyRegion): List<IntArray> {
        val bounds = IntArray(4)
        val rects = IntArray(4 * DirtyRegion.MAX_RECTS)
        val n = region.take(bounds, rects)
        return (0 until n).map { rects.copyOfRange(4 * it, 4 * it + 4) }
    }

    private fun area(r: IntArray) = (r[2] - r[0]).toLong() * (r[3] - r[1])

    @Test
    fun `two small distant zones stay two rectangles - the copy is not the bounding box`() {
        val region = DirtyRegion(1280, 800)
        region.add(0, 0, 100, 20)
        region.add(1180, 780, 100, 20)

        val bounds = IntArray(4)
        val rects = IntArray(4 * DirtyRegion.MAX_RECTS)
        val n = region.take(bounds, rects)

        assertEquals(2, n)
        assertEquals(listOf(0, 0, 1280, 800), bounds.toList()) // la boîte reste entière pour le dessin
        val copied = (0 until n).sumOf { area(rects.copyOfRange(4 * it, 4 * it + 4)) }
        assertEquals("seulement 4 000 pixels à copier au lieu de 1 024 000", 4_000L, copied)
    }

    @Test
    fun `overlapping and touching rectangles merge into one`() {
        val region = DirtyRegion(200, 200)
        region.add(10, 10, 50, 50)
        region.add(40, 40, 50, 50)   // recouvre
        region.add(90, 10, 30, 30)   // touche le bord droit du premier (x = 60..90 : vide) puis le second

        val rects = takeRects(region)

        assertEquals(1, rects.size)
    }

    @Test
    fun `adjacent text lines merge, distant ones do not`() {
        val region = DirtyRegion(1280, 800)
        region.add(0, 100, 600, 16)
        region.add(0, 116, 600, 16)   // ligne suivante, bord contre bord
        region.add(0, 600, 600, 16)   // très loin en dessous

        val rects = takeRects(region)

        assertEquals(2, rects.size)
        assertTrue(rects.any { it.toList() == listOf(0, 100, 600, 132) })
    }

    @Test
    fun `a merge that would waste more than twice the area is refused`() {
        val region = DirtyRegion(1000, 1000)
        region.add(0, 0, 100, 100)
        region.add(300, 0, 100, 100) // la boîte commune fait 400x100 = 40 000 pour 20 000 : 2x, fusionne
        region.add(0, 500, 100, 100) // 100x600 = 60 000 pour 20 000 : refusé pour le premier

        val rects = takeRects(region)

        assertTrue("au moins deux rectangles : $rects", rects.size >= 2)
    }

    @Test
    fun `over the capacity the closest rectangles are merged, never more than the maximum`() {
        val region = DirtyRegion(2000, 2000)
        repeat(DirtyRegion.MAX_RECTS + 5) { region.add(it * 190, (it % 2) * 1000, 20, 20) } // toutes éloignées

        val rects = takeRects(region)

        assertTrue("${rects.size} rectangles", rects.size <= DirtyRegion.MAX_RECTS)
        assertTrue(rects.isNotEmpty())
    }

    @Test
    fun `take with rectangles clears the region and reports nothing the second time`() {
        val region = DirtyRegion(100, 100)
        region.add(5, 5, 10, 10)

        assertEquals(1, takeRects(region).size)

        assertTrue(region.isEmpty())
        assertEquals(0, takeRects(region).size)
    }

    @Test
    fun `nothing is written when there is nothing to take`() {
        val bounds = intArrayOf(-7, -7, -7, -7)
        val rects = IntArray(4 * DirtyRegion.MAX_RECTS) { -7 }

        assertEquals(0, DirtyRegion(100, 100).take(bounds, rects))

        assertEquals(listOf(-7, -7, -7, -7), bounds.toList())
        assertTrue(rects.all { it == -7 })
    }

    @Test
    fun `the two ways of taking give the same bounding box`() {
        val a = DirtyRegion(300, 300); val b = DirtyRegion(300, 300)
        for (r in listOf(intArrayOf(10, 20, 30, 40), intArrayOf(200, 250, 50, 40), intArrayOf(-5, -5, 20, 20))) {
            a.add(r[0], r[1], r[2], r[3]); b.add(r[0], r[1], r[2], r[3])
        }
        val one = IntArray(4); a.take(one)
        val two = IntArray(4); b.take(two, IntArray(4 * DirtyRegion.MAX_RECTS))

        assertEquals(one.toList(), two.toList())
    }

    @Test
    fun `rectangles are clamped to the screen and a hostile size does not overflow`() {
        val region = DirtyRegion(100, 100)
        region.add(-50, -50, 80, 80)
        region.add(90, 90, Int.MAX_VALUE, Int.MAX_VALUE)
        region.add(1000, 1000, 5, 5)            // hors écran : ignoré
        region.add(10, 10, 0, 5)                // vide : ignoré

        for (r in takeRects(region)) {
            assertTrue(r.toList().toString(), r[0] >= 0 && r[1] >= 0 && r[2] <= 100 && r[3] <= 100 && r[2] > r[0] && r[3] > r[1])
        }
    }

    /**
     * Propriété : quelle que soit la suite de rectangles, (1) chaque pixel ajouté est dans au moins un rectangle rendu,
     * (2) il y a au plus MAX_RECTS rectangles, (3) la surface à copier n'est jamais supérieure à celle de la boîte englobante,
     * (4) les rectangles rendus sont dans la boîte englobante.
     */
    @Test
    fun `random additions are always covered, bounded and never worse than the bounding box`() {
        val rnd = java.util.Random(62)
        repeat(500) { seq ->
            val w = 50 + rnd.nextInt(300); val h = 50 + rnd.nextInt(200)
            val region = DirtyRegion(w, h)
            val covered = Array(h) { BooleanArray(w) }
            repeat(1 + rnd.nextInt(25)) {
                val x = rnd.nextInt(w + 20) - 10; val y = rnd.nextInt(h + 20) - 10
                val rw = rnd.nextInt(60); val rh = rnd.nextInt(40)
                region.add(x, y, rw, rh)
                for (yy in maxOf(0, y) until minOf(h, y + rh)) for (xx in maxOf(0, x) until minOf(w, x + rw)) covered[yy][xx] = true
            }
            val bounds = IntArray(4)
            val rects = IntArray(4 * DirtyRegion.MAX_RECTS)
            val n = region.take(bounds, rects)
            if (covered.all { row -> row.none { it } }) { assertEquals(0, n); return@repeat }

            assertTrue("au plus ${DirtyRegion.MAX_RECTS} rectangles (seq $seq) : $n", n in 1..DirtyRegion.MAX_RECTS)
            val list = (0 until n).map { rects.copyOfRange(4 * it, 4 * it + 4) }
            for (yy in 0 until h) for (xx in 0 until w) if (covered[yy][xx]) {
                assertTrue("pixel ($xx,$yy) non couvert (seq $seq)", list.any { xx >= it[0] && xx < it[2] && yy >= it[1] && yy < it[3] })
            }
            val boxArea = (bounds[2] - bounds[0]).toLong() * (bounds[3] - bounds[1])
            assertTrue("copie > boîte (seq $seq)", list.sumOf { area(it) } <= boxArea)
            for (r in list) assertTrue("rectangle hors boîte (seq $seq)", r[0] >= bounds[0] && r[1] >= bounds[1] && r[2] <= bounds[2] && r[3] <= bounds[3])
        }
    }
}
