package fr.webinfoconcept.secondscreen.rfb.framebuffer

import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.testutil.allocatedBytesOfCurrentThread
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopArgb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * Framebuffer.copyRect (SS-025) : copie interne avec chevauchement, comparée à un oracle indépendant
 * (« copier la source dans un tampon temporaire, puis la coller »), puis cas limites et sécurité.
 */
class FramebufferCopyRectTest {

    /** Référence : ce que doit produire un memmove, calculé avec un tampon temporaire, donc sans risque de corruption. */
    private fun referenceCopy(
        pixels: IntArray, width: Int, srcX: Int, srcY: Int, w: Int, h: Int, dstX: Int, dstY: Int
    ): IntArray {
        val out = pixels.copyOf()
        val temp = IntArray(w * h) { pixels[(srcY + it / w) * width + srcX + it % w] }
        for (row in 0 until h) for (col in 0 until w) out[(dstY + row) * width + dstX + col] = temp[row * w + col]
        return out
    }

    private fun filled(width: Int, height: Int): Framebuffer =
        Framebuffer(width, height).also { it.writeRect(0, 0, width, height, desktopArgb(width, height)) }

    private fun checkAgainstReference(width: Int, height: Int, srcX: Int, srcY: Int, w: Int, h: Int, dstX: Int, dstY: Int) {
        val fb = filled(width, height)
        val expected = referenceCopy(fb.pixels, width, srcX, srcY, w, h, dstX, dstY)

        fb.copyRect(srcX, srcY, w, h, dstX, dstY)

        assertArrayEquals("($srcX,$srcY ${w}x$h) -> ($dstX,$dstY) dans ${width}x$height", expected, fb.pixels)
    }

    // ------------------------------------------------- sans chevauchement

    @Test
    fun `copies to a disjoint destination and leaves the source intact`() {
        val fb = filled(20, 10)
        val before = fb.pixels.copyOf()

        fb.copyRect(0, 0, 5, 4, 10, 5)

        for (y in 0 until 4) for (x in 0 until 5) {
            assertEquals(before[y * 20 + x], fb.getPixel(10 + x, 5 + y)) // copié
            assertEquals(before[y * 20 + x], fb.getPixel(x, y))          // source intacte
        }
        assertArrayEquals(referenceCopy(before, 20, 0, 0, 5, 4, 10, 5), fb.pixels)
    }

    // ---------------------------------------------- avec chevauchement (SS-025)

    @Test
    fun `overlapping downwards keeps every source row`() {
        // Colonne de 8 pixels numérotés 0..7 : on copie les lignes 0..5 vers 2..7 (chevauchement de 4 lignes).
        val fb = Framebuffer(3, 8)
        for (y in 0 until 8) fb.fillRect(0, y, 3, 1, 0xFF000000.toInt() or y)

        fb.copyRect(0, 0, 3, 6, 0, 2)

        // Résultat attendu, vérifiable à l'œil : lignes 0,1 inchangées, puis 0,1,2,3,4,5.
        assertEquals(listOf(0, 1, 0, 1, 2, 3, 4, 5), (0 until 8).map { fb.getPixel(0, it) and 0xFF })
    }

