package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import java.io.IOException

/**
 * Négociation de sécurité RFB (SS-012), deuxième étape du handshake, après
 * [ProtocolVersion.negotiate].
 *
 * | | RFB 3.8 | RFB 3.3 |
 * |---|---|---|
 * | Types proposés | U8 `n` puis `n` U8 ; `n = 0` : refus + raison | un seul U32 imposé ; `0` : refus + raison |
 * | Choix du client | 1 octet | aucun |
 * | `SecurityResult` | toujours, y compris pour `None` | sauf pour `None` |
 * | Raison si échec | oui (U32 longueur + texte) | non |
 *
 * Le serveur est une entrée non fiable : nombre de types borné par un U8, texte de
 * raison lu sur au plus [MAX_REASON_LENGTH] octets quelle que soit la longueur annoncée,
 * puis assaini. Toute erreur ferme la socket avant d'être relancée.
 */
object SecurityNegotiation {
    /** Longueur maximale lue pour un texte de raison. Le reste est ignoré (la connexion est fermée). */
    const val MAX_REASON_LENGTH = 256

    private const val RESULT_OK = 0L
    private const val RESULT_FAILED = 1L

    /**
     * Négocie et exécute l'authentification. Bloquant : ne pas appeler depuis le thread UI.
     *
     * @param handlers types acceptés par le client, **par ordre de préférence** (non vide).
     * @return le handler effectivement utilisé.
     * @throws RfbProtocolException.ConnectionRejected refus avant authentification.
     * @throws RfbProtocolException.NoSupportedSecurityType aucun type commun.
     * @throws RfbProtocolException.AuthenticationFailed `SecurityResult` négatif.
     * @throws RfbProtocolException.InvalidSecurityResult `SecurityResult` inconnu.
     * @throws RfbTransportException erreur réseau/timeout/EOF.
     */
    fun negotiate(socket: RfbSocket, version: RfbVersion, handlers: List<SecurityHandler>): SecurityHandler {
        require(handlers.isNotEmpty()) { "au moins un SecurityHandler est requis" }
        require(handlers.all { it.type in 1..255 }) { "type de sécurité hors 1..255" }

        try {
            val reader = RfbReader(socket)
            val handler = when (version) {
                RfbVersion.V3_3 -> selectV33(reader, handlers)
                RfbVersion.V3_8 -> selectV38(reader, socket, handlers)
            }

            handler.authenticate(socket)

            // 3.8 : SecurityResult pour tous les types. 3.3 : sauf pour None.
            if (version == RfbVersion.V3_8 || handler.type != SecurityType.NONE) {
                checkSecurityResult(reader, withReason = version == RfbVersion.V3_8)
            }
            return handler
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    private fun selectV33(reader: RfbReader, handlers: List<SecurityHandler>): SecurityHandler {
        val type = reader.readU32()
        if (type == SecurityType.INVALID.toLong()) throw RfbProtocolException.ConnectionRejected(readReason(reader))
        return handlers.firstOrNull { it.type.toLong() == type }
            ?: throw RfbProtocolException.NoSupportedSecurityType(listOf(type))
    }

    private fun selectV38(reader: RfbReader, socket: RfbSocket, handlers: List<SecurityHandler>): SecurityHandler {
        val count = reader.readU8() // 0..255 : borné par construction
        if (count == 0) throw RfbProtocolException.ConnectionRejected(readReason(reader))

        val offered = reader.readBytes(count).map { it.toInt() and 0xFF }
        val handler = handlers.firstOrNull { it.type in offered }
            ?: throw RfbProtocolException.NoSupportedSecurityType(offered.map { it.toLong() })

        socket.write(byteArrayOf(handler.type.toByte()), 0, 1)
        return handler
    }

    private fun checkSecurityResult(reader: RfbReader, withReason: Boolean) {
        when (reader.readU32()) {
            RESULT_OK -> Unit
            RESULT_FAILED ->
                throw RfbProtocolException.AuthenticationFailed(if (withReason) readReason(reader) else "")
            else -> throw RfbProtocolException.InvalidSecurityResult()
        }
    }

    /**
     * Lit un texte de raison (U32 longueur + octets). La longueur annoncée est bornée
     * avant toute allocation. Un serveur qui coupe ou se tait avant d'avoir envoyé la
     * raison (non conforme mais fréquent après un échec) donne une raison vide plutôt
     * qu'une erreur réseau qui masquerait le vrai motif du refus.
     */
    private fun readReason(reader: RfbReader): String = try {
        val announced = reader.readU32()
        val toRead = minOf(announced, MAX_REASON_LENGTH.toLong()).toInt()
        sanitizeServerText(reader.readBytes(toRead))
    } catch (e: RfbTransportException.EndOfStream) {
        ""
    } catch (e: RfbTransportException.ReadTimeout) {
        ""
    }
}
