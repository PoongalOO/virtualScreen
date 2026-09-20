package fr.webinfoconcept.secondscreen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation de l'hôte et du port saisis (SS-050). */
class ConnectionParamsTest {

    @Test
    fun `valid hosts`() {
        for (host in listOf("192.168.1.10", "pc", "mon-pc.local", "PC_BUREAU", "a", "srv1.example.com", "10.0.0.1")) {
            assertTrue(host, ConnectionParams.isValidHost(host))
        }
    }

    @Test
    fun `invalid hosts are refused before any DNS lookup`() {
        for (host in listOf(
            "", " ", "pc ", " pc", "http://pc", "pc:5900", "pc/x", ".pc", "pc.", "-pc", "pc\n", "pc;rm", "pc?x", "é.local", "a b",
            "a".repeat(254)
        )) {
            assertFalse("« $host »", ConnectionParams.isValidHost(host))
        }
        assertTrue(ConnectionParams.isValidHost("a".repeat(253)))
    }

    @Test
    fun `port parsing`() {
        assertEquals(5900, ConnectionParams.parsePort(""))
        assertEquals(5900, ConnectionParams.parsePort("   "))
        assertEquals(5901, ConnectionParams.parsePort("5901"))
        assertEquals(5901, ConnectionParams.parsePort(" 5901 "))
        assertEquals(1, ConnectionParams.parsePort("1"))
        assertEquals(65535, ConnectionParams.parsePort("65535"))
        for (bad in listOf("0", "65536", "-1", "12a", "5 900", "99999999999", "+80", "５９００", "1e3", "5900.0")) {
            assertNull("« $bad »", ConnectionParams.parsePort(bad))
        }
    }

    @Test
    fun `params are validated at construction`() {
        assertThrows(IllegalArgumentException::class.java) { ConnectionParams("", 5900) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionParams("pc", 0) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionParams("pc", 70000) }
        assertEquals(5900, ConnectionParams("pc").port)
        assertTrue(ConnectionParams("pc").shared)
    }

    @Test
    fun `there is no password field to leak and toString shows host and port only`() {
        val params = ConnectionParams("192.168.1.10", 5901)

        assertEquals("192.168.1.10:5901", params.toString())
        val fields = ConnectionParams::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("aucun champ de secret : $fields", fields.none { "pass" in it || "secret" in it || "pwd" in it })
    }
}
