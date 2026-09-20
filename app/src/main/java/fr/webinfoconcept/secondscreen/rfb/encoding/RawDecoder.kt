package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.Encoding
import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket

/**
 * Encodage RAW (SS-024) : les pixels du rectangle, ligne par ligne, `w × h × bytesPerPixel`
 * octets, dans le format de pixels en vigueur (celui imposé par `SetPixelFormat`, SS-021).
 *
 * **Ligne par ligne, sans buffer de rectangle** : un rectangle peut couvrir tout l'écran
 * (jusqu'à 9 Mio pour 1920×1200). Chaque ligne est lue dans un petit buffer, convertie en ARGB puis
 * copiée dans le framebuffer : deux buffers de `maxWidth` pixels alloués une fois, aucune
 * allocation par rectangle ni par mise à jour.
 *
 * **Validation avant lecture** : un rectangle hors du framebuffer est refusé avant d'avoir lu le
 * moindre octet de pixels (la taille des données, `w × h × bytesPerPixel`, est donc bornée par
 * la taille du framebuffer ; elle ne peut pas déborder).
 *
 * Deux chemins, qui doivent produire exactement les mêmes pixels (un test l'impose) :
 * - **rapide** pour [PixelFormat.XRGB_8888_LE] : `[bleu, vert, rouge, inutilisé]` -> `0xFF000000 | 0x00RRGGBB` ;
 * - **générique** pour tout autre format à couleurs vraies, via [PixelFormat.decodePixel]
 *   (référence, plus lente) : garde-fou si le format en vigueur n'est pas celui demandé.
 *
 * @param pixelFormat format des pixels reçus, à couleurs vraies (une palette n'est pas gérée).
 * @param maxWidth plus grande largeur de rectangle décodable : la largeur du framebuffer.
 */
class RawDecoder(
    private val pixelFormat: PixelFormat = PixelFormat.XRGB_8888_LE,
    private val maxWidth: Int = Framebuffer.NOMINAL_WIDTH
) : EncodingDecoder {

    override val encoding: Int = Encoding.RAW

    private val bytesPerPixel = pixelFormat.bytesPerPixel
    private val fastPath = pixelFormat == PixelFormat.XRGB_8888_LE

    private val rowBytes: ByteArray
    private val rowPixels: IntArray

    init {
        require(pixelFormat.trueColour) { "RAW : format à palette non supporté" }
        require(maxWidth in 1..Framebuffer.MAX_DIMENSION) { "maxWidth hors limites : $maxWidth" }
        rowBytes = ByteArray(maxWidth * bytesPerPixel)
        rowPixels = IntArray(maxWidth)
    }

    override fun decode(socket: RfbSocket, x: Int, y: Int, w: Int, h: Int, framebuffer: Framebuffer) {
        // Avant toute lecture : un rectangle invalide ne doit pas consommer de données du serveur.
        if (!framebuffer.contains(x, y, w, h)) throw RfbProtocolException.RectangleOutOfBounds(x, y, w, h)
        require(w <= maxWidth) { "rectangle plus large que le décodeur : $w > $maxWidth" }
        if (w == 0 || h == 0) return

        val rowLength = w * bytesPerPixel // <= maxWidth * 4 : ne déborde pas
        for (row in 0 until h) {
            socket.readFully(rowBytes, 0, rowLength)
            if (fastPath) convertFast(w) else convertGeneric(w)
            framebuffer.writeRect(x, y + row, w, 1, rowPixels)
        }
    }

    /** `[B, G, R, X]` -> `0xFFRRGGBB`, l'octet inutilisé est ignoré. */
    private fun convertFast(w: Int) {
        val bytes = rowBytes
        val pixels = rowPixels
        var s = 0
        for (i in 0 until w) {
            pixels[i] = OPAQUE or
                (bytes[s].toInt() and 0xFF) or
                ((bytes[s + 1].toInt() and 0xFF) shl 8) or
                ((bytes[s + 2].toInt() and 0xFF) shl 16)
            s += 4
        }
    }

    private fun convertGeneric(w: Int) {
        var s = 0
        for (i in 0 until w) {
            rowPixels[i] = pixelFormat.decodePixel(rowBytes, s)
            s += bytesPerPixel
        }
    }

    private companion object {
        const val OPAQUE: Int = 0xFF000000.toInt()
    }
}
