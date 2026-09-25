package fr.webinfoconcept.secondscreen.input

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Correction du défaut de toucher de la GT-P5110 (SS-088) : fonction pure, sans dépendance à un appareil réel.
 * `Surface.ROTATION_*` sont des constantes entières (API 1) : les référencer ne fait aucun appel Android.
 */
class TouchRotationQuirkTest {

    @Test
    fun `the faulty rotation is exactly Surface ROTATION_180, measured on the GT-P5110`() {
        // Documente la valeur mesurée (SS-084) : si cette constante Android changeait de sens, ce test le dirait.
        assertEquals(2, Surface.ROTATION_180)
        assertEquals(Surface.ROTATION_180, TouchRotationQuirk.UNCOMPENSATED_ROTATION)
    }

    @Test
    fun `every other rotation is left exactly unchanged, including out of range values`() {
        val untouched = intArrayOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_270, -1, 4, 180)
        for (rotation in untouched) {
            for ((x, y) in listOf(0f to 0f, 1280f to 800f, 640.5f to 12.25f, -3f to 900f, Float.NaN to Float.POSITIVE_INFINITY)) {
                val (cx, cy) = TouchRotationQuirk.correct(x, y, 1280, 800, rotation)
                if (x.isNaN()) assertEquals("rotation=$rotation", true, cx.isNaN()) else assertEquals("rotation=$rotation", x, cx, 0f)
                if (y.isInfinite()) assertEquals("rotation=$rotation", true, cy.isInfinite()) else assertEquals("rotation=$rotation", y, cy, 0f)
            }
        }
    }

    @Test
    fun `the faulty rotation mirrors the position around the centre of the view`() {
        assertEquals(1280f to 800f, TouchRotationQuirk.correct(0f, 0f, 1280, 800, Surface.ROTATION_180))
        assertEquals(0f to 0f, TouchRotationQuirk.correct(1280f, 800f, 1280, 800, Surface.ROTATION_180))
        assertEquals(640f to 400f, TouchRotationQuirk.correct(640f, 400f, 1280, 800, Surface.ROTATION_180)) // le centre est invariant
        assertEquals(1030f to 150f, TouchRotationQuirk.correct(250f, 650f, 1280, 800, Surface.ROTATION_180)) // symétrique, comme mesuré (SS-084)
        assertEquals(250f to 650f, TouchRotationQuirk.correct(1030f, 150f, 1280, 800, Surface.ROTATION_180)) // et réciproquement
    }

    @Test
    fun `correcting twice with the faulty rotation returns to the original position`() {
        for ((x, y) in listOf(0f to 0f, 1f to 799f, 640.5f to 12.25f, 1279.9f to 0.1f)) {
            val (cx, cy) = TouchRotationQuirk.correct(x, y, 1280, 800, Surface.ROTATION_180)
            val (ox, oy) = TouchRotationQuirk.correct(cx, cy, 1280, 800, Surface.ROTATION_180)
            assertEquals(x, ox, 1e-4f)
            assertEquals(y, oy, 1e-4f)
        }
    }

    @Test
    fun `the correction only depends on the view size, not on the framebuffer`() {
        // La vue peut être plus grande ou plus petite que le framebuffer (barre système, plein écran) : c'est la taille
        // de la vue qui compte, puisque c'est elle que le toucher parcourt.
        assertEquals(64f to 48f, TouchRotationQuirk.correct(0f, 0f, 64, 48, Surface.ROTATION_180))
        assertEquals(1920f to 1080f, TouchRotationQuirk.correct(0f, 0f, 1920, 1080, Surface.ROTATION_180))
    }
}
