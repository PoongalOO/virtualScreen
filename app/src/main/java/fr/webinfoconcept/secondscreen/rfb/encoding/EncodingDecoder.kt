package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket

/**
 * Décodeur d'un encodage RFB (SS-023, SS-024) : lit sur la socket les données d'**un**
 * rectangle et les écrit dans le [Framebuffer].
 *
 * Contrat :
 * - le rectangle (x, y, w, h) vient du réseau ; l'appelant l'a validé, mais un décodeur ne doit
 *   **jamais** lire ni écrire hors du framebuffer même s'il est appelé directement ;
 * - il lit exactement les octets de ce rectangle, ni plus ni moins, pour ne pas désaligner le flux ;
 * - il n'alloue pas par rectangle (buffers réutilisés, alloués une fois à la construction) ;
 * - sur erreur, le contenu du rectangle en cours dans le framebuffer est indéfini : la connexion
 *   est fermée et la reconnexion redemande un écran complet.
 *
 * Non thread-safe : utilisé par le seul thread I/O.
 */
interface EncodingDecoder {
    /** Numéro d'encodage RFB géré, voir [fr.webinfoconcept.secondscreen.rfb.protocol.Encoding]. */
    val encoding: Int

    /**
     * @throws fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException.RectangleOutOfBounds
     *   rectangle hors du framebuffer (rien n'est lu).
     * @throws fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException erreur réseau/timeout/EOF.
     */
    fun decode(socket: RfbSocket, x: Int, y: Int, w: Int, h: Int, framebuffer: Framebuffer)
}
