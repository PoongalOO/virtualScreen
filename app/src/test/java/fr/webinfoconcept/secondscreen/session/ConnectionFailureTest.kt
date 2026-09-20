package fr.webinfoconcept.secondscreen.session

import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Classement des erreurs en causes que l'utilisateur comprend (SS-053). */
class ConnectionFailureTest {

    private fun kind(e: Throwable, phase: Phase = Phase.NEGOTIATING, password: Boolean = false) =
        ConnectionFailure.classify(e, phase, password).kind

    @Test
    fun `transport errors while connecting`() {
        assertEquals(FailureKind.UNKNOWN_HOST, kind(RfbTransportException.UnknownHost("pc"), Phase.CONNECTING))
        assertEquals(FailureKind.CONNECT_TIMEOUT, kind(RfbTransportException.ConnectTimeout(5000), Phase.CONNECTING))
        assertEquals(FailureKind.CONNECTION_REFUSED, kind(RfbTransportException.ConnectionRefused(), Phase.CONNECTING))
        assertEquals(FailureKind.NETWORK_UNREACHABLE, kind(RfbTransportException.ConnectFailed(), Phase.CONNECTING))
    }

    @Test
    fun `a read timeout means a silent server during the handshake and a dead link during a session`() {
        val timeout = RfbTransportException.ReadTimeout(0, 1)

        assertEquals(FailureKind.HANDSHAKE_TIMEOUT, kind(timeout, Phase.NEGOTIATING))
        assertEquals(FailureKind.NETWORK_LOST, kind(timeout, Phase.RUNNING))
    }

    @Test
    fun `end of stream, io and closed are a lost connection`() {
        for (e in listOf(RfbTransportException.EndOfStream(0, 1), RfbTransportException.Io(), RfbTransportException.Closed())) {
            assertEquals(FailureKind.CONNECTION_LOST, kind(e, Phase.RUNNING))
            assertEquals(FailureKind.CONNECTION_LOST, kind(e, Phase.NEGOTIATING))
        }
    }

    @Test
    fun `protocol errors of the handshake`() {
        assertEquals(FailureKind.NOT_A_VNC_SERVER, kind(RfbProtocolException.InvalidBanner()))
        assertEquals(FailureKind.UNSUPPORTED_VERSION, kind(RfbProtocolException.UnsupportedVersion(3, 2)))
        assertEquals(FailureKind.AUTH_FAILED, kind(RfbProtocolException.AuthenticationFailed("nope"), password = true))
        assertEquals(FailureKind.UNSUPPORTED_SERVER_SIZE, kind(RfbProtocolException.InvalidFramebufferSize(60000, 60000)))
        assertEquals(FailureKind.PROTOCOL_ERROR, kind(RfbProtocolException.InvalidSecurityResult()))
        assertEquals(FailureKind.PROTOCOL_ERROR, kind(RfbProtocolException.UnsupportedServerMessage(99), Phase.RUNNING))
        assertEquals(FailureKind.PROTOCOL_ERROR, kind(RfbProtocolException.RectangleOutOfBounds(0, 0, 9, 9), Phase.RUNNING))
        assertEquals(FailureKind.LOCAL_ERROR, kind(RfbProtocolException.DesUnavailable(RuntimeException())))
    }

    @Test
    fun `a server that wants a password when none was typed is PASSWORD_REQUIRED, otherwise no compatible security`() {
        val vncAuthOnly = RfbProtocolException.NoSupportedSecurityType(listOf(2L))

        assertEquals(FailureKind.PASSWORD_REQUIRED, kind(vncAuthOnly, password = false))
        assertEquals(FailureKind.NO_COMPATIBLE_SECURITY, kind(vncAuthOnly, password = true))
        assertEquals(FailureKind.NO_COMPATIBLE_SECURITY, kind(RfbProtocolException.NoSupportedSecurityType(listOf(16L, 19L)), password = false))
    }

    @Test
    fun `the server reason is kept for a rejection and a failed authentication, nothing else`() {
        val rejected = ConnectionFailure.classify(RfbProtocolException.ConnectionRejected("Too many"), Phase.NEGOTIATING, false)
        val bad = ConnectionFailure.classify(RfbProtocolException.AuthenticationFailed("Bad"), Phase.NEGOTIATING, true)

        assertEquals(FailureKind.SERVER_REJECTED, rejected.kind)
        assertEquals("Too many", rejected.serverReason)
        assertEquals("Bad", bad.serverReason)
        assertEquals("", ConnectionFailure.classify(RfbTransportException.Io(), Phase.RUNNING, false).serverReason)
    }

    @Test
    fun `an illegal argument is an invalid password when one was given, a local error otherwise`() {
        assertEquals(FailureKind.PASSWORD_INVALID, kind(IllegalArgumentException("x"), password = true))
        assertEquals(FailureKind.LOCAL_ERROR, kind(IllegalArgumentException("x"), password = false))
        assertEquals(FailureKind.LOCAL_ERROR, kind(IllegalStateException("x")))
        assertEquals(FailureKind.LOCAL_ERROR, kind(IOException("x")))
    }

    @Test
    fun `password related failures are flagged so the screen can refocus the password field`() {
        assertTrue(ConnectionFailure(FailureKind.AUTH_FAILED, Phase.NEGOTIATING).isPasswordProblem)
        assertTrue(ConnectionFailure(FailureKind.PASSWORD_REQUIRED, Phase.NEGOTIATING).isPasswordProblem)
        assertTrue(ConnectionFailure(FailureKind.PASSWORD_INVALID, Phase.NEGOTIATING).isPasswordProblem)
        assertFalse(ConnectionFailure(FailureKind.CONNECTION_REFUSED, Phase.CONNECTING).isPasswordProblem)
    }

    @Test
    fun `toString never carries the server text`() {
        val failure = ConnectionFailure(FailureKind.SERVER_REJECTED, Phase.NEGOTIATING, "secret-looking text")

        assertFalse(failure.toString().contains("secret"))
    }

    @Test
    fun `every failure kind is classified by at least one input`() {
        // Garde-fou : ajouter un FailureKind sans savoir le produire serait du code mort et un message jamais vu.
        val produced = setOf(
            kind(RfbTransportException.UnknownHost("h"), Phase.CONNECTING), kind(RfbTransportException.ConnectTimeout(1), Phase.CONNECTING),
            kind(RfbTransportException.ConnectionRefused(), Phase.CONNECTING), kind(RfbTransportException.ConnectFailed(), Phase.CONNECTING),
            kind(RfbProtocolException.InvalidBanner()), kind(RfbProtocolException.UnsupportedVersion(3, 0)),
            kind(RfbProtocolException.ConnectionRejected("")), kind(RfbProtocolException.NoSupportedSecurityType(listOf(2L))),
            kind(IllegalArgumentException(), password = true), kind(RfbProtocolException.NoSupportedSecurityType(listOf(9L))),
            kind(RfbProtocolException.AuthenticationFailed("")), kind(RfbTransportException.ReadTimeout(0, 1), Phase.NEGOTIATING),
            kind(RfbProtocolException.InvalidFramebufferSize(0, 0)), kind(RfbTransportException.Io()),
            kind(RfbTransportException.ReadTimeout(0, 1), Phase.RUNNING), kind(RfbProtocolException.InvalidSecurityResult()),
            kind(IllegalStateException())
        )

        assertEquals(FailureKind.values().toSet(), produced)
    }
}
