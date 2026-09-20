package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.Encoding
import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.testutil.HextileTestEncoder
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.allocatedBytesOfCurrentThread
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopArgb
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopRect
import fr.webinfoconcept.secondscreen.rfb.testutil.hextilePixel
import fr.webinfoconcept.secondscreen.rfb.testutil.rawXrgb
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * Décodeur Hextile (SS-026). D'abord un test par sous-encodage avec des flux écrits octet par octet
 * (indépendants de tout encodeur), puis la comparaison à une image attendue avec un encodeur de test, puis
 * la robustesse face à un serveur non fiable.
 */
class HextileDecoderTest {

    private val black = Framebuffer.OPAQUE_BLACK
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFFF00.toInt()

    // Sous-encodage
    private val raw = 0x01
    private val bg = 0x02
    private val fg = 0x04
    private val any = 0x08
    private val coloured = 0x10

    private fun px(argb: Int) = rawXrgb(intArrayOf(argb))
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun cat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun decode(fb: Framebuffer, x: Int, y: Int, w: Int, h: Int, vararg stream: ByteArray) {
        LoopbackPair().use { p ->
            p.send(cat(*stream))
            HextileDecoder().decode(p.client, x, y, w, h, fb)
        }
    }

    private fun assertRegion(fb: Framebuffer, x0: Int, y0: Int, w: Int, h: Int, colour: Int, what: String = "") {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) assertEquals("$what ($x,$y)", colour, fb.getPixel(x, y))
    }

    private fun countNot(fb: Framebuffer, colour: Int) = fb.pixels.count { it != colour }

    // ============================================================ un test par sous-encodage

    @Test
    fun `Raw tile - 256 pixels are copied as is`() {
        val fb = Framebuffer(32, 32)

        decode(fb, 0, 0, 16, 16, bytes(raw), rawXrgb(desktopRect(0, 0, 16, 16)))

        for (y in 0 until 16) for (x in 0 until 16) assertEquals("($x,$y)", desktopRect(0, 0, 16, 16)[y * 16 + x], fb.getPixel(x, y))
        for (y in 0 until 32) for (x in 0 until 32) if (x >= 16 || y >= 16) assertEquals("hors tuile ($x,$y)", black, fb.getPixel(x, y))
    }

    @Test
    fun `Raw tile - every other bit is ignored`() {
        // « If set, then the other bits are irrelevant » : 0xFF est une tuile brute.
        val fb = Framebuffer(32, 32)

        decode(fb, 0, 0, 16, 16, bytes(0xFF), rawXrgb(desktopRect(0, 0, 16, 16)))

        assertEquals(desktopRect(0, 0, 16, 16)[5 * 16 + 7], fb.getPixel(7, 5))
    }

    @Test
    fun `BackgroundSpecified only - the tile is one solid colour`() {
        val fb = Framebuffer(32, 32)

        decode(fb, 0, 0, 16, 16, bytes(bg), px(red))

        assertRegion(fb, 0, 0, 16, 16, red)
        assertEquals(256, fb.pixels.count { it == red })
        assertEquals("rien hors de la tuile", 256, countNot(fb, black))
    }

    @Test
    fun `mask zero reuses the background of the previous tile`() {
        val fb = Framebuffer(48, 16)

        decode(fb, 0, 0, 48, 16, bytes(bg), px(red), bytes(0x00), bytes(0x00))

        assertRegion(fb, 0, 0, 48, 16, red)
    }

    @Test
    fun `mask zero on the first tile gives black rather than an error`() {
        // Choix documenté : la spec impose de préciser le fond de la 1re tuile ; un serveur qui l'omet obtient du noir.
        val fb = Framebuffer(32, 32)
        fb.writeRect(0, 0, 32, 32, desktopArgb(32, 32))

        decode(fb, 0, 0, 16, 16, bytes(0x00))

        assertRegion(fb, 0, 0, 16, 16, black)
        assertEquals(desktopArgb(32, 32)[20 * 32 + 20], fb.getPixel(20, 20)) // hors rectangle : intact
    }

    @Test
    fun `ForegroundSpecified with AnySubrects - subrects use the foreground`() {
        val fb = Framebuffer(32, 32)
        // fond rouge, premier plan vert, 2 sous-rectangles : (2,3) 4x2 et (10,12) 6x3
        val stream = cat(bytes(bg or fg or any), px(red), px(green), bytes(2), bytes(0x23, 0x31), bytes(0xAC, 0x52))

        decode(fb, 0, 0, 16, 16, stream)

        for (y in 0 until 16) for (x in 0 until 16) {
            val inA = x in 2..5 && y in 3..4
            val inB = x in 10..15 && y in 12..14
            assertEquals("($x,$y)", if (inA || inB) green else red, fb.getPixel(x, y))
        }
    }

    @Test
    fun `SubrectsColoured - each subrect brings its own colour`() {
        val fb = Framebuffer(32, 32)
        // fond rouge ; (0,0) 1x1 bleu ; (1,1) 2x2 jaune
        val stream = cat(bytes(bg or any or coloured), px(red), bytes(2), px(blue), bytes(0x00, 0x00), px(yellow), bytes(0x11, 0x11))

        decode(fb, 0, 0, 16, 16, stream)

        assertEquals(blue, fb.getPixel(0, 0))
        assertRegion(fb, 1, 1, 2, 2, yellow)
        assertEquals(red, fb.getPixel(3, 3))
        assertEquals(1, fb.pixels.count { it == blue })
        assertEquals(4, fb.pixels.count { it == yellow })
    }

    @Test
    fun `AnySubrects with zero subrects is just the background`() {
        val fb = Framebuffer(32, 32)

        decode(fb, 0, 0, 16, 16, bytes(bg or any), px(red), bytes(0))

        assertRegion(fb, 0, 0, 16, 16, red)
    }

    // ================================================= état entre tuiles (persistance)

    @Test
    fun `the foreground persists to the next tile`() {
        val fb = Framebuffer(32, 16)
        val tile1 = cat(bytes(bg or fg or any), px(red), px(green), bytes(1), bytes(0x00, 0x00))
        val tile2 = cat(bytes(any), bytes(1), bytes(0x55, 0x00)) // ni fond ni premier plan : tout est hérité

        decode(fb, 0, 0, 32, 16, tile1, tile2)

        assertEquals(green, fb.getPixel(0, 0))
        assertEquals(red, fb.getPixel(16, 0))     // fond hérité
        assertEquals(green, fb.getPixel(16 + 5, 5)) // premier plan hérité
    }

    @Test
    fun `the background can change between tiles`() {
        val fb = Framebuffer(32, 16)

        decode(fb, 0, 0, 32, 16, bytes(bg), px(red), bytes(bg), px(blue))

        assertRegion(fb, 0, 0, 16, 16, red)
        assertRegion(fb, 16, 0, 16, 16, blue)
    }

    @Test
    fun `a Raw tile does not disturb the persisted background and foreground`() {
        val fb = Framebuffer(48, 16)
        val tile1 = cat(bytes(bg or fg or any), px(red), px(green), bytes(1), bytes(0x00, 0x00))
        val tile2 = cat(bytes(raw), rawXrgb(desktopRect(16, 0, 16, 16)))
        val tile3 = cat(bytes(any), bytes(1), bytes(0x33, 0x00)) // hérite fond et premier plan à travers la tuile brute

        decode(fb, 0, 0, 48, 16, tile1, tile2, tile3)

        assertEquals(red, fb.getPixel(32, 0))
        assertEquals(green, fb.getPixel(32 + 3, 3))
    }

    @Test
    fun `state does not leak from one rectangle to the next`() = LoopbackPair().use { p ->
        val fb = Framebuffer(32, 32)
        val decoder = HextileDecoder() // le MÊME décodeur pour les deux rectangles : c'est ce qui rend le test utile
        p.send(cat(bytes(bg or fg or any), px(red), px(green), bytes(0)))
        decoder.decode(p.client, 0, 0, 16, 16, fb)
        p.send(bytes(0x00)) // 1re tuile du 2e rectangle sans fond

        decoder.decode(p.client, 16, 16, 16, 16, fb)

        // Si le rouge du rectangle précédent fuyait, cette tuile serait rouge ; elle doit être noire.
        assertRegion(fb, 16, 16, 16, 16, black)
        assertRegion(fb, 0, 0, 16, 16, red)
    }

    @Test
    fun `ForegroundSpecified together with SubrectsColoured consumes the foreground and ignores it`() {
        // Interdit par la spec, mais le pixel de premier plan est présent dans le flux : il faut le lire pour rester aligné.
        val fb = Framebuffer(32, 16)
        val tile1 = cat(bytes(bg or fg or any or coloured), px(red), px(blue), bytes(1), px(yellow), bytes(0x00, 0x00))
        val tile2 = cat(bytes(bg), px(green))

        decode(fb, 0, 0, 32, 16, tile1, tile2)

        assertEquals(yellow, fb.getPixel(0, 0))     // couleur du sous-rectangle, pas le bleu
        assertEquals(red, fb.getPixel(1, 0))
        assertRegion(fb, 16, 0, 16, 16, green)      // la tuile suivante est bien alignée
    }

    // ================================================================ géométrie des sous-rectangles

    @Test
    fun `subrect extremes - single pixels in both corners and the full tile`() {
        val fb = Framebuffer(32, 32)
        // (0,0) 1x1 et (15,15) 1x1
        decode(fb, 0, 0, 16, 16, bytes(bg or fg or any), px(red), px(green), bytes(2), bytes(0x00, 0x00), bytes(0xFF, 0x00))

        assertEquals(green, fb.getPixel(0, 0))
        assertEquals(green, fb.getPixel(15, 15))
        assertEquals(2, fb.pixels.count { it == green })

        // sous-rectangle qui couvre toute la tuile : (0,0) 16x16 -> xy=0x00 wh=0xFF
        decode(fb, 0, 0, 16, 16, bytes(bg or fg or any), px(red), px(blue), bytes(1), bytes(0x00, 0xFF))
        assertRegion(fb, 0, 0, 16, 16, blue)
    }

    @Test
    fun `subrect position and size encodings`() {
        val fb = Framebuffer(32, 32)
        // xy = 0x53 -> x=5, y=3 ; wh = 0x31 -> w=4, h=2
        decode(fb, 0, 0, 16, 16, bytes(bg or fg or any), px(black), px(green), bytes(1), bytes(0x53, 0x31))

        assertRegion(fb, 5, 3, 4, 2, green)
        assertEquals(8, fb.pixels.count { it == green })
    }

    @Test
    fun `later subrects overwrite earlier ones where they overlap`() {
        val fb = Framebuffer(32, 32)
        // bleu (0,0) 4x4 puis vert (2,2) 4x4 : le pixel (2,2) est vert, (0,0) bleu
        val stream = cat(
            bytes(bg or any or coloured), px(red), bytes(2),
            px(blue), bytes(0x00, 0x33), px(green), bytes(0x22, 0x33)
        )

        decode(fb, 0, 0, 16, 16, stream)

        assertEquals(blue, fb.getPixel(0, 0))
        assertEquals(blue, fb.getPixel(1, 1))
        assertEquals(green, fb.getPixel(2, 2))
        assertEquals(green, fb.getPixel(5, 5))
        assertEquals(red, fb.getPixel(6, 6))
    }

    @Test
    fun `the maximum of 255 subrects is read in full and the stream stays aligned`() {
        val fb = Framebuffer(32, 16)
        val subrects = ByteArray(255 * 2)
        for (i in 0 until 255) { // une case 1x1 par position, sauf la dernière (15,15)
            subrects[2 * i] = (((i % 16) shl 4) or (i / 16)).toByte()
            subrects[2 * i + 1] = 0
        }
        val tile1 = cat(bytes(bg or fg or any), px(red), px(green), bytes(255), subrects)
        val tile2 = cat(bytes(bg), px(blue))

        decode(fb, 0, 0, 32, 16, tile1, tile2)

        assertEquals(255, fb.pixels.count { it == green })
        assertEquals(red, fb.getPixel(15, 15)) // la case non couverte garde le fond
        assertRegion(fb, 16, 0, 16, 16, blue)   // la tuile suivante est alignée
    }

    // ================================================================ tuiles de bord et positions

    @Test
    fun `edge tiles are smaller - a 17x17 rectangle has four tiles of 16x16, 1x16, 16x1 and 1x1`() {
        val fb = Framebuffer(32, 32)

        decode(fb, 0, 0, 17, 17, bytes(bg), px(red), bytes(bg), px(green), bytes(bg), px(blue), bytes(bg), px(yellow))

        assertRegion(fb, 0, 0, 16, 16, red, "tuile 1")
        assertRegion(fb, 16, 0, 1, 16, green, "tuile 2 (1x16)")
        assertRegion(fb, 0, 16, 16, 1, blue, "tuile 3 (16x1)")
        assertEquals(yellow, fb.getPixel(16, 16))
        assertEquals(256 + 16 + 16 + 1, fb.pixels.count { it != black })
    }

    @Test
    fun `a Raw tile at the edge reads width times height pixels`() {
        val fb = Framebuffer(32, 32)
        val pixels = desktopRect(0, 0, 5, 3)

        decode(fb, 0, 0, 5, 3, bytes(raw), rawXrgb(pixels))

        for (y in 0 until 3) for (x in 0 until 5) assertEquals(pixels[y * 5 + x], fb.getPixel(x, y))
    }

    @Test
    fun `tiles are relative to the rectangle origin not to the screen`() {
        val fb = Framebuffer(40, 40)

        // Rectangle en (5,7) de 20x20 : tuiles 16x16, 4x16, 16x4, 4x4 à partir de (5,7).
        decode(fb, 5, 7, 20, 20, bytes(bg), px(red), bytes(bg), px(green), bytes(bg), px(blue), bytes(bg), px(yellow))

        assertRegion(fb, 5, 7, 16, 16, red)
        assertRegion(fb, 21, 7, 4, 16, green)
        assertRegion(fb, 5, 23, 16, 4, blue)
        assertRegion(fb, 21, 23, 4, 4, yellow)
        assertEquals(20 * 20, fb.pixels.count { it != black })
        assertEquals(black, fb.getPixel(4, 7))    // juste à gauche
        assertEquals(black, fb.getPixel(25, 7))   // juste à droite
    }

    @Test
    fun `sizes around the tile boundary`() {
        for ((w, h) in listOf(1 to 1, 15 to 15, 16 to 16, 17 to 16, 16 to 17, 32 to 16, 33 to 20, 16 to 1, 1 to 16)) {
            val fb = Framebuffer(64, 64)
            val tiles = ((w + 15) / 16) * ((h + 15) / 16)
            val stream = cat(*Array(tiles) { cat(bytes(bg), px(red)) })

            decode(fb, 3, 4, w, h, stream)

            assertRegion(fb, 3, 4, w, h, red, "${w}x$h")
            assertEquals("${w}x$h", w * h, fb.pixels.count { it != black })
        }
    }

    @Test
    fun `reads exactly the tile data and nothing after`() = LoopbackPair().use { p ->
        val fb = Framebuffer(32, 32)
        p.send(cat(bytes(bg), px(red), bytes(9, 8, 7, 6)))

        HextileDecoder().decode(p.client, 0, 0, 16, 16, fb)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), next)
    }

    @Test
    fun `empty rectangles read nothing`() = LoopbackPair().use { p ->
        val fb = Framebuffer(32, 32)
        p.send(1, 2, 3, 4)

        val decoder = HextileDecoder()
        decoder.decode(p.client, 5, 5, 0, 8, fb)
        decoder.decode(p.client, 5, 5, 8, 0, fb)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), next)
        assertEquals(0, countNot(fb, black))
    }

    // ================================================================ comparaison à une image attendue

    /** Décode un rectangle produit par l'encodeur de test et compare à l'image attendue, pixel par pixel. */
    private fun assertImage(x: Int, y: Int, w: Int, h: Int, seed: Long?, fbW: Int = 260, fbH: Int = 200) {
        val encoder = HextileTestEncoder(seed?.let { Random(it) })
        val stream = encoder.encode(::hextilePixel, x, y, w, h)
        val fb = Framebuffer(fbW, fbH)
        LoopbackPair(readTimeoutMs = 10_000).use { p ->
            val writer = Thread { p.send(stream) }
            writer.start()
            HextileDecoder().decode(p.client, x, y, w, h, fb)
            writer.join()
        }
        for (cy in 0 until fbH) for (cx in 0 until fbW) {
            val inside = cx in x until x + w && cy in y until y + h
            assertEquals("${w}x$h en ($x,$y) seed=$seed pixel ($cx,$cy)", if (inside) hextilePixel(cx, cy) else black, fb.getPixel(cx, cy))
        }
    }

    @Test(timeout = 60_000)
    fun `an encoded desktop image is reproduced exactly at many positions and sizes`() {
        val rects = listOf(
            intArrayOf(0, 0, 64, 64), intArrayOf(0, 0, 200, 130), intArrayOf(7, 5, 100, 77), intArrayOf(63, 63, 50, 50),
            intArrayOf(0, 0, 1, 1), intArrayOf(0, 0, 17, 1), intArrayOf(100, 100, 16, 16), intArrayOf(15, 0, 33, 20),
            intArrayOf(64, 0, 64, 64), intArrayOf(0, 64, 64, 64), intArrayOf(128, 128, 100, 60), intArrayOf(31, 31, 2, 2)
        )
        for (r in rects) for (seed in listOf<Long?>(null, 1L, 2L, 3L)) assertImage(r[0], r[1], r[2], r[3], seed)
    }

    @Test
    fun `the test encoder really exercises every sub encoding`() {
        // Garde-fou : sans lui, la comparaison précédente pourrait ne tester que des tuiles unies.
        val encoder = HextileTestEncoder()
        encoder.encode(::hextilePixel, 0, 0, 256, 192)
        val c = encoder.maskCounts

        assertTrue("Raw", c[raw] > 0)
        assertTrue("fond seul", c[bg] > 0)
        assertTrue("tuile réutilisant le fond (masque 0)", c[0] > 0)
        assertTrue("premier plan + sous-rectangles", (0 until 256).any { m -> c[m] > 0 && m and any != 0 && m and coloured == 0 && m and raw == 0 })
        assertTrue("sous-rectangles colorés", (0 until 256).any { m -> c[m] > 0 && m and coloured != 0 })
        assertTrue("premier plan hérité (sans FG)", (0 until 256).any { m -> c[m] > 0 && m and any != 0 && m and coloured == 0 && m and fg == 0 && m and raw == 0 })
    }

    @Test(timeout = 60_000)
    fun `with random redundancy the encoder still reproduces the image`() {
        val encoder = HextileTestEncoder(Random(7))
        encoder.encode(::hextilePixel, 0, 0, 260, 200)
        // avec le hasard, on répète des fonds/premiers plans inutilement : plus de combinaisons de masques
        assertTrue(encoder.maskCounts.count { it > 0 } >= 8)
        assertImage(0, 0, 260, 200, seed = 7L)
    }

    @Test(timeout = 60_000)
    fun `Hextile and RAW produce the same framebuffer`() = LoopbackPair(readTimeoutMs = 10_000).use { p ->
        val x = 11
        val y = 9
        val w = 150
        val h = 90
        val viaHextile = Framebuffer(260, 200)
        val viaRaw = Framebuffer(260, 200)
        val hextile = HextileTestEncoder().encode(::hextilePixel, x, y, w, h)
        val pixels = IntArray(w * h) { hextilePixel(x + it % w, y + it / w) }
        val writer = Thread { p.send(hextile); p.send(rawXrgb(pixels)) }
        writer.start()

        HextileDecoder().decode(p.client, x, y, w, h, viaHextile)
        RawDecoder(PixelFormat.XRGB_8888_LE, 260).decode(p.client, x, y, w, h, viaRaw)
        writer.join()

        assertArrayEquals(viaRaw.pixels, viaHextile.pixels)
    }

    @Test(timeout = 60_000)
    fun `a full 1280x800 desktop is reproduced exactly and is smaller than RAW`() = LoopbackPair(readTimeoutMs = 20_000).use { p ->
        val stream = HextileTestEncoder().encode(::hextilePixel, 0, 0, 1280, 800)
        val fb = Framebuffer()
        val writer = Thread { p.send(stream) }
        writer.start()

        HextileDecoder().decode(p.client, 0, 0, 1280, 800, fb)
        writer.join()

        for (y in 0 until 800) for (x in 0 until 1280) {
            if (hextilePixel(x, y) != fb.pixels[y * 1280 + x]) throw AssertionError("pixel ($x,$y)")
        }
        val rawSize = 1280 * 800 * 4
        assertTrue("Hextile ${stream.size} octets contre RAW $rawSize", stream.size < rawSize * 8 / 10)
    }

    @Test(timeout = 30_000)
    fun `survives TCP fragmentation`() {
        val stream = HextileTestEncoder(Random(11)).encode(::hextilePixel, 5, 5, 70, 50)
        for (chunk in listOf(1, 2, 3, 7, 100)) {
            val fb = Framebuffer(80, 60)
            LoopbackPair().use { p ->
                val writer = Thread { p.sendFragmented(stream, chunk) }
                writer.start()

                HextileDecoder().decode(p.client, 5, 5, 70, 50, fb)
                writer.join()
            }
            for (y in 5 until 55) for (x in 5 until 75) assertEquals("fragments de $chunk ($x,$y)", hextilePixel(x, y), fb.getPixel(x, y))
        }
    }

    // ================================================================ autres formats de pixels

    @Test
    fun `other pixel formats go through the generic conversion`() {
        val rgb565 = PixelFormat(16, 16, false, true, 31, 63, 31, 11, 5, 0)
        val fb = Framebuffer(32, 32)
        // fond bleu 0x001F, un sous-rectangle rouge 0xF800 (2x2 en (0,0)), pixels sur 2 octets little-endian
        val stream = cat(bytes(bg or any or coloured), bytes(0x1F, 0x00), bytes(1), bytes(0x00, 0xF8), bytes(0x00, 0x11))
        LoopbackPair().use { p ->
            p.send(stream)
            HextileDecoder(rgb565).decode(p.client, 0, 0, 16, 16, fb)
        }

        assertEquals(blue, fb.getPixel(5, 5))
        assertRegion(fb, 0, 0, 2, 2, red)
        assertEquals(blue, fb.getPixel(2, 2))
    }

    @Test
    fun `a Raw tile in a two byte format reads two bytes per pixel`() {
        val rgb565 = PixelFormat(16, 16, true, true, 31, 63, 31, 11, 5, 0)
        val random = Random(3)
        val data = ByteArray(16 * 16 * 2).also { random.nextBytes(it) }
        val fb = Framebuffer(32, 32)
        LoopbackPair().use { p ->
            p.send(cat(bytes(raw), data, bytes(9)))
            HextileDecoder(rgb565).decode(p.client, 0, 0, 16, 16, fb)
            val next = ByteArray(1)
            p.client.readFully(next, 0, 1)
            assertEquals("flux aligné après 512 octets", 9, next[0].toInt())
        }

        for (i in 0 until 256) assertEquals("pixel $i", rgb565.decodePixel(data, 2 * i), fb.getPixel(i % 16, i / 16))
    }

    // ================================================================ serveur non fiable : erreurs

    private fun decodeError(w: Int, h: Int, vararg stream: ByteArray, fbSize: Int = 64): Pair<RfbProtocolException.InvalidHextileTile, Framebuffer> {
        val fb = Framebuffer(fbSize, fbSize)
        lateinit var error: RfbProtocolException.InvalidHextileTile
        LoopbackPair().use { p ->
            p.send(cat(*stream))
            error = assertThrows(RfbProtocolException.InvalidHextileTile::class.java) {
                HextileDecoder().decode(p.client, 0, 0, w, h, fb)
            }
        }
        return error to fb
    }

    @Test
    fun `undefined sub encoding bits are refused`() {
        // bits 5, 6, 7 : ils révèlent en pratique un flux désaligné.
        for (mask in listOf(0x20, 0x40, 0x80, 0x60, 0xE0, 0xA2, 0x30, 0xC8)) {
            val (e, _) = decodeError(16, 16, bytes(mask))
            assertEquals("masque 0x${Integer.toHexString(mask)}", "sous-encodage inconnu", e.detail)
        }
    }

    @Test
    fun `undefined bits are ignored when Raw is set`() {
        val fb = Framebuffer(32, 32)

        decode(fb, 0, 0, 16, 16, bytes(raw or 0xE0), rawXrgb(desktopRect(0, 0, 16, 16)))

        assertEquals(desktopRect(0, 0, 16, 16)[0], fb.getPixel(0, 0))
    }

    @Test
    fun `a subrect that leaves its 16x16 tile is refused and nothing is written outside the rectangle`() {
        val cases = listOf(
            bytes(0xF0, 0x10), // x=15, w=2 -> 17
            bytes(0x0F, 0x01), // y=15, h=2 -> 17
            bytes(0x88, 0xFF), // (8,8) 16x16 -> 24
            bytes(0xFF, 0x11)  // (15,15) 2x2
        )
        for (subrect in cases) {
            val (e, fb) = decodeError(16, 16, cat(bytes(bg or fg or any), px(red), px(green), bytes(1), subrect))
            assertEquals("sous-rectangle hors tuile", e.detail)
            // Seul le fond de la tuile a été peint : le sous-rectangle fautif n'est pas dessiné, rien hors du rectangle.
            assertEquals(256, fb.pixels.count { it == red })
            assertEquals(0, fb.pixels.count { it == green })
            assertEquals(256, countNot(fb, black))
        }
    }

    @Test
    fun `a subrect must fit in a smaller edge tile too`() {
        // Tuile de bord 4x4 (rectangle 4x4) : un sous-rectangle de 5x1 ou en (3,3) de 2x2 dépasse la tuile.
        for (subrect in listOf(bytes(0x00, 0x40), bytes(0x33, 0x11), bytes(0x30, 0x10), bytes(0x03, 0x01))) {
            val (e, _) = decodeError(4, 4, cat(bytes(bg or fg or any), px(red), px(green), bytes(1), subrect))
            assertEquals("sous-rectangle hors tuile", e.detail)
        }
        // Et un sous-rectangle qui tient exactement est accepté.
        val fb = Framebuffer(32, 32)
        decode(fb, 0, 0, 4, 4, bytes(bg or fg or any), px(red), px(green), bytes(1), bytes(0x00, 0x33))
        assertRegion(fb, 0, 0, 4, 4, green)
    }

    @Test
    fun `an out of bounds rectangle is refused before reading anything`() = LoopbackPair(readTimeoutMs = 1_500).use { p ->
        val fb = Framebuffer(32, 32)
        val start = System.nanoTime()

        assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) {
            HextileDecoder().decode(p.client, 20, 20, 16, 16, fb)
        }

        assertTrue("lecture tentée", (System.nanoTime() - start) / 1_000_000 < 1_000)
        assertEquals(0, countNot(fb, black))
    }

    @Test
    fun `EOF at every position of a tile is an end of stream with the right counts`() {
        // mask + fond(4) + premier plan(4) + nombre(1) = 10 octets, puis 1 sous-rectangle de 2 octets.
        val full = cat(bytes(bg or fg or any), px(red), px(green), bytes(1), bytes(0x00, 0x00))
        for (cut in 0 until full.size) {
            LoopbackPair().use { p ->
                p.send(full.copyOfRange(0, cut))
                p.peer.close()

                val e = assertThrows("coupé à $cut", RfbTransportException.EndOfStream::class.java) {
                    HextileDecoder().decode(p.client, 0, 0, 16, 16, Framebuffer(32, 32))
                }

                val (read, expected) = when {
                    cut == 0 -> 0 to 1                 // le masque
                    cut <= 9 -> (cut - 1) to 9         // fond + premier plan + nombre en une lecture
                    else -> (cut - 10) to 2            // le sous-rectangle
                }
                assertEquals("lus, coupé à $cut", read, e.bytesRead)
                assertEquals("attendus, coupé à $cut", expected, e.bytesExpected)
            }
        }
    }

    @Test
    fun `EOF in the middle of a Raw tile is an end of stream`() = LoopbackPair().use { p ->
        p.send(cat(bytes(raw), ByteArray(300)))
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            HextileDecoder().decode(p.client, 0, 0, 16, 16, Framebuffer(32, 32))
        }

        assertEquals(300, e.bytesRead)
        assertEquals(1024, e.bytesExpected)
    }

    @Test(timeout = 30_000)
    fun `random garbage never crashes and never writes outside the rectangle`() {
        val random = Random(4242)
        val allowedOutcomes = mutableSetOf<String>()
        repeat(400) { iteration ->
            val w = 1 + random.nextInt(40)
            val h = 1 + random.nextInt(40)
            val x = random.nextInt(64 - w + 1)
            val y = random.nextInt(64 - h + 1)
            val garbage = ByteArray(random.nextInt(600)).also { random.nextBytes(it) }
            val fb = Framebuffer(64, 64)

            LoopbackPair().use { p ->
                p.send(garbage)
                p.peer.close()
                try {
                    HextileDecoder().decode(p.client, x, y, w, h, fb)
                    allowedOutcomes += "ok"
                } catch (e: RfbProtocolException.InvalidHextileTile) {
                    allowedOutcomes += "tuile invalide"
                } catch (e: RfbTransportException.EndOfStream) {
                    allowedOutcomes += "EOF"
                }
                // Toute autre exception (ArrayIndexOutOfBounds...) fait échouer le test.
            }

            for (cy in 0 until 64) for (cx in 0 until 64) {
                val inside = cx in x until x + w && cy in y until y + h
                if (!inside && fb.pixels[cy * 64 + cx] != black) {
                    throw AssertionError("itération $iteration : pixel ($cx,$cy) écrit hors du rectangle ($x,$y ${w}x$h)")
                }
            }
        }
        // Les trois issues se produisent : le fuzzing n'est pas vide de sens.
        assertTrue(allowedOutcomes.toString(), allowedOutcomes.containsAll(listOf("tuile invalide", "EOF")))
    }

    // ================================================================ allocation

    @Test(timeout = 60_000)
    fun `decoding allocates nothing`() {
        assumeTrue("mesure d'allocation indisponible sur cette JVM", allocatedBytesOfCurrentThread() != null)
        val stream = HextileTestEncoder(Random(5)).encode(::hextilePixel, 0, 0, 128, 128)
        val fb = Framebuffer(128, 128)
        val decoder = HextileDecoder()
        val rounds = 250

        LoopbackPair(readTimeoutMs = 20_000).use { p ->
            val writer = Thread { repeat(rounds) { p.send(stream) } }
            writer.start()
            repeat(50) { decoder.decode(p.client, 0, 0, 128, 128, fb) } // échauffement

            val before = allocatedBytesOfCurrentThread()!!
            repeat(rounds - 50) { decoder.decode(p.client, 0, 0, 128, 128, fb) }
            val allocated = allocatedBytesOfCurrentThread()!! - before
            writer.join()

            // 200 décodages de 64 tuiles : un objet par tuile ferait plus de 200 Kio.
            assertTrue("$allocated octets alloués", allocated < 16_384)
        }
    }

    @Test
    fun `identifies itself as the Hextile encoding`() {
        assertEquals(Encoding.HEXTILE, HextileDecoder().encoding)
        assertEquals(5, HextileDecoder().encoding)
    }
}