    @Test
    fun `overlapping upwards keeps every source row`() {
        val fb = Framebuffer(3, 8)
        for (y in 0 until 8) fb.fillRect(0, y, 3, 1, 0xFF000000.toInt() or y)

        fb.copyRect(0, 2, 3, 6, 0, 0)

        // Lignes 2..7 remontent en 0..5 ; les lignes 6,7 gardent leur ancienne valeur.
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 6, 7), (0 until 8).map { fb.getPixel(0, it) and 0xFF })
    }

    @Test
    fun `a naive top to bottom copy would corrupt and this one does not`() {
        // Preuve que le cas testé est bien le piège : la copie naïve (toujours de haut en bas) donne un résultat faux.
        val fb = Framebuffer(3, 8)
        for (y in 0 until 8) fb.fillRect(0, y, 3, 1, 0xFF000000.toInt() or y)
        val naive = fb.pixels.copyOf()
        for (row in 0 until 6) System.arraycopy(naive, row * 3, naive, (row + 2) * 3, 3) // dst = src + 2 lignes

        fb.copyRect(0, 0, 3, 6, 0, 2)

        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0, 1), (0 until 8).map { naive[it * 3] and 0xFF }) // la corruption
        assertEquals(listOf(0, 1, 0, 1, 2, 3, 4, 5), (0 until 8).map { fb.getPixel(0, it) and 0xFF }) // le bon résultat
    }

    @Test
    fun `horizontal overlap on the same rows in both directions`() {
        checkAgainstReference(20, 10, 0, 2, 10, 3, 3, 2)  // vers la droite
        checkAgainstReference(20, 10, 3, 2, 10, 3, 0, 2)  // vers la gauche
        checkAgainstReference(20, 10, 0, 0, 19, 1, 1, 0)  // décalage d'un pixel, une seule ligne
        checkAgainstReference(20, 10, 1, 0, 19, 1, 0, 0)
    }

    @Test
    fun `diagonal overlap in the four directions`() {
        checkAgainstReference(20, 12, 2, 2, 8, 5, 4, 4)  // bas-droite
        checkAgainstReference(20, 12, 4, 4, 8, 5, 2, 2)  // haut-gauche
        checkAgainstReference(20, 12, 2, 4, 8, 5, 4, 2)  // haut-droite
        checkAgainstReference(20, 12, 4, 2, 8, 5, 2, 4)  // bas-gauche
    }

    @Test
    fun `terminal style scroll of one line up and down`() {
        val fb = Framebuffer(40, 25)
        for (y in 0 until 25) fb.fillRect(0, y, 40, 1, 0xFF000000.toInt() or (y * 10))

        fb.copyRect(0, 1, 40, 24, 0, 0) // défilement vers le haut d'une ligne

        for (y in 0 until 24) assertEquals("ligne $y", 0xFF000000.toInt() or ((y + 1) * 10), fb.getPixel(5, y))
        assertEquals(0xFF000000.toInt() or 240, fb.getPixel(5, 24)) // dernière ligne inchangée

        fb.copyRect(0, 0, 40, 24, 0, 1) // et vers le bas
        for (y in 1 until 25) assertEquals("ligne $y", 0xFF000000.toInt() or (y * 10), fb.getPixel(5, y))
    }

    @Test
    fun `whole screen shifted by one pixel`() {
        checkAgainstReference(30, 20, 0, 0, 29, 19, 1, 1)
        checkAgainstReference(30, 20, 1, 1, 29, 19, 0, 0)
    }

    @Test
    fun `copying a rectangle onto itself changes nothing`() {
        val fb = filled(20, 10)
        val before = fb.pixels.copyOf()

        fb.copyRect(3, 2, 8, 5, 3, 2)

        assertArrayEquals(before, fb.pixels)
    }

    @Test
    fun `single pixel single row and single column`() {
        checkAgainstReference(20, 10, 3, 3, 1, 1, 17, 8)
        checkAgainstReference(20, 10, 0, 4, 20, 1, 0, 6)   // toute une ligne
        checkAgainstReference(20, 10, 5, 0, 1, 10, 12, 0)  // toute une colonne
        checkAgainstReference(20, 10, 5, 0, 1, 10, 6, 0)   // colonne voisine
    }

    @Test
    fun `copies to and from the corners`() {
        checkAgainstReference(20, 10, 0, 0, 5, 4, 15, 6)
        checkAgainstReference(20, 10, 15, 6, 5, 4, 0, 0)
        checkAgainstReference(20, 10, 0, 6, 5, 4, 15, 0)
        checkAgainstReference(20, 10, 15, 0, 5, 4, 0, 6)
    }

    @Test
    fun `random rectangles agree with the reference in thousands of cases`() {
        val random = Random(20250920)
        repeat(6_000) {
            val width = 1 + random.nextInt(24)
            val height = 1 + random.nextInt(24)
            val w = random.nextInt(width + 1)   // 0 est valide (vide)
            val h = random.nextInt(height + 1)
            val srcX = random.nextInt(width - w + 1)
            val srcY = random.nextInt(height - h + 1)
            val dstX = random.nextInt(width - w + 1)
            val dstY = random.nextInt(height - h + 1)
            checkAgainstReference(width, height, srcX, srcY, w, h, dstX, dstY)
        }
    }

    @Test
    fun `most random cases really overlap so the trap is exercised`() {
        // Garde-fou du test précédent : sans cas qui se chevauchent il ne prouverait rien sur le piège.
        val random = Random(20250920)
        var overlapping = 0
        var total = 0
        repeat(6_000) {
            val width = 1 + random.nextInt(24)
            val height = 1 + random.nextInt(24)
            val w = random.nextInt(width + 1)
            val h = random.nextInt(height + 1)
            val srcX = random.nextInt(width - w + 1)
            val srcY = random.nextInt(height - h + 1)
            val dstX = random.nextInt(width - w + 1)
            val dstY = random.nextInt(height - h + 1)
            if (w > 0 && h > 0) {
                total++
                if (srcX < dstX + w && dstX < srcX + w && srcY < dstY + h && dstY < srcY + h) overlapping++
            }
        }
        assertTrue("$overlapping cas chevauchants sur $total", overlapping > total / 4)
    }

    // ---------------------------------------------------------------- vide

    @Test
    fun `an empty rectangle changes nothing`() {
        val fb = filled(20, 10)
        val before = fb.pixels.copyOf()

        fb.copyRect(5, 5, 0, 3, 8, 4)  // largeur nulle : la hauteur (3) doit quand même tenir : 4 + 3 <= 10
        fb.copyRect(5, 5, 3, 0, 8, 8)  // hauteur nulle : la largeur (3) doit tenir : 8 + 3 <= 20
        fb.copyRect(20, 10, 0, 0, 20, 10) // à la limite extérieure : valide car vide

        assertArrayEquals(before, fb.pixels)
    }

    // ------------------------------------------------------- limites (réseau)

    private fun assertOutOfBounds(x: Int, y: Int, w: Int, h: Int, action: () -> Unit) {
        val e = assertThrows("($x,$y) ${w}x$h", RfbProtocolException.RectangleOutOfBounds::class.java) { action() }
        assertEquals(listOf(x, y, w, h), listOf(e.x, e.y, e.width, e.height))
    }

    @Test
    fun `an out of bounds source is refused without writing anything`() {
        val fb = filled(20, 10)
        val before = fb.pixels.copyOf()

        assertOutOfBounds(15, 0, 6, 2) { fb.copyRect(15, 0, 6, 2, 0, 5) }        // déborde à droite
        assertOutOfBounds(0, 9, 2, 2) { fb.copyRect(0, 9, 2, 2, 5, 0) }          // déborde en bas
        assertOutOfBounds(-1, 0, 2, 2) { fb.copyRect(-1, 0, 2, 2, 5, 5) }
        assertOutOfBounds(65535, 65535, 3, 3) { fb.copyRect(65535, 65535, 3, 3, 0, 0) }

        assertArrayEquals("aucune écriture partielle", before, fb.pixels)
    }

    @Test
    fun `an out of bounds destination is refused without writing anything`() {
        val fb = filled(20, 10)
        val before = fb.pixels.copyOf()

        assertOutOfBounds(15, 0, 6, 2) { fb.copyRect(0, 0, 6, 2, 15, 0) }
        assertOutOfBounds(0, 9, 2, 2) { fb.copyRect(0, 0, 2, 2, 0, 9) }
        assertOutOfBounds(-3, 0, 2, 2) { fb.copyRect(0, 0, 2, 2, -3, 0) }

        assertArrayEquals(before, fb.pixels)
    }

    @Test
    fun `coordinates are immune to integer overflow`() {
        val fb = filled(20, 10)
        val before = fb.pixels.copyOf()

        assertOutOfBounds(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1) { fb.copyRect(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1, 0, 0) }
        assertOutOfBounds(Int.MAX_VALUE - 5, 0, 10, 10) { fb.copyRect(Int.MAX_VALUE - 5, 0, 10, 10, 0, 0) }
        assertOutOfBounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE) { fb.copyRect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE, 0, 0) }
        assertOutOfBounds(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1) { fb.copyRect(0, 0, 1, 1, Int.MAX_VALUE, Int.MAX_VALUE) }

        assertArrayEquals(before, fb.pixels)
    }

    // -------------------------------------------------------------- allocation

    @Test
    fun `copying allocates nothing and needs no temporary buffer`() {
        assumeTrue("mesure d'allocation indisponible sur cette JVM", allocatedBytesOfCurrentThread() != null)
        val fb = Framebuffer() // 1280x800
        fun oneRound() {
            fb.copyRect(0, 16, 1280, 784, 0, 0)   // défilement plein écran, sources et destination chevauchantes
            fb.copyRect(0, 0, 1280, 784, 0, 16)
            fb.copyRect(100, 100, 300, 200, 150, 120)
        }
        repeat(50) { oneRound() } // échauffement

        val before = allocatedBytesOfCurrentThread()!!
        repeat(200) { oneRound() }
        val allocated = allocatedBytesOfCurrentThread()!! - before

        // Un tampon temporaire de 3 Mio par copie serait ~1,8 Gio ici : la tolérance est ridicule à côté.
        assertTrue("$allocated octets alloués par copyRect", allocated < 8_192)
    }
}
