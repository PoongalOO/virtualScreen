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
}
