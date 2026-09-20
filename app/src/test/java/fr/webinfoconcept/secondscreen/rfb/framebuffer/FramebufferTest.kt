package fr.webinfoconcept.secondscreen.rfb.framebuffer

import fr.webinfoconcept.secondscreen.rfb.protocol.InitExchange
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Framebuffer : allocation, limites, mises à jour rectangulaires (JVM pur, aucun appareil). */
class FramebufferTest {

    private val black = Framebuffer.OPAQUE_BLACK
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()

    /** Valeur reconnaissable pour chaque index de tableau source. */
    private fun pattern(i: Int): Int = 0xFF000000.toInt() or (i and 0xFFFFFF)

    private fun assertOutOfBounds(x: Int, y: Int, w: Int, h: Int, action: () -> Unit) {
        val e = assertThrows("($x,$y) ${w}x$h", RfbProtocolException.RectangleOutOfBounds::class.java) { action() }
        assertEquals(x, e.x)
        assertEquals(y, e.y)
        assertEquals(w, e.width)
        assertEquals(h, e.height)
    }

    // ----------------------------------------------------------- allocation

    @Test
    fun `default framebuffer is 1280x800 filled with opaque black`() {
        val fb = Framebuffer()

        assertEquals(1280, fb.width)
        assertEquals(800, fb.height)
        assertEquals(1280 * 800, fb.pixels.size)
        assertTrue(fb.pixels.all { it == black })
    }

    @Test
    fun `nominal constants match the target screen`() {
        assertEquals(1280, Framebuffer.NOMINAL_WIDTH)
        assertEquals(800, Framebuffer.NOMINAL_HEIGHT)
        assertEquals(0xFF000000.toInt(), Framebuffer.OPAQUE_BLACK)
        assertEquals(255, Framebuffer.OPAQUE_BLACK ushr 24) // alpha opaque
    }

    @Test
    fun `accepts sizes up to the limits`() {
        for ((w, h) in listOf(1 to 1, 1280 to 800, 1920 to 1200, 1200 to 1920, 4096 to 562)) {
            val fb = Framebuffer(w, h)
            assertEquals(w * h, fb.pixels.size)
        }
    }

    @Test
    fun `rejects invalid sizes`() {
        val invalid = listOf(
            0 to 800, 1280 to 0, 0 to 0, -1 to 800, 1280 to -1, Int.MIN_VALUE to 1,
            4097 to 100, 100 to 4097, Int.MAX_VALUE to Int.MAX_VALUE, // dépasserait Int si multiplié tel quel
            1921 to 1200, 4096 to 563, 2560 to 1440
        )
        for ((w, h) in invalid) {
            assertThrows("${w}x$h", IllegalArgumentException::class.java) { Framebuffer(w, h) }
        }
    }

    @Test
    fun `limits are shared with the ServerInit validation`() {
        assertEquals(InitExchange.MAX_DIMENSION, Framebuffer.MAX_DIMENSION)
        assertEquals(InitExchange.MAX_PIXELS, Framebuffer.MAX_PIXELS)
        assertTrue(1280L * 800L <= Framebuffer.MAX_PIXELS)
    }

    @Test
    fun `each framebuffer owns its pixels`() {
        val a = Framebuffer(16, 16)
        val b = Framebuffer(16, 16)

        a.fillRect(0, 0, 16, 16, red)

        assertNotSame(a.pixels, b.pixels)
        assertTrue(b.pixels.all { it == black })
    }

    @Test
    fun `pixel array is never reallocated by updates`() {
        val fb = Framebuffer(64, 48)
        val array = fb.pixels
        val src = IntArray(64 * 48) { pattern(it) }

        repeat(100) {
            fb.fillRect(3, 4, 20, 10, red)
            fb.writeRect(0, 0, 64, 48, src)
            fb.clear()
        }

        assertSame(array, fb.pixels)
        assertEquals(64 * 48, fb.pixels.size)
    }

    // ------------------------------------------------------------- contains

    @Test
    fun `contains accepts rectangles inside the screen`() {
        val fb = Framebuffer()
        assertTrue(fb.contains(0, 0, 1280, 800))     // plein écran
        assertTrue(fb.contains(0, 0, 1, 1))
        assertTrue(fb.contains(1279, 799, 1, 1))     // dernier pixel
        assertTrue(fb.contains(100, 200, 300, 400))
        assertTrue(fb.contains(0, 0, 0, 0))          // vide
        assertTrue(fb.contains(1280, 800, 0, 0))     // vide, sur le coin extérieur
        assertTrue(fb.contains(1280, 0, 0, 800))     // vide, sur le bord
    }

