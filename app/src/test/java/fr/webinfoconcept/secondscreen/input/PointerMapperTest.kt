package fr.webinfoconcept.secondscreen.input

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
}
