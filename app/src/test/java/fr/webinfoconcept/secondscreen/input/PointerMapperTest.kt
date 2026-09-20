package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.render.RenderGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversion position tactile -> pixel du framebuffer (SS-040). */
class PointerMapperTest {

    private val mapper = PointerMapper(1280, 800)

    private fun map(x: Float, y: Float): IntArray? {
        val out = intArrayOf(-7, -7)
        return if (mapper.map(x, y, out)) out else null
    }

    @Test
    fun `whole coordinates map to the same pixel`() {
        assertArrayEquals(intArrayOf(0, 0), map(0f, 0f))
        assertArrayEquals(intArrayOf(640, 400), map(640f, 400f))
        assertArrayEquals(intArrayOf(1279, 799), map(1279f, 799f))
    }

    @Test
    fun `fractions floor to the pixel that contains the point, never round to nearest`() {
        assertArrayEquals(intArrayOf(639, 400), map(639.9f, 400.2f))
        assertArrayEquals(intArrayOf(0, 0), map(0.99f, 0.5f))
        assertArrayEquals(intArrayOf(1279, 799), map(1279.99f, 799.99f))
    }

    @Test
    fun `every pixel of the framebuffer is reachable and maps to itself`() {
        val out = IntArray(2)
        for (x in 0 until 1280) {
            assertTrue(mapper.map(x + 0.5f, 10.5f, out))
            assertEquals(x, out[0])
        }
        for (y in 0 until 800) {
            assertTrue(mapper.map(10.5f, y + 0.5f, out))
            assertEquals(y, out[1])
        }
    }

    @Test
    fun `touches outside the framebuffer are not converted and leave the output untouched`() {
        assertEquals(null, map(-0.01f, 10f))
        assertEquals(null, map(10f, -0.01f))
        assertEquals(null, map(1280f, 10f))
        assertEquals(null, map(10f, 800f))
        assertEquals(null, map(5000f, 5000f))
        val out = intArrayOf(-7, -7)
        assertFalse(mapper.map(1280f, 0f, out))
        assertArrayEquals(intArrayOf(-7, -7), out)
    }