    @Test
    fun `contains rejects rectangles outside the screen`() {
        val fb = Framebuffer()
        assertFalse(fb.contains(0, 0, 1281, 800))
        assertFalse(fb.contains(0, 0, 1280, 801))
        assertFalse(fb.contains(1, 0, 1280, 800))
        assertFalse(fb.contains(0, 1, 1280, 800))
        assertFalse(fb.contains(1279, 799, 2, 1))
        assertFalse(fb.contains(1279, 799, 1, 2))
        assertFalse(fb.contains(1280, 0, 1, 1))      // juste à droite
        assertFalse(fb.contains(0, 800, 1, 1))       // juste en dessous
        assertFalse(fb.contains(1281, 0, 0, 0))      // origine hors écran même si vide
        assertFalse(fb.contains(0, 801, 0, 0))
    }

    @Test
    fun `contains rejects negative values`() {
        val fb = Framebuffer()
        assertFalse(fb.contains(-1, 0, 10, 10))
        assertFalse(fb.contains(0, -1, 10, 10))
        assertFalse(fb.contains(0, 0, -1, 10))
        assertFalse(fb.contains(0, 0, 10, -1))
        assertFalse(fb.contains(-5, -5, 10, 10))
        assertFalse(fb.contains(-1, 0, 0, 0))
    }

