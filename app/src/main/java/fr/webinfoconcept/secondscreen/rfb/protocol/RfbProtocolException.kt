package fr.webinfoconcept.secondscreen.rfb.protocol

import java.io.IOException

/**
 * Erreurs de protocole RFB : le transport a fonctionné mais le serveur a envoyé
 * des données inattendues (SS-011). Distinctes de
 * [fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException].
 *
 * Les messages n'incluent jamais d'octets reçus du serveur (donnée non fiable).
 */
sealed class RfbProtocolException(message: String) : IOException(message) {

    /**
     * La bannière de version n'est pas au format `RFB xxx.yyy\n`. Cause la plus
     * fréquente : port qui n'appartient pas à un serveur VNC.
     */
    class InvalidBanner :
        RfbProtocolException("Réponse invalide : ce port ne semble pas être un serveur VNC (RFB)")

    /** Version RFB annoncée par le serveur inférieure à la plus ancienne supportée (3.3). */
    class UnsupportedVersion(val major: Int, val minor: Int) :
        RfbProtocolException("Version RFB non supportée : $major.$minor")
}