    @Test
    fun `non finite coordinates are refused`() {
        assertEquals(null, map(Float.NaN, 10f))
        assertEquals(null, map(10f, Float.NaN))
        assertEquals(null, map(Float.POSITIVE_INFINITY, 10f))
        assertEquals(null, map(10f, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `a float just below the edge never rounds up to the size`() {
        // Math.nextDown(1280f) est < 1280 mais très proche : le résultat doit rester <= 1279.
        assertArrayEquals(intArrayOf(1279, 799), map(Math.nextDown(1280f), Math.nextDown(800f)))
    }

    @Test
    fun `smaller and larger framebuffers use their own bounds`() {
        val small = PointerMapper(320, 200)
        val out = IntArray(2)
        assertTrue(small.map(319.5f, 199.5f, out))
        assertArrayEquals(intArrayOf(319, 199), out)
        assertFalse(small.map(320f, 0f, out))

        val large = PointerMapper(65535, 65535) // borne du U16
        assertTrue(large.map(65534.9f, 0f, out))
        assertEquals(65534, out[0])
    }

    @Test
    fun `size must fit the U16 coordinates of PointerEvent`() {
        assertThrows(IllegalArgumentException::class.java) { PointerMapper(0, 800) }
        assertThrows(IllegalArgumentException::class.java) { PointerMapper(1280, 0) }
        assertThrows(IllegalArgumentException::class.java) { PointerMapper(65536, 800) }
        assertThrows(IllegalArgumentException::class.java) { PointerMapper(1280, 65536) }
    }

    // ------------------------------------------------------------ mapClamped (glissement)

    private fun clamped(x: Float, y: Float): IntArray? {
        val out = intArrayOf(-7, -7)
        return if (mapper.mapClamped(x, y, out)) out else null
    }

    @Test
    fun `clamped mapping agrees with the strict one inside the framebuffer`() {
        assertArrayEquals(intArrayOf(639, 400), clamped(639.9f, 400.2f))
        assertArrayEquals(intArrayOf(0, 0), clamped(0f, 0f))
        assertArrayEquals(intArrayOf(1279, 799), clamped(1279.99f, 799.99f))
    }

    @Test
    fun `clamped mapping sticks to the nearest edge outside the framebuffer`() {
        assertArrayEquals(intArrayOf(0, 400), clamped(-50f, 400f))
        assertArrayEquals(intArrayOf(1279, 400), clamped(5000f, 400f))
        assertArrayEquals(intArrayOf(10, 0), clamped(10f, -0.5f))
        assertArrayEquals(intArrayOf(10, 799), clamped(10f, 800f))
        assertArrayEquals(intArrayOf(1279, 799), clamped(Math.nextUp(1280f), 1e9f))
        assertArrayEquals(intArrayOf(0, 0), clamped(-1e9f, -1e9f))
    }

    @Test
    fun `clamped mapping still refuses non finite values and leaves the output untouched`() {
        assertEquals(null, clamped(Float.NaN, 10f))
        assertEquals(null, clamped(10f, Float.NaN))
        assertEquals(null, clamped(Float.POSITIVE_INFINITY, 10f))
        assertEquals(null, clamped(10f, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `clamped mapping never leaves the U16 range for any finite input`() {
        val out = IntArray(2)
        for (v in listOf(-Float.MAX_VALUE, -1e30f, -1f, 0f, 1279.5f, 1280f, 1e30f, Float.MAX_VALUE)) {
            assertTrue(mapper.mapClamped(v, v, out))
            assertTrue(out[0] in 0..1279 && out[1] in 0..799)
        }
    }

    // ============================================================ mise à l'échelle (SS-033)

    /** 1920x1080 ajusté dans 1280x800 : échelle 2/3, image en (0, 40) de 1280x720. */
    private val fitted = RenderGeometry(1920, 1080, 1280, 800)
    private val scaledMapper = PointerMapper(1920, 1080) { fitted }

    private fun scaled(x: Float, y: Float): IntArray? {
        val out = intArrayOf(-7, -7)
        return if (scaledMapper.map(x, y, out)) out else null
    }

    @Test
    fun `a scaled image maps the view back to framebuffer pixels through offset and scale`() {
        assertArrayEquals(intArrayOf(0, 0), scaled(0f, 40f))                    // coin haut gauche de l'image
        assertArrayEquals(intArrayOf(960, 540), scaled(640f, 400f))             // le centre de la vue est le centre de l'image
        assertArrayEquals(intArrayOf(1500, 0), scaled(1000f, 40f))              // 1000 / (2/3) = 1500
        assertArrayEquals(intArrayOf(1919, 1079), scaled(1279.9f, 759.9f))      // dernier pixel, jamais 1920 ou 1080
    }

    @Test
    fun `a touch in a black bar is not converted and leaves the output untouched`() {
        assertEquals(null, scaled(100f, 39.9f))       // bande du haut
        assertEquals(null, scaled(100f, 760f))        // bande du bas
        val out = intArrayOf(-7, -7)
        assertFalse(scaledMapper.map(100f, 10f, out))
        assertArrayEquals(intArrayOf(-7, -7), out)
    }

    @Test
    fun `side bars are refused too`() {
        val g = RenderGeometry(1024, 768, 1280, 800)  // barres latérales de 106 px
        val m = PointerMapper(1024, 768) { g }
        val out = IntArray(2)

        assertFalse(m.map(50f, 400f, out))
        assertFalse(m.map(1200f, 400f, out))
        assertTrue(m.map(106.5f, 0.5f, out))
        assertArrayEquals(intArrayOf(0, 0), out)
    }

    @Test
    fun `clamped mapping brings a touch in a bar or outside back to the edge of the image`() {
        val out = IntArray(2)

        assertTrue(scaledMapper.mapClamped(100f, 5f, out))       // dans la bande du haut
        assertArrayEquals(intArrayOf(150, 0), out)
        assertTrue(scaledMapper.mapClamped(5000f, 5000f, out))
        assertArrayEquals(intArrayOf(1919, 1079), out)
        assertTrue(scaledMapper.mapClamped(-50f, 400f, out))
        assertEquals(0, out[0])
        assertFalse(scaledMapper.mapClamped(Float.NaN, 400f, out))
    }

    @Test
    fun `every framebuffer pixel that shows on screen maps to itself when the image is enlarged`() {
        val g = RenderGeometry(320, 200, 1280, 800)   // x4 : chaque pixel du framebuffer couvre 4x4 pixels d'écran
        val m = PointerMapper(320, 200) { g }
        val out = IntArray(2)
        for (fx in 0 until 320 step 7) for (fy in 0 until 200 step 5) {
            assertTrue(m.map(fx * 4f + 2f, fy * 4f + 2f, out))   // le centre du carré à l'écran
            assertEquals(fx, out[0])
            assertEquals(fy, out[1])
        }
    }

    @Test
    fun `mapping and drawing agree - the center of a reduced pixel maps back to that pixel`() {
        val out = IntArray(2)
        // le pixel (fx, fy) est dessiné à (destLeft + fx*scale ... destLeft + (fx+1)*scale) : on vise son centre
        for (fx in listOf(0, 1, 500, 959, 1918, 1919)) for (fy in listOf(0, 1, 540, 1078, 1079)) {
            val vx = fitted.destLeft + (fx + 0.5f) * fitted.scale
            val vy = fitted.destTop + (fy + 0.5f) * fitted.scale
            assertTrue(scaledMapper.map(vx, vy, out))
            assertEquals("x $fx", fx, out[0])
            assertEquals("y $fy", fy, out[1])
        }
    }

    @Test
    fun `the mapper follows the geometry as it changes, without being rebuilt`() {
        var current: RenderGeometry? = RenderGeometry(1280, 800, 1280, 800)
        val m = PointerMapper(1280, 800) { current }
        val out = IntArray(2)

        assertTrue(m.map(640f, 400f, out)); assertArrayEquals(intArrayOf(640, 400), out)

        current = RenderGeometry(1280, 800, 1280, 752, fitToScreen = true)   // l'utilisateur ajuste
        assertTrue(m.map(100f, 188f, out)); assertArrayEquals(intArrayOf(65, 200), out)    // (100-38)/0,94 ; 188/0,94

        current = null                                                          // plus de géométrie : 1:1
        assertTrue(m.map(100f, 188f, out)); assertArrayEquals(intArrayOf(100, 188), out)
    }

    @Test
    fun `viewScale is 1 without scaling and the scale with it`() {
        assertEquals(1f, mapper.viewScale, 0f)
        assertEquals(1280f / 1920f, scaledMapper.viewScale, 1e-6f)
        assertEquals(1f, PointerMapper(1280, 800) { RenderGeometry(1280, 800, 1280, 752) }.viewScale, 0f)
    }
}
