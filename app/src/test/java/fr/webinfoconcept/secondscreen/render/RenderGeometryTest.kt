package fr.webinfoconcept.secondscreen.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** RenderGeometry (SS-032) : le rendu est-il fidèle, et sinon que perd-on ? */
class RenderGeometryTest {

    @Test
    fun `identical sizes are native with nothing clipped or blank`() {
        val g = RenderGeometry(1280, 800, 1280, 800)

        assertTrue(g.isNative)
        assertFalse(g.isClipped)
        assertEquals(1280, g.visibleWidth)
        assertEquals(800, g.visibleHeight)
        assertEquals(0, g.clippedColumns + g.clippedRows + g.blankColumns + g.blankRows)
    }

    @Test
    fun `the 752 line surface of a visible system bar clips 48 rows`() {
        // Le cas réel de SS-030 : barre système de 48 px sur la GT-P5110.
        val g = RenderGeometry(1280, 800, 1280, 752)

        assertFalse(g.isNative)
        assertTrue(g.isClipped)
        assertEquals(48, g.clippedRows)
        assertEquals(0, g.clippedColumns)
        assertEquals(752, g.visibleHeight)
    }

    @Test
    fun `a narrower surface clips columns`() {
        val g = RenderGeometry(1280, 800, 1200, 800)

        assertEquals(80, g.clippedColumns)
        assertEquals(1200, g.visibleWidth)
    }

    @Test
    fun `a nominal frame in a larger surface leaves blank margins, still pixel for pixel`() {
        val g = RenderGeometry(1280, 800, 1400, 900)

        assertFalse(g.isNative)
        assertFalse(g.isScaled)
        assertFalse(g.isClipped)
        assertEquals(120, g.blankColumns)
        assertEquals(100, g.blankRows)
        assertEquals(1280, g.visibleWidth)
    }

    @Test
    fun `clipped in one axis and blank in the other, for a nominal frame`() {
        val g = RenderGeometry(1280, 800, 1400, 600)

        assertTrue(g.isClipped)
        assertEquals(200, g.clippedRows)
        assertEquals(120, g.blankColumns)
    }

    @Test
    fun `a not yet laid out surface of zero size clips everything and is never scaled`() {
        val g = RenderGeometry(1280, 800, 0, 0)

        assertFalse(g.isNative)
        assertFalse(g.isScaled)
        assertFalse(RenderGeometry(1920, 1080, 0, 0).isScaled)
        assertEquals(0, g.visibleWidth)
        assertEquals(1280, g.clippedColumns)
    }

    @Test
    fun `invalid sizes are refused`() {
        assertThrows(IllegalArgumentException::class.java) { RenderGeometry(0, 800, 1280, 800) }
        assertThrows(IllegalArgumentException::class.java) { RenderGeometry(1280, -1, 1280, 800) }
        assertThrows(IllegalArgumentException::class.java) { RenderGeometry(1280, 800, -1, 800) }
    }

    @Test
    fun `toString mentions both sizes and whether it is native or scaled`() {
        assertEquals("RenderGeometry(1280x800 dans 1280x752, natif=false, ajusté=false)", RenderGeometry(1280, 800, 1280, 752).toString())
        assertEquals("RenderGeometry(1920x1080 dans 1280x800, natif=false, ajusté=true)", RenderGeometry(1920, 1080, 1280, 800).toString())
    }

    // ===================================================================== SS-033 : ajusté avec bandes noires

    @Test
    fun `a 1920x1080 server is fitted into 1280x800 with bars above and below and nothing cropped`() {
        val g = RenderGeometry(1920, 1080, 1280, 800)

        assertTrue(g.isScaled)
        assertFalse(g.isClipped)
        assertEquals(1280f / 1920f, g.scale, 1e-6f)          // 0,667 : la largeur limite
        assertEquals(1280, g.destWidth)
        assertEquals(720, g.destHeight)
        assertEquals(0, g.destLeft)
        assertEquals(40, g.destTop)                          // (800 - 720) / 2
        assertEquals(0, g.barColumns)
        assertEquals(80, g.barRows)
    }

    @Test
    fun `a 1024x768 server is fitted into 1280x800 with bars on the sides`() {
        val g = RenderGeometry(1024, 768, 1280, 800)

        assertTrue(g.isScaled)
        assertEquals(800f / 768f, g.scale, 1e-6f)            // la hauteur limite : 1,0417
        assertEquals(1067, g.destWidth)                      // round(1024 x 1,0417)
        assertEquals(800, g.destHeight)
        assertEquals(106, g.destLeft)                        // (1280 - 1067) / 2
        assertEquals(0, g.destTop)
        assertEquals(213, g.barColumns)
    }

