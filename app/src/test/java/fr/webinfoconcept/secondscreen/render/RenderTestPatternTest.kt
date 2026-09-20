package fr.webinfoconcept.secondscreen.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motif de test d'affichage (SS-030) : il doit permettre de détecter toute erreur de rendu. */
class RenderTestPatternTest {

    private val w = 1280
    private val h = 800
    private fun p(x: Int, y: Int) = RenderTestPattern.pixel(x, y, w, h)

    @Test
    fun `a one pixel white frame surrounds the pattern`() {
        val white = 0xFFFFFFFF.toInt()
        for (x in listOf(0, 1, 640, 1279)) {
            assertEquals("haut x=$x", white, p(x, 0))
            assertEquals("bas x=$x", white, p(x, h - 1))
        }
        for (y in listOf(0, 1, 400, 799)) {
            assertEquals("gauche y=$y", white, p(0, y))
            assertEquals("droite y=$y", white, p(w - 1, y))
        }
        assertNotEquals("le pixel juste à l'intérieur n'est plus blanc", white, p(1, 400))
    }

    @Test
    fun `the four corners identify the orientation`() {
        assertEquals(0xFFFF0000.toInt(), p(1, 1))            // haut-gauche : rouge
        assertEquals(0xFF00FF00.toInt(), p(w - 2, 1))        // haut-droite : vert
        assertEquals(0xFF0000FF.toInt(), p(1, h - 2))        // bas-gauche : bleu
        assertEquals(0xFFFFFF00.toInt(), p(w - 2, h - 2))    // bas-droite : jaune
        assertEquals(0xFFFF0000.toInt(), p(31, 31))          // limite du carré de 32
        assertNotEquals(0xFFFF0000.toInt(), p(32, 32))       // au-delà : dégradé
    }

    @Test
    fun `the centre is a gradient where every channel varies differently`() {
        // rouge croît avec x, vert avec y, bleu = x xor y
        assertEquals(0xFF000000.toInt() or (100 * 255 / (w - 1) shl 16) or (200 * 255 / (h - 1) shl 8) or (100 xor 200),
            p(100, 200))
        assertTrue("le rouge croît avec x", (p(600, 400) shr 16 and 0xFF) > (p(100, 400) shr 16 and 0xFF))
        assertTrue("le vert croît avec y", (p(600, 600) shr 8 and 0xFF) > (p(600, 100) shr 8 and 0xFF))
        assertNotEquals("x et y ne sont pas interchangeables", p(100, 200), p(200, 100))
    }

    @Test
    fun `every pixel is opaque`() {
        for (y in 0 until h step 7) for (x in 0 until w step 5) assertEquals(0xFF, p(x, y) ushr 24)
    }

    @Test
    fun `the pattern is deterministic`() {
        assertEquals(p(333, 444), p(333, 444))
        assertEquals(RenderTestPattern.pixel(10, 10, 100, 50), RenderTestPattern.pixel(10, 10, 100, 50))
    }

    @Test
    fun `create fills a framebuffer with exactly the pattern`() {
        val fb = RenderTestPattern.create(200, 120)

        assertEquals(200, fb.width)
        assertEquals(120, fb.height)
        for (y in 0 until 120) for (x in 0 until 200) {
            assertEquals("($x,$y)", RenderTestPattern.pixel(x, y, 200, 120), fb.getPixel(x, y))
        }
    }

    @Test
    fun `the default framebuffer is the nominal 1280x800`() {
        val fb = RenderTestPattern.create()

        assertEquals(1280, fb.width)
        assertEquals(800, fb.height)
        assertEquals(p(640, 400), fb.getPixel(640, 400))
    }

    @Test
    fun `out of range coordinates and tiny sizes are refused`() {
        assertThrows(IllegalArgumentException::class.java) { RenderTestPattern.pixel(-1, 0, 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { RenderTestPattern.pixel(0, 100, 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { RenderTestPattern.pixel(0, 0, 2, 100) }
    }
}
