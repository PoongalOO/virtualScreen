package fr.webinfoconcept.secondscreen.rfb.transport

import java.io.IOException

/**
 * Erreurs typées du transport TCP (SS-010).
 *
 * Toute défaillance de [RfbSocket] est convertie en l'un de ces types afin que
 * la couche supérieure (ConnectionController, SS-053) puisse afficher un message
 * compréhensible sans analyser des messages d'exceptions dépendants de la
 * plateforme. Les messages ne contiennent aucune donnée reçue du serveur.
 */
sealed class RfbTransportException(message: String, cause: Throwable? = null) :
    IOException(message, cause) {

    /** Le nom d'hôte n'a pas pu être résolu. */
    class UnknownHost(val host: String, cause: Throwable? = null) :
        RfbTransportException("Hôte inconnu : $host", cause)

    /** Le délai de connexion TCP est dépassé. */
    class ConnectTimeout(val timeoutMs: Int, cause: Throwable? = null) :
        RfbTransportException("Délai de connexion dépassé (${timeoutMs} ms)", cause)

    /** Le serveur a activement refusé la connexion (aucun service sur ce port). */
    class ConnectionRefused(cause: Throwable? = null) :
        RfbTransportException("Connexion refusée", cause)

    /** Autre échec de connexion (réseau injoignable, pas de route...). */
    class ConnectFailed(cause: Throwable? = null) :
        RfbTransportException("Connexion impossible", cause)

    /**
     * Aucune donnée reçue dans le délai de lecture. La socket n'est **pas**
     * fermée : si [bytesRead] vaut 0 on est à une frontière de message et l'appelant
     * peut réessayer ; sinon le flux est désaligné et l'appelant doit fermer.
     */
    class ReadTimeout(val bytesRead: Int, val bytesExpected: Int, cause: Throwable? = null) :
        RfbTransportException("Délai de lecture dépassé ($bytesRead/$bytesExpected octets)", cause)

    /** Le serveur a fermé la connexion avant la fin de la lecture demandée. */
    class EndOfStream(val bytesRead: Int, val bytesExpected: Int) :
        RfbTransportException("Connexion fermée par le serveur ($bytesRead/$bytesExpected octets)")

    /** La socket a été fermée localement (ou n'est pas connectée). */
    class Closed(cause: Throwable? = null) :
        RfbTransportException("Socket fermée", cause)

    /** Autre erreur d'entrée/sortie (réseau coupé, reset...). */
    class Io(cause: Throwable? = null) :
        RfbTransportException("Erreur réseau", cause)
}
