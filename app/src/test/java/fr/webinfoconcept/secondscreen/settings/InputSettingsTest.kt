package fr.webinfoconcept.secondscreen.settings

import fr.webinfoconcept.secondscreen.input.TouchpadActions
import fr.webinfoconcept.secondscreen.profile.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réglages des entrées : mode touchpad et sensibilité (SS-045). */
class InputSettingsTest {

    private class MemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        val data = LinkedHashMap(initial)
        override fun all(): Map<String, String> = LinkedHashMap(data)
        override fun apply(changes: Map<String, String?>) {
            for ((k, v) in changes) if (v == null) data.remove(k) else data[k] = v
        }
    }

    @Test
    fun `direct mode and the default sensitivity by default`() {
        val s = InputSettings(MemoryStore())

        assertFalse(s.touchpad)
        assertEquals(TouchpadActions.DEFAULT_SENSITIVITY, s.touchpadSensitivity, 0f)
    }

    @Test
    fun `the mode and the sensitivity are remembered by a new instance`() {
        val store = MemoryStore()

        InputSettings(store).touchpad = true
        InputSettings(store).touchpadSensitivity = 2.5f

        assertTrue(InputSettings(store).touchpad)
        assertEquals(2.5f, InputSettings(store).touchpadSensitivity, 0f)
    }

    @Test
    fun `a sensitivity outside the bounds is clamped when written and when read`() {
        val store = MemoryStore()
        InputSettings(store).touchpadSensitivity = 100f
        assertEquals(TouchpadActions.MAX_SENSITIVITY, InputSettings(store).touchpadSensitivity, 0f)
        InputSettings(store).touchpadSensitivity = -3f
        assertEquals(TouchpadActions.MIN_SENSITIVITY, InputSettings(store).touchpadSensitivity, 0f)

        // valeur hors bornes déjà dans le stockage (autre version, fichier modifié)
        assertEquals(TouchpadActions.MAX_SENSITIVITY, InputSettings(MemoryStore(mapOf("touchpad_sensitivity" to "9999"))).touchpadSensitivity, 0f)
        assertEquals(TouchpadActions.MIN_SENSITIVITY, InputSettings(MemoryStore(mapOf("touchpad_sensitivity" to "0"))).touchpadSensitivity, 0f)
    }

    @Test
    fun `unreadable or non finite stored values give the default, never an exception`() {
        for (garbage in listOf("", "abc", "NaN", "Infinity", "-Infinity", "1,5", "1.5.2", " ")) {
            assertEquals("« $garbage »", TouchpadActions.DEFAULT_SENSITIVITY,
                InputSettings(MemoryStore(mapOf("touchpad_sensitivity" to garbage))).touchpadSensitivity, 0f)
        }
    }

    @Test
    fun `only an exact true means touchpad`() {
        for (garbage in listOf("", "TRUE", "1", "yes", "true ")) {
            assertFalse("« $garbage »", InputSettings(MemoryStore(mapOf("touchpad" to garbage))).touchpad)
        }
    }

    @Test
    fun `a non finite sensitivity cannot be written`() {
        val s = InputSettings(MemoryStore())

        assertThrows(IllegalArgumentException::class.java) { s.touchpadSensitivity = Float.NaN }
        assertThrows(IllegalArgumentException::class.java) { s.touchpadSensitivity = Float.POSITIVE_INFINITY }
    }

    @Test
    fun `only the two expected keys are written, in a file of their own`() {
        val store = MemoryStore()

        InputSettings(store).touchpad = true
        InputSettings(store).touchpadSensitivity = 1f

        assertEquals(setOf("touchpad", "touchpad_sensitivity"), store.data.keys)
        assertTrue(InputSettings.FILE_NAME != DisplaySettings.FILE_NAME)
    }
}
