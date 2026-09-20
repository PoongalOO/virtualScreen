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
    fun `a larger surface leaves blank margins`() {
        val g = RenderGeometry(1024, 768, 1280, 800)

        assertFalse(g.isNative)
        assertFalse(g.isClipped)
        assertEquals(256, g.blankColumns)
        assertEquals(32, g.blankRows)
        assertEquals(1024, g.visibleWidth)
    }

    @Test
    fun `clipped in one axis and blank in the other`() {
        val g = RenderGeometry(1280, 800, 1400, 600)

        assertTrue(g.isClipped)
        assertEquals(200, g.clippedRows)
        assertEquals(120, g.blankColumns)
    }

    @Test
    fun `a not yet laid out surface of zero size clips everything`() {
        val g = RenderGeometry(1280, 800, 0, 0)

        assertFalse(g.isNative)
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
    fun `toString mentions both sizes and whether it is native`() {
        assertEquals("RenderGeometry(1280x800 dans 1280x752, natif=false)", RenderGeometry(1280, 800, 1280, 752).toString())
    }
}
