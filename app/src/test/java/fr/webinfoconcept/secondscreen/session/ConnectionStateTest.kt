package fr.webinfoconcept.secondscreen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Machine à états de la connexion (SS-054) : transitions autorisées, et seulement celles-là. */
class ConnectionStateTest {
    private val S = ConnectionState.values().toList()

    @Test
    fun `the documented transitions are the only legal ones`() {
        val legal = setOf(
            "DISCONNECTED>CONNECTING", "DISCONNECTED>RECONNECTING",
            "CONNECTING>NEGOTIATING", "CONNECTING>ERROR", "CONNECTING>DISCONNECTED",
            "RECONNECTING>NEGOTIATING", "RECONNECTING>ERROR", "RECONNECTING>DISCONNECTED",
            "NEGOTIATING>CONNECTED", "NEGOTIATING>ERROR", "NEGOTIATING>DISCONNECTED",
            "CONNECTED>ERROR", "CONNECTED>DISCONNECTED",
            "ERROR>CONNECTING", "ERROR>RECONNECTING", "ERROR>DISCONNECTED"
        )
        val actual = S.flatMap { from -> S.filter { from.canTransitionTo(it) }.map { "$from>$it" } }.toSet()

        assertEquals(legal, actual)
    }

    @Test
    fun `a session can never skip the negotiation`() {
        assertFalse(ConnectionState.CONNECTING.canTransitionTo(ConnectionState.CONNECTED))
        assertFalse(ConnectionState.RECONNECTING.canTransitionTo(ConnectionState.CONNECTED))
        assertFalse(ConnectionState.DISCONNECTED.canTransitionTo(ConnectionState.CONNECTED))
        assertFalse(ConnectionState.DISCONNECTED.canTransitionTo(ConnectionState.NEGOTIATING))
    }

    @Test
    fun `a connection can start only from disconnected or error`() {
        assertEquals(
            setOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR),
            S.filter { it.canStartConnection }.toSet()
        )
        assertEquals(
            setOf(ConnectionState.CONNECTING, ConnectionState.NEGOTIATING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING),
            S.filter { it.isActive }.toSet()
        )
    }

    @Test
    fun `active and startable states never overlap`() {
        assertTrue(S.none { it.isActive && it.canStartConnection })
    }
}