    @Test
    fun `a server with the same ratio as the surface fills it with no bar`() {
        val g = RenderGeometry(2560, 1600, 1280, 800)

        assertTrue(g.isScaled)
        assertEquals(0.5f, g.scale, 0f)
        assertEquals(0, g.barColumns + g.barRows)
        assertEquals(0, g.destLeft + g.destTop)
    }

    @Test
    fun `the nominal 1280x800 is never scaled unless asked, and never when the sizes are equal`() {
        assertFalse(RenderGeometry(1280, 800, 1280, 752).isScaled)
        assertFalse(RenderGeometry(1280, 800, 1280, 800, fitToScreen = true).isScaled)  // déjà natif
        assertTrue(RenderGeometry(1280, 800, 1280, 752, fitToScreen = true).isScaled)
    }

    @Test
    fun `fitting a nominal screen into the 752 line surface shows the 48 rows that 1 to 1 crops`() {
        val plain = RenderGeometry(1280, 800, 1280, 752)
        val fit = RenderGeometry(1280, 800, 1280, 752, fitToScreen = true)

        assertEquals(48, plain.clippedRows)
        assertEquals(0, fit.clippedRows)
        assertFalse(fit.isClipped)
        assertEquals(0.94f, fit.scale, 1e-6f)
        assertEquals(752, fit.destHeight)
        assertEquals(1203, fit.destWidth)                     // round(1280 x 0,94)
        assertEquals(38, fit.destLeft)
    }

    @Test
    fun `a server in the native size of the surface is native even when it is not 1280x800`() {
        val g = RenderGeometry(1280, 752, 1280, 752)

        assertTrue(g.isNative)
        assertFalse(g.isScaled)
        assertEquals(1f, g.scale, 0f)
    }

    @Test
    fun `without scaling the destination is the anchored visible part`() {
        val g = RenderGeometry(1280, 800, 1280, 752)

        assertEquals(0, g.destLeft)
        assertEquals(0, g.destTop)
        assertEquals(1280, g.destWidth)
        assertEquals(752, g.destHeight)
        assertEquals(1f, g.scale, 0f)
        assertEquals(0, g.barColumns + g.barRows)
    }

    @Test
    fun `for any server and surface the fitted image lies inside the surface, keeps its ratio, is centered and touches an edge`() {
        val rnd = java.util.Random(33)
        repeat(5_000) {
            val fw = 1 + rnd.nextInt(1920)
            val fh = 1 + rnd.nextInt(1200)
            val sw = 1 + rnd.nextInt(2000)
            val sh = 1 + rnd.nextInt(1300)
            val g = RenderGeometry(fw, fh, sw, sh, fitToScreen = rnd.nextBoolean())
            if (!g.isScaled) return@repeat

            val label = "${fw}x$fh dans ${sw}x$sh"
            assertTrue("dans la surface : $label", g.destLeft >= 0 && g.destTop >= 0)
            assertTrue("dans la surface : $label", g.destLeft + g.destWidth <= sw && g.destTop + g.destHeight <= sh)
            assertTrue("largeur et hauteur non nulles : $label", g.destWidth >= 1 && g.destHeight >= 1)
            // ratio conservé à un pixel d'arrondi près : le rapport sur chaque axe est celui de `scale`
            assertEquals("largeur : $label", fw * g.scale, g.destWidth.toFloat(), 1f)
            assertEquals("hauteur : $label", fh * g.scale, g.destHeight.toFloat(), 1f)
            // centré : les deux bandes d'un axe diffèrent d'au plus un pixel
            assertTrue("centré : $label", Math.abs(g.destLeft - (sw - g.destWidth - g.destLeft)) <= 1)
            assertTrue("centré : $label", Math.abs(g.destTop - (sh - g.destHeight - g.destTop)) <= 1)
            // l'image touche un bord au moins (elle est aussi grande que possible), à un pixel d'arrondi près
            assertTrue("aussi grande que possible : $label", sw - g.destWidth <= 1 || sh - g.destHeight <= 1)
            assertFalse("rien de rogné", g.isClipped)
            assertTrue(g.scale > 0f)
        }
    }

    @Test
    fun `a fitted frame that is much smaller than the surface is enlarged, and one that is much larger is reduced`() {
        assertTrue(RenderGeometry(320, 200, 1280, 800).scale > 1f)
        assertEquals(4f, RenderGeometry(320, 200, 1280, 800).scale, 0f)
        assertTrue(RenderGeometry(1920, 1200, 1280, 800).scale < 1f)
    }
}
