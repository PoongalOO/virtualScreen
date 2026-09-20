package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.Encoding
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.protocol.u16At
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket

/**
 * Encodage CopyRect (SS-025) : le serveur indique qu'un rectangle est identique à une zone déjà
 * présente dans le framebuffer. Les données sont 4 octets : `U16 src-x`, `U16 src-y`. Aucun pixel
 * ne circule : c'est l'encodage idéal pour un défilement ou un déplacement de fenêtre.
 *
 * - **Chevauchement** : la source et la destination peuvent se chevaucher (défilement d'un terminal
 *   de quelques lignes) ; [Framebuffer.copyRect] se comporte comme un `memmove`, sans corruption.
 * - **Ordre des rectangles** : les rectangles d'un `FramebufferUpdate` s'appliquent dans l'ordre ; la
 *   source d'un CopyRect est le framebuffer tel qu'il est **à ce moment-là**, rectangles précédents de la
 *   même mise à jour inclus.
 * - **Source non fiable** : `src-x`/`src-y` viennent du réseau ; la source **et** la destination sont
 *   validées avant la moindre écriture. Le décodeur lit toujours les 4 octets (pour rester aligné) avant de
 *   valider la source, mais ne lit rien si la destination est hors écran.
 * - Aucune allocation : un buffer de 4 octets alloué une fois.
 */
class CopyRectDecoder : EncodingDecoder {

    override val encoding: Int = Encoding.COPY_RECT

    private val source = ByteArray(SOURCE_LENGTH)

    override fun decode(socket: RfbSocket, x: Int, y: Int, w: Int, h: Int, framebuffer: Framebuffer) {
        // Avant toute lecture : une destination invalide ne doit pas consommer de données du serveur.
        if (!framebuffer.contains(x, y, w, h)) throw RfbProtocolException.RectangleOutOfBounds(x, y, w, h)

        socket.readFully(source, 0, SOURCE_LENGTH)
        val srcX = source.u16At(0)
        val srcY = source.u16At(2)

        // Valide aussi la source (RectangleOutOfBounds avec les coordonnées de la source).
        framebuffer.copyRect(srcX, srcY, w, h, x, y)
    }

    private companion object {
        const val SOURCE_LENGTH = 4 // U16 src-x + U16 src-y
    }
}