    @Test
    fun `contains is immune to integer overflow`() {
        val fb = Framebuffer()
        // x + w déborde vers un petit nombre négatif ou positif si additionné : doit rester refusé.
        assertFalse(fb.contains(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1))
        assertFalse(fb.contains(0, Int.MAX_VALUE, 1, Int.MAX_VALUE))
        assertFalse(fb.contains(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(fb.contains(Int.MAX_VALUE - 5, 0, 10, 10))   // x + w = Int.MIN_VALUE + 4
        assertFalse(fb.contains(1, 0, Int.MAX_VALUE, 1))          // 1 + MAX déborde
        assertFalse(fb.contains(0, 0, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(fb.contains(0x7FFF_FFFF - 1279, 0, 1280, 1))
        assertFalse(fb.contains(Int.MIN_VALUE, 0, 1, 1))
        assertFalse(fb.contains(0, 0, Int.MIN_VALUE, 1))
        assertFalse(fb.contains(65535, 65535, 65535, 65535))
    }

    // ------------------------------------------------------------- fillRect

    @Test
    fun `fillRect paints exactly the rectangle`() {
        val fb = Framebuffer(20, 10)

        fb.fillRect(3, 2, 5, 4, red)

        for (y in 0 until 10) {
            for (x in 0 until 20) {
                val inside = x in 3..7 && y in 2..5
                assertEquals("($x,$y)", if (inside) red else black, fb.getPixel(x, y))
            }
        }
    }

    @Test
    fun `fillRect handles corners and full screen`() {
        val fb = Framebuffer(20, 10)

        fb.fillRect(0, 0, 1, 1, red)
        fb.fillRect(19, 9, 1, 1, green)
        assertEquals(red, fb.getPixel(0, 0))
        assertEquals(green, fb.getPixel(19, 9))
        assertEquals(2, fb.pixels.count { it != black })

        fb.fillRect(0, 0, 20, 10, green)
        assertTrue(fb.pixels.all { it == green })
    }

    @Test
    fun `fillRect with an empty rectangle changes nothing`() {
        val fb = Framebuffer(20, 10)
        val before = fb.pixels.copyOf()

        fb.fillRect(5, 5, 0, 3, red)
        fb.fillRect(5, 5, 3, 0, red)
        fb.fillRect(20, 10, 0, 0, red)

        assertArrayEquals(before, fb.pixels)
    }

    @Test
    fun `fillRect out of bounds throws and writes nothing`() {
        val fb = Framebuffer(20, 10)
        val before = fb.pixels.copyOf()

        assertOutOfBounds(15, 0, 6, 1) { fb.fillRect(15, 0, 6, 1, red) }        // déborde à droite
        assertOutOfBounds(0, 8, 1, 3) { fb.fillRect(0, 8, 1, 3, red) }          // déborde en bas
        assertOutOfBounds(-1, 0, 5, 5) { fb.fillRect(-1, 0, 5, 5, red) }
        assertOutOfBounds(0, 0, -1, 5) { fb.fillRect(0, 0, -1, 5, red) }
        assertOutOfBounds(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1) { fb.fillRect(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1, red) }

        assertArrayEquals("aucune écriture partielle", before, fb.pixels)
    }

    // ------------------------------------------------------------ writeRect

    @Test
    fun `writeRect copies pixels row by row at the right place`() {
        val fb = Framebuffer(20, 10)
        val src = IntArray(4 * 3) { pattern(it + 1) } // 4 de large, 3 de haut, contigu

        fb.writeRect(5, 6, 4, 3, src)

        for (y in 0 until 10) {
            for (x in 0 until 20) {
                val inside = x in 5..8 && y in 6..8
                val expected = if (inside) pattern((y - 6) * 4 + (x - 5) + 1) else black
                assertEquals("($x,$y)", expected, fb.getPixel(x, y))
            }
        }
    }

    @Test
    fun `writeRect reads a sub area of a wider source using offset and stride`() {
        val fb = Framebuffer(20, 10)
        // Source de 8 de large ; on ne prend que le bloc 3x2 qui commence en (2, 1).
        val srcWidth = 8
        val src = IntArray(srcWidth * 4) { pattern(it) }

        fb.writeRect(10, 3, 3, 2, src, srcOffset = 1 * srcWidth + 2, srcStride = srcWidth)

        assertEquals(pattern(1 * 8 + 2), fb.getPixel(10, 3))
        assertEquals(pattern(1 * 8 + 4), fb.getPixel(12, 3))
        assertEquals(pattern(2 * 8 + 2), fb.getPixel(10, 4))
        assertEquals(pattern(2 * 8 + 4), fb.getPixel(12, 4))
        assertEquals(black, fb.getPixel(13, 3)) // rien au-delà de la largeur demandée
        assertEquals(6, fb.pixels.count { it != black })
    }

    @Test
    fun `writeRect full screen and single pixel`() {
        val fb = Framebuffer(20, 10)
        val full = IntArray(200) { pattern(it) }

        fb.writeRect(0, 0, 20, 10, full)
        assertArrayEquals(full, fb.pixels)

        fb.writeRect(19, 9, 1, 1, intArrayOf(red))
        assertEquals(red, fb.getPixel(19, 9))
    }

    @Test
    fun `writeRect with an empty rectangle changes nothing and needs no source`() {
        val fb = Framebuffer(20, 10)
        val before = fb.pixels.copyOf()

        fb.writeRect(5, 5, 0, 3, IntArray(0))
        fb.writeRect(5, 5, 3, 0, IntArray(0))

        assertArrayEquals(before, fb.pixels)
    }

    @Test
    fun `writeRect out of bounds throws and writes nothing`() {
        val fb = Framebuffer(20, 10)
        val before = fb.pixels.copyOf()
        val src = IntArray(400) { pattern(it) }

        assertOutOfBounds(15, 0, 6, 2) { fb.writeRect(15, 0, 6, 2, src) }
        assertOutOfBounds(0, 9, 2, 2) { fb.writeRect(0, 9, 2, 2, src) }
        assertOutOfBounds(-1, 0, 2, 2) { fb.writeRect(-1, 0, 2, 2, src) }
        assertOutOfBounds(0, 0, 21, 1) { fb.writeRect(0, 0, 21, 1, src) }
        assertOutOfBounds(Int.MAX_VALUE, 0, 10, 10) { fb.writeRect(Int.MAX_VALUE, 0, 10, 10, src) }

        assertArrayEquals("aucune écriture partielle", before, fb.pixels)
    }

    @Test
    fun `writeRect rejects a source that is too small without writing`() {
        val fb = Framebuffer(20, 10)
        val before = fb.pixels.copyOf()

        assertThrows(IllegalArgumentException::class.java) { fb.writeRect(0, 0, 4, 3, IntArray(11)) }  // 12 requis
        assertThrows(IllegalArgumentException::class.java) { fb.writeRect(0, 0, 4, 3, IntArray(12), srcOffset = 1) }
        assertArrayEquals(before, fb.pixels)
    }

    @Test
    fun `writeRect source size boundary is exact`() {
        val fb = Framebuffer(20, 10)

        // srcStride = 8, h = 3, w = 4 : dernier index lu = 0 + 2*8 + 4 = 20 -> 20 éléments suffisent.
        fb.writeRect(0, 0, 4, 3, IntArray(20) { pattern(it) }, srcOffset = 0, srcStride = 8)
        assertEquals(pattern(16), fb.getPixel(0, 2))

        assertThrows(IllegalArgumentException::class.java) {
            fb.writeRect(0, 0, 4, 3, IntArray(19), srcOffset = 0, srcStride = 8)
        }
    }

    @Test
    fun `writeRect rejects bad offset or stride`() {
        val fb = Framebuffer(20, 10)
        val src = IntArray(100)

        assertThrows(IllegalArgumentException::class.java) { fb.writeRect(0, 0, 4, 3, src, srcOffset = -1) }
        assertThrows(IllegalArgumentException::class.java) { fb.writeRect(0, 0, 4, 3, src, srcStride = 3) } // < w
        assertThrows(IllegalArgumentException::class.java) { fb.writeRect(0, 0, 4, 3, src, srcStride = -4) }
    }

    @Test
    fun `writeRect source bounds are immune to integer overflow`() {
        val fb = Framebuffer(20, 10)
        val before = fb.pixels.copyOf()
        val src = IntArray(100)

        // (h - 1) * srcStride déborde un Int : ne doit pas passer pour « petit » et lire hors tableau.
        assertThrows(IllegalArgumentException::class.java) { fb.writeRect(0, 0, 4, 3, src, srcStride = Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { fb.writeRect(0, 0, 4, 3, src, srcOffset = Int.MAX_VALUE) }
        // Le piège : 9 * 0x1C71C71D = 2^32 + 5. Calculé en Int, le produit vaut 5 (« petit »), le dernier
        // index lu paraîtrait valide (1 + 5 + 4 = 10 <= 100) alors que la vraie position est ~4,3 milliards.
        val trapStride = intArrayOf(0x1C71C71D)[0] // non constant : le compilateur avertirait du débordement
        assertEquals(5, 9 * trapStride) // vérifie le piège lui-même
        assertThrows(IllegalArgumentException::class.java) {
            fb.writeRect(0, 0, 4, 10, src, srcOffset = 1, srcStride = trapStride)
        }
        assertArrayEquals(before, fb.pixels)
    }

    // ------------------------------------------------------- getPixel / clear

    @Test
    fun `getPixel reads back what was written and checks bounds`() {
        val fb = Framebuffer(20, 10)
        fb.fillRect(7, 3, 1, 1, red)

        assertEquals(red, fb.getPixel(7, 3))
        assertEquals(black, fb.getPixel(0, 0))
        assertOutOfBounds(20, 0, 1, 1) { fb.getPixel(20, 0) }
        assertOutOfBounds(0, 10, 1, 1) { fb.getPixel(0, 10) }
        assertOutOfBounds(-1, 0, 1, 1) { fb.getPixel(-1, 0) }
    }

    @Test
    fun `clear resets every pixel`() {
        val fb = Framebuffer(20, 10)
        fb.fillRect(0, 0, 20, 10, red)

        fb.clear()
        assertTrue(fb.pixels.all { it == black })

        fb.clear(green)
        assertTrue(fb.pixels.all { it == green })
    }

    @Test
    fun `pixel layout is row major`() {
        val fb = Framebuffer(20, 10)
        fb.fillRect(3, 2, 1, 1, red)

        assertEquals(red, fb.pixels[2 * 20 + 3])
    }

    @Test
    fun `error message contains only numbers`() {
        val e = assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) {
            Framebuffer(20, 10).fillRect(19, 0, 9, 1, red)
        }
        assertEquals("Rectangle hors de l'écran distant : (19,0) 9x1", e.message)
    }

    // ------------------------------------------- allocation par mise à jour

    /**
     * Octets alloués jusqu'ici par le thread courant, ou `null` si la JVM ne sait pas les mesurer.
     * Par réflexion : les tests unitaires AGP compilent contre android.jar, qui ne contient pas
     * `java.lang.management`, alors que la JVM qui les exécute l'a.
     */
    private fun allocatedBytes(): Long? = try {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val sunBean = Class.forName("com.sun.management.ThreadMXBean")
        if (!sunBean.isInstance(bean) || !(sunBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)) {
            null
        } else {
            sunBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                .invoke(bean, Thread.currentThread().id) as Long
        }
    } catch (e: ReflectiveOperationException) {
        null
    }

    @Test
    fun `updates allocate nothing`() {
        assumeTrue("mesure d'allocation indisponible sur cette JVM", allocatedBytes() != null)

        val fb = Framebuffer()
        val src = IntArray(300 * 200) { pattern(it) }
        fun oneRound() {
            fb.fillRect(10, 10, 300, 200, red)
            fb.writeRect(400, 300, 300, 200, src)
            fb.writeRect(0, 0, 100, 50, src, srcOffset = 20, srcStride = 300) // sous-zone d'un tableau plus large
            fb.getPixel(5, 5)
        }
        repeat(3_000) { oneRound() } // échauffement JIT

        val before = allocatedBytes()!!
        repeat(3_000) { oneRound() }
        val allocated = allocatedBytes()!! - before

        // 12 000 appels. Tolérance pour le bruit de la mesure elle-même (réflexion), très en dessous
        // du moindre objet alloué par appel (12 000 x 16 octets = 192 Kio).
        assertTrue("$allocated octets alloués par les mises à jour", allocated < 8_192)
    }

    @Test
    fun `allocation measurement can detect allocations`() {
        // Témoin : sans lui, le test précédent pourrait passer sans rien mesurer.
        assumeTrue("mesure d'allocation indisponible sur cette JVM", allocatedBytes() != null)

        val before = allocatedBytes()!!
        var keep = 0
        repeat(200) { keep += IntArray(1_000).size } // 200 x ~4 Kio
        val allocated = allocatedBytes()!! - before

        assertTrue("la mesure n'a rien vu : $allocated octets ($keep)", allocated > 400_000)
    }
}
