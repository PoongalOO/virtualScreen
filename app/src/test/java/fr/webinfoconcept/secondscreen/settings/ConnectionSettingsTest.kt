package fr.webinfoconcept.secondscreen.settings

import fr.webinfoconcept.secondscreen.profile.KeyValueStore
import fr.webinfoconcept.secondscreen.rfb.protocol.EncodingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réglage de la reconnexion automatique (SS-055). */
class ConnectionSettingsTest {

    private class MemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        val data = LinkedHashMap(initial)
        override fun all(): Map<String, String> = LinkedHashMap(data)
        override fun apply(changes: Map<String, String?>) {
            for ((k, v) in changes) if (v == null) data.remove(k) else data[k] = v
        }
    }

    @Test
    fun `automatic reconnection is on by default`() {
        assertTrue(ConnectionSettings(MemoryStore()).autoReconnect)
    }

    @Test
    fun `turning it off is remembered, turning it back on too`() {
        val store = MemoryStore()

        ConnectionSettings(store).autoReconnect = false
        assertFalse(ConnectionSettings(store).autoReconnect)

        ConnectionSettings(store).autoReconnect = true
        assertTrue(ConnectionSettings(store).autoReconnect)
    }

    @Test
    fun `only an exact false turns it off - anything unreadable keeps the default`() {
        for (garbage in listOf("", "FALSE", "0", "no", "false ", "null")) {
            assertTrue("« $garbage »", ConnectionSettings(MemoryStore(mapOf("auto_reconnect" to garbage))).autoReconnect)
        }
        assertFalse(ConnectionSettings(MemoryStore(mapOf("auto_reconnect" to "false"))).autoReconnect)
    }

    @Test
    fun `only its own key is written, in a file of its own, and no secret`() {
        val store = MemoryStore()

        ConnectionSettings(store).autoReconnect = false

        assertEquals(setOf("auto_reconnect"), store.data.keys)
        assertTrue(ConnectionSettings.FILE_NAME !in listOf(InputSettings.FILE_NAME, DisplaySettings.FILE_NAME, fr.webinfoconcept.secondscreen.profile.PreferencesStore.FILE_NAME))
        assertTrue(store.data.keys.none { "pass" in it || "secret" in it || "pwd" in it })
    }

    // ----------------------------------------------------------- encodage demandé (SS-063)

    @Test
    fun `the encoding mode is automatic by default`() {
        assertEquals(EncodingMode.AUTO, ConnectionSettings(MemoryStore()).encodingMode)
    }

    @Test
    fun `every encoding mode is remembered`() {
        for (mode in EncodingMode.values()) {
            val store = MemoryStore()
            ConnectionSettings(store).encodingMode = mode
            assertEquals(mode, ConnectionSettings(store).encodingMode)
        }
    }

    @Test
    fun `an unknown stored encoding mode gives automatic, never a failure`() {
        for (garbage in listOf("", "RAW", "tight", "5", "null")) {
            assertEquals("« $garbage »", EncodingMode.AUTO, ConnectionSettings(MemoryStore(mapOf("encoding_mode" to garbage))).encodingMode)
        }
    }

    @Test
    fun `the encoding mode and the automatic reconnection do not disturb each other`() {
        val store = MemoryStore()
        ConnectionSettings(store).encodingMode = EncodingMode.RAW
        ConnectionSettings(store).autoReconnect = false

        assertEquals(EncodingMode.RAW, ConnectionSettings(store).encodingMode)
        assertFalse(ConnectionSettings(store).autoReconnect)
    }
}
