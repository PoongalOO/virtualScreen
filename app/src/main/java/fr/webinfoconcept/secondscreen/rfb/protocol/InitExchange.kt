package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import java.io.IOException

/** Contenu du message `ServerInit` : ce que le serveur expose, après validation. */
data class ServerInit(
    val width: Int,
    val height: Int,
    /** Format natif du serveur, à titre informatif : le client impose le sien (SetPixelFormat, SS-021). */
    val pixelFormat: PixelFormat,
    /** Nom du bureau, assaini en ASCII imprimable (jamais un texte serveur brut). */
    val desktopName: String
)

/**
 * `ClientInit` puis `ServerInit` (SS-013), dernière étape du handshake après
 * [SecurityNegotiation].
 *
 * ```text
 * ClientInit : U8 shared-flag
 * ServerInit : U16 largeur, U16 hauteur, PIXEL_FORMAT (16 o), U32 longueur du nom, nom
 * ```
 *
 * Le serveur est une entrée non fiable, tout est validé **avant** d'allouer ou de lire la suite :
 * 1. dimensions non nulles et bornées ([MAX_DIMENSION], [MAX_PIXELS]), produit calculé en `Long` ;
 * 2. `PIXEL_FORMAT` cohérent ([PixelFormat.parse]) ;
 * 3. longueur du nom ≤ [MAX_NAME_LENGTH]. Contrairement aux textes de raison (SS-012), on ne peut
 *    pas tronquer : le flux continue après le nom, en lire moins désalignerait tous les messages
 *    suivants. Un nom trop long est donc refusé.
 *
 * Toute erreur ferme la socket avant d'être relancée.
 */
object InitExchange {
    /** Plus grande largeur ou hauteur acceptée : celle du [Framebuffer], qui porte le budget mémoire. */
    const val MAX_DIMENSION = Framebuffer.MAX_DIMENSION

    /** Plus grande surface acceptée (1920×1200) : celle du [Framebuffer], justification mémoire incluse. */
    const val MAX_PIXELS = Framebuffer.MAX_PIXELS

    /** Plus longue longueur annoncée acceptée pour le nom du bureau. */
    const val MAX_NAME_LENGTH = 1024

    private const val HEADER_LENGTH = 24 // 2 + 2 + 16 + 4

    /**
     * Envoie `ClientInit` puis lit et valide `ServerInit`. Bloquant : ne pas appeler depuis le
     * thread UI.
     *
     * @param shared `true` : session partagée, les autres clients déjà connectés restent connectés
     *   (recommandé) ; `false` : demande l'accès exclusif, certains serveurs déconnectent alors les autres.
     * @throws RfbProtocolException.InvalidFramebufferSize
     * @throws RfbProtocolException.InvalidPixelFormat
     * @throws RfbProtocolException.InvalidDesktopName
     * @throws fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException erreur réseau/timeout/EOF.
     */
    fun perform(socket: RfbSocket, shared: Boolean = true): ServerInit {
        try {
            socket.write(byteArrayOf(if (shared) 1 else 0), 0, 1)

            val headerBytes = ByteArray(HEADER_LENGTH)
            socket.readFully(headerBytes, 0, HEADER_LENGTH)
            val header = parseHeader(headerBytes)

            val nameBytes = ByteArray(header.nameLength) // borné par MAX_NAME_LENGTH dans parseHeader
            socket.readFully(nameBytes, 0, nameBytes.size)

            return ServerInit(header.width, header.height, header.pixelFormat, sanitizeServerText(nameBytes))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    internal class Header(val width: Int, val height: Int, val pixelFormat: PixelFormat, val nameLength: Int)

    /**
     * Valide les 24 premiers octets de `ServerInit` dans l'ordre : taille, format de pixels,
     * longueur du nom.
     *
     * @param bytes exactement [HEADER_LENGTH] octets (erreur de programmation sinon).
     */
    internal fun parseHeader(bytes: ByteArray): Header {
        require(bytes.size == HEADER_LENGTH) { "en-tête de ${bytes.size} octets au lieu de $HEADER_LENGTH" }

        val width = bytes.u16At(0)
        val height = bytes.u16At(2)
        // Long : 65535 × 65535 dépasse Int.MAX_VALUE.
        val pixels = width.toLong() * height.toLong()
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION || pixels > MAX_PIXELS) {
            throw RfbProtocolException.InvalidFramebufferSize(width, height)
        }

        val pixelFormat = PixelFormat.parse(bytes, 4)

        val nameLength = bytes.u32At(20)
        if (nameLength > MAX_NAME_LENGTH) throw RfbProtocolException.InvalidDesktopName(nameLength)

        return Header(width, height, pixelFormat, nameLength.toInt())
    }
}
