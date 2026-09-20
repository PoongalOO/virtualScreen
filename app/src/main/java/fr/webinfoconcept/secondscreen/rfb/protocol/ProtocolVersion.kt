package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import java.io.IOException

/** Versions du protocole RFB que le client sait parler (RFB_SPEC.md). */
enum class RfbVersion(val major: Int, val minor: Int) {
    V3_3(3, 3),
    V3_8(3, 8);

    /** Bannière ASCII de 12 octets à envoyer au serveur, ex. `RFB 003.008\n`. */
    fun bannerBytes(): ByteArray = ProtocolVersion.formatBanner(major, minor)
}

/** Version annoncée par le serveur dans sa bannière (valeurs brutes, non validées au-delà du format). */
data class ServerVersion(val major: Int, val minor: Int)

/**
 * Négociation de la version du protocole (SS-011), première étape du handshake RFB.
 *
 * Le serveur envoie 12 octets `RFB xxx.yyy\n` ; le client répond avec la plus
 * haute version qu'il supporte qui ne dépasse pas celle du serveur (RFC 6143 §7.1.1).
 *
 * | Serveur                         | Réponse du client |
 * |---------------------------------|-------------------|
 * | ≥ 3.8 (dont 3.889 d'Apple, 4.x) | 3.8               |
 * | 3.3 à 3.7                       | 3.3               |
 * | < 3.3                           | erreur            |
 *
 * 3.7 est volontairement rabattu sur 3.3 : seules 3.3 et 3.8 sont implémentées
 * (RFB_SPEC.md) et tout serveur 3.7 doit accepter 3.3.
 */
object ProtocolVersion {
    const val BANNER_LENGTH = 12

    private const val PREFIX = "RFB "
    private const val MIN_MAJOR = 3
    private const val MIN_MINOR = 3
    private const val MAX_MINOR_FOR_3_3 = 7

    /**
     * Lit la bannière du serveur, choisit la version et répond. Bloquant : ne pas
     * appeler depuis le thread UI. La lecture est limitée à exactement
     * [BANNER_LENGTH] octets, les octets suivants restent disponibles pour l'étape
     * suivante du handshake. Toute erreur ferme la socket avant d'être relancée.
     *
     * @throws fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException erreur réseau/timeout/EOF.
     * @throws RfbProtocolException bannière invalide ou version non supportée.
     */
    fun negotiate(socket: RfbSocket): RfbVersion {
        try {
            val banner = ByteArray(BANNER_LENGTH)
            socket.readFully(banner, 0, BANNER_LENGTH)
            val chosen = select(parseServerBanner(banner))
            socket.write(chosen.bannerBytes(), 0, BANNER_LENGTH)
            return chosen
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    /**
     * Décode `RFB xxx.yyy\n` : exactement 12 octets, chiffres ASCII stricts. Le
     * contenu vient du réseau et n'est jamais recopié dans les messages d'erreur.
     *
     * @param banner exactement [BANNER_LENGTH] octets (erreur de programmation sinon).
     * @throws RfbProtocolException.InvalidBanner format non conforme.
     */
    fun parseServerBanner(banner: ByteArray): ServerVersion {
        require(banner.size == BANNER_LENGTH) { "bannière de ${banner.size} octets au lieu de $BANNER_LENGTH" }

        for (i in PREFIX.indices) {
            if (banner[i].toInt() != PREFIX[i].code) throw RfbProtocolException.InvalidBanner()
        }
        val major = parseThreeDigits(banner, 4) ?: throw RfbProtocolException.InvalidBanner()
        if (banner[7].toInt() != '.'.code) throw RfbProtocolException.InvalidBanner()
        val minor = parseThreeDigits(banner, 8) ?: throw RfbProtocolException.InvalidBanner()
        if (banner[11].toInt() != '\n'.code) throw RfbProtocolException.InvalidBanner()
        return ServerVersion(major, minor)
    }

    /**
     * Choisit la version à parler selon celle du serveur (voir tableau ci-dessus).
     *
     * @throws RfbProtocolException.UnsupportedVersion serveur plus ancien que 3.3.
     */
    fun select(server: ServerVersion): RfbVersion = when {
        server.major > MIN_MAJOR -> RfbVersion.V3_8
        server.major == MIN_MAJOR && server.minor > MAX_MINOR_FOR_3_3 -> RfbVersion.V3_8
        server.major == MIN_MAJOR && server.minor >= MIN_MINOR -> RfbVersion.V3_3
        else -> throw RfbProtocolException.UnsupportedVersion(server.major, server.minor)
    }

    internal fun formatBanner(major: Int, minor: Int): ByteArray {
        require(major in 0..999 && minor in 0..999) { "version hors format : $major.$minor" }
        val out = ByteArray(BANNER_LENGTH)
        PREFIX.toByteArray(Charsets.US_ASCII).copyInto(out)
        writeThreeDigits(out, 4, major)
        out[7] = '.'.code.toByte()
        writeThreeDigits(out, 8, minor)
        out[11] = '\n'.code.toByte()
        return out
    }

    private fun parseThreeDigits(bytes: ByteArray, at: Int): Int? {
        var value = 0
        for (i in 0 until 3) {
            // Octet signé : tout octet >= 0x80 devient négatif et est rejeté ici.
            val digit = bytes[at + i].toInt() - '0'.code
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        return value
    }

    private fun writeThreeDigits(out: ByteArray, at: Int, value: Int) {
        out[at] = ('0'.code + value / 100).toByte()
        out[at + 1] = ('0'.code + value / 10 % 10).toByte()
        out[at + 2] = ('0'.code + value % 10).toByte()
    }
}
