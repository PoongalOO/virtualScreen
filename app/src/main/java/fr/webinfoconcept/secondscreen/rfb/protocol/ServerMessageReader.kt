package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.perf.PerfStats
import fr.webinfoconcept.secondscreen.rfb.encoding.CopyRectDecoder
import fr.webinfoconcept.secondscreen.rfb.encoding.EncodingDecoder
import fr.webinfoconcept.secondscreen.rfb.encoding.HextileDecoder
import fr.webinfoconcept.secondscreen.rfb.encoding.RawDecoder
import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import java.io.IOException

/**
 * Lit les messages serveur un par un (SS-023) et applique les `FramebufferUpdate` au
 * [Framebuffer], via un [EncodingDecoder] par encodage (RAW pour SS-024).
 *
 * ```text
 * FramebufferUpdate : U8 type = 0, 1 octet de padding, U16 n, puis n rectangles :
 *                     U16 x, U16 y, U16 largeur, U16 hauteur, S32 encodage, données de l'encodage
 * Bell              : U8 type = 2
 * ServerCutText     : U8 type = 3, 3 octets de padding, U32 longueur, texte
 * ```
 *
 * **Le serveur est une entrée non fiable** :
 * - chaque rectangle est validé (dans le framebuffer) **avant** de lire ses données ;
 * - un encodage sans décodeur, un type de message inconnu ou un `SetColourMapEntries` (jamais
 *   envoyé à un client en couleurs vraies) donnent une erreur typée, jamais un flux désaligné ;
 * - le texte de `ServerCutText` est consommé sans être conservé, par blocs de [SKIP_BUFFER_SIZE]
 *   octets, et refusé au-delà de [MAX_CUT_TEXT_LENGTH] ;
 * - le nombre de rectangles est un U16 : il ne pilote aucune allocation.
 *
 * **Erreurs** : pendant un message, toute erreur (EOF, timeout, protocole) ferme la socket, car le
 * flux est désaligné. Seule exception : un timeout **avant le premier octet** d'un message
 * (frontière de message) est récupérable : la socket reste ouverte et l'appel peut être refait.
 * Un EOF à cet endroit (`EndOfStream.bytesRead == 0`) est la fermeture normale de la connexion par
 * le serveur.
 *
 * Après une erreur au milieu d'un `FramebufferUpdate`, les rectangles déjà décodés restent
 * appliqués et le contenu du rectangle en cours est indéfini ; la reconnexion (SS-054) redemande
 * un écran complet.
 *
 * Non thread-safe : utilisé par le seul thread I/O. Bloquant : jamais sur le thread UI.
 *
 * @param decoders décodeurs disponibles ; au plus un par encodage. Par défaut ceux de
 *   [defaultDecoders] (Hextile, CopyRect, RAW), ce qui correspond à [Encoding.ADVERTISED] : on ne
 *   doit annoncer que ce qu'on sait décoder (un test le vérifie).
 * @param listener notifié après chaque rectangle décodé.
 * @param perf compteurs de performance (SS-060) ou `null` ; si les mesures sont désactivées, un `FramebufferUpdate` ne coûte
 *   que la lecture d'un booléen de plus.
 */
