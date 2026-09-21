package fr.webinfoconcept.secondscreen.rfb.robustness

import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessageReader
import fr.webinfoconcept.secondscreen.rfb.testutil.memoryClient
import fr.webinfoconcept.secondscreen.rfb.testutil.rectHeader
import fr.webinfoconcept.secondscreen.rfb.testutil.updateHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SS-070 : la géométrie d'un rectangle vient du réseau (quatre U16). Pour **toutes** les combinaisons de valeurs limites, avec
 * chaque encodage : un rectangle qui tient dans l'écran est dessiné exactement à sa place et nulle part ailleurs, un rectangle
 * qui n'y tient pas donne `RectangleOutOfBounds` et **ne modifie aucun pixel**. L'oracle (« tient-il ? ») est écrit à part,
 * en `Long`, indépendamment de `Framebuffer.contains`.
 */
class RectangleGeometryTest {

    private val fbW = 64
    private val fbH = 48

    // Autour de chaque frontière : 0, 1, la taille d'une tuile Hextile (16/17), l'écran (63/64/65), le budget mémoire (4096),
    // le bit de signe d'un U16 (32767/32768) et le maximum (65535).
    private val values = intArrayOf(0, 1, 15, 16, 17, 47, 48, 63, 64, 65, 4096, 32768, 65535)

    private fun fits(x: Int, y: Int, w: Int, h: Int) = x.toLong() + w <= fbW && y.toLong() + h <= fbH

    private fun payload(encoding: Int, w: Int, h: Int): ByteArray = when (encoding) {
        RAW -> ByteArray(w * h * 4)                                  // pixels tous à 0 : noir opaque après conversion
        HEXTILE -> ByteArray(((w + 15) / 16) * ((h + 15) / 16))      // une tuile « fond seul, sans fond annoncé » = noir
        else -> ByteArray(4)                                         // CopyRect : source (0, 0)
    }

    private fun check(encoding: Int) {
        var cases = 0
        for (x in values) for (y in values) for (w in values) for (h in values) {
            val ok = fits(x, y, w, h)
            val fb = patterned(fbW, fbH)
            val before = fb.pixels.copyOf()
            val stream = updateHeader(1) + rectHeader(x, y, w, h, encoding) + if (ok) payload(encoding, w, h) else ByteArray(0)
            val reader = ServerMessageReader(memoryClient(stream), fb, PixelFormat.XRGB_8888_LE)
            val what = "encodage $encoding rectangle x=$x y=$y w=$w h=$h"

            if (ok) {
                reader.readMessage()
                for (py in 0 until fbH) for (px in 0 until fbW) {
                    val inside = px >= x && px < x + w && py >= y && py < y + h
                    val expected = when {
                        !inside -> before[py * fbW + px]
                        encoding == COPY_RECT -> before[(py - y) * fbW + (px - x)]  // recopie de (0,0), avec recouvrement possible
                        else -> BLACK
                    }
                    assertEquals("$what : pixel ($px,$py)", expected, fb.pixels[py * fbW + px])
                }
            } else {
                val e = typedFailure({ what }) { reader.readMessage() }
                assertTrue("$what : attendu RectangleOutOfBounds, obtenu $e", e is RfbProtocolException.RectangleOutOfBounds)
                assertArrayEquals("$what : un rectangle refusé ne doit modifier aucun pixel", before, fb.pixels)
            }
            cases++
        }
        assertEquals(values.size.let { it * it * it * it }, cases)
    }

    @Test(timeout = 60_000)
    fun `RAW - every boundary rectangle is drawn exactly in place or refused without touching a pixel`() = check(RAW)

    @Test(timeout = 60_000)
    fun `Hextile - every boundary rectangle is drawn exactly in place or refused without touching a pixel`() = check(HEXTILE)

    @Test(timeout = 60_000)
    fun `CopyRect - every boundary rectangle is copied exactly, overlap included, or refused without touching a pixel`() = check(COPY_RECT)

    private companion object {
        const val RAW = 0
        const val COPY_RECT = 1
        const val HEXTILE = 5
        const val BLACK = 0xFF000000.toInt()
    }
}
