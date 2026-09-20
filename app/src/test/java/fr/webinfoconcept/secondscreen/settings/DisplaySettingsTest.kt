package fr.webinfoconcept.secondscreen.settings

import fr.webinfoconcept.secondscreen.profile.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réglage « plein écran » : faux par défaut, mémorisé, tolérant aux données étranges. */
class DisplaySettingsTest {

    private class MemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        val data = LinkedHashMap(initial)
        override fun all(): Map<String, String> = LinkedHashMap(data)
        override fun apply(changes: Map<String, String?>) {
            for ((k, v) in changes) if (v == null) data.remove(k) else data[k] = v
        }
    }

    @Test
    fun `fullscreen is off by default`() {
        assertFalse(DisplaySettings(MemoryStore()).fullscreen)
    }

    @Test
    fun `the choice is remembered by a new instance on the same store`() {
        val store = MemoryStore()

        DisplaySettings(store).fullscreen = true
        assertTrue(DisplaySettings(store).fullscreen)

        DisplaySettings(store).fullscreen = false
        assertFalse(DisplaySettings(store).fullscreen)
    }

    @Test
    fun `anything other than the exact stored value means off`() {
        for (garbage in listOf("", "TRUE", "1", "yes", "true ", "null")) {
            assertFalse("« $garbage »", DisplaySettings(MemoryStore(mapOf("fullscreen" to garbage))).fullscreen)
        }
    }

    @Test
    fun `only the fullscreen key is ever written`() {
        val store = MemoryStore()

        DisplaySettings(store).fullscreen = true

        assertEquals(setOf("fullscreen"), store.data.keys)
    }

    @Test
    fun `the file is separate from the connection profiles`() {
        assertTrue(DisplaySettings.FILE_NAME != fr.webinfoconcept.secondscreen.profile.PreferencesStore.FILE_NAME)
    }
}