class ServerMessageReader(
    private val socket: RfbSocket,
    private val framebuffer: Framebuffer,
    pixelFormat: PixelFormat = PixelFormat.XRGB_8888_LE,
    decoders: List<EncodingDecoder> = defaultDecoders(pixelFormat, framebuffer.width),
    private val listener: RectangleListener? = null,
    private val perf: PerfStats? = null
) {
    private val decoderList: Array<EncodingDecoder> = decoders.toTypedArray()

    // Buffers réutilisés : aucune allocation par message ni par rectangle.
    private val header = ByteArray(RECTANGLE_HEADER_LENGTH)
    private val skipBuffer = ByteArray(SKIP_BUFFER_SIZE)

    init {
        require(decoders.map { it.encoding }.toSet().size == decoders.size) { "deux décodeurs pour un même encodage" }
    }

    /** `true` si un décodeur existe pour [encoding] : condition pour l'annoncer dans `SetEncodings`. */
    fun supports(encoding: Int): Boolean = decoderList.any { it.encoding == encoding }

    /**
     * Lit et traite le prochain message. Bloquant.
     *
     * @throws RfbProtocolException type de message ou encodage non supporté, rectangle hors écran,
     *   texte de presse-papiers trop long.
     * @throws fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException erreur réseau,
     *   timeout ou EOF. `ReadTimeout` avec `bytesRead == 0` au premier octet : socket ouverte.
     */
    fun readMessage(): ServerMessage {
        // Frontière de message : hors du try, pour qu'un timeout sans octet reste récupérable.
        socket.readFully(header, 0, 1)
        val type = header[0].toInt() and 0xFF

        try {
            return when (type) {
                TYPE_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
                TYPE_BELL -> ServerMessage.Bell
                TYPE_SERVER_CUT_TEXT -> skipCutText()
                else -> throw RfbProtocolException.UnsupportedServerMessage(type)
            }
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    private fun readFramebufferUpdate(): ServerMessage {
        val stats = perf?.takeIf { it.enabled }
        val startNs = if (stats != null) System.nanoTime() else 0L
        var pixels = 0L
        socket.readFully(header, 0, 3) // padding + U16 nombre de rectangles
        val rectangles = header.u16At(1)

        repeat(rectangles) {
            socket.readFully(header, 0, RECTANGLE_HEADER_LENGTH)
            val x = header.u16At(0)
            val y = header.u16At(2)
            val w = header.u16At(4)
            val h = header.u16At(6)
            val encoding = header.u32At(8).toInt() // S32 : les pseudo-encodages sont négatifs

            // Avant de chercher un décodeur ou de lire des pixels.
            if (!framebuffer.contains(x, y, w, h)) throw RfbProtocolException.RectangleOutOfBounds(x, y, w, h)

            val decoder = decoderList.firstOrNull { it.encoding == encoding }
                ?: throw RfbProtocolException.UnsupportedEncoding(encoding)
            decoder.decode(socket, x, y, w, h, framebuffer)
            listener?.onRectangle(x, y, w, h)
            pixels += w.toLong() * h
        }
        stats?.onUpdate(rectangles, pixels, System.nanoTime() - startNs)
        return ServerMessage.FramebufferUpdated(rectangles)
    }

    private fun skipCutText(): ServerMessage {
        socket.readFully(header, 0, 7) // 3 octets de padding + U32 longueur
        val length = header.u32At(3)
        if (length > MAX_CUT_TEXT_LENGTH) throw RfbProtocolException.CutTextTooLong(length)

        var remaining = length.toInt()
        while (remaining > 0) {
            val chunk = minOf(remaining, skipBuffer.size)
            socket.readFully(skipBuffer, 0, chunk)
            remaining -= chunk
        }
        return ServerMessage.CutTextIgnored(length.toInt())
    }

    companion object {
        /**
         * Les décodeurs de tous les encodages annoncés dans [Encoding.ADVERTISED] : Hextile (SS-026),
         * CopyRect (SS-025) et RAW (SS-024). Ajouter un encodage à `ADVERTISED` exige d'ajouter ici son décodeur.
         */
        fun defaultDecoders(pixelFormat: PixelFormat, maxWidth: Int): List<EncodingDecoder> = listOf(
            HextileDecoder(pixelFormat),
            CopyRectDecoder(),
            RawDecoder(pixelFormat, maxWidth)
        )

        const val TYPE_FRAMEBUFFER_UPDATE = 0
        const val TYPE_BELL = 2
        const val TYPE_SERVER_CUT_TEXT = 3

        /** Plus long texte de `ServerCutText` accepté : 1 Mio. Le reste est une erreur, pas une lecture sans fin. */
        const val MAX_CUT_TEXT_LENGTH = 1024L * 1024L

        private const val RECTANGLE_HEADER_LENGTH = 12 // 4 x U16 + S32
        private const val SKIP_BUFFER_SIZE = 4096
    }
}
