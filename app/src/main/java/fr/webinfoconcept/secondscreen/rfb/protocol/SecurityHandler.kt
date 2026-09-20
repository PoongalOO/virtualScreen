package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket

/** Numéros de types de sécurité RFB (RFC 6143 §7.1.2). */
object SecurityType {
    /** Type 0 en RFB 3.3 : le serveur refuse la connexion (suivi d'une raison). */
    const val INVALID = 0
    const val NONE = 1
    const val VNC_AUTH = 2
}

/**
 * Un type de sécurité que le client sait exécuter. Point d'extension de la
 * négociation : `None` est fourni ([NoneSecurity]), VNC Authentication (SS-014)
 * s'ajoute en implémentant cette interface, sans toucher à [SecurityNegotiation].
 *
 * Les handlers sont fournis par ordre de **préférence du client** ; le premier
 * que le serveur propose est utilisé.
 */
interface SecurityHandler {
    /** Numéro du type de sécurité RFB géré, voir [SecurityType]. */
    val type: Int

    /**
     * Exécute l'échange propre au type, une fois le type choisi (et annoncé au
     * serveur en RFB 3.8) et avant la lecture du `SecurityResult`. Bloquant ; lève
     * une [java.io.IOException] en cas d'échec (la socket est alors fermée par
     * l'appelant). Une implémentation ne doit **jamais** journaliser ni conserver
     * de secret (mot de passe, challenge, réponse).
     */
    fun authenticate(socket: RfbSocket)
}

/** Type `None` : aucune authentification, réservé à un LAN de développement de confiance. */
object NoneSecurity : SecurityHandler {
    override val type: Int = SecurityType.NONE

    override fun authenticate(socket: RfbSocket) = Unit
}
