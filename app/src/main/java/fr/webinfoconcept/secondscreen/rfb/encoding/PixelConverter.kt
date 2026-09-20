package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat

/**
 * Conversion des pixels reçus (dans le format en vigueur, SS-021) vers ARGB_8888, partagée par les
 * décodeurs RAW (SS-024) et Hextile (SS-026) pour ne pas dupliquer la règle.
 *
 * Deux chemins qui doivent produire exactement les mêmes pixels (un test l'impose) :
 * - **rapide** pour [PixelFormat.XRGB_8888_LE] : `[bleu, vert, rouge, inutilisé]` -> `0xFF000000 | 0x00RRGGBB` ;
 * - **générique** pour tout autre format à couleurs vraies, via [PixelFormat.decodePixel] (référence, plus lente).
 *
 * Sans état mutable ni allocation : partageable par un même thread sans précaution.
 *
 * @throws IllegalArgumentException format à palette (non géré : le client n'en demande jamais).
 */
internal class PixelConverter(private val format: PixelFormat) {

    init {
        require(format.trueColour) { "format à palette non supporté" }
    }

    val bytesPerPixel: Int = format.bytesPerPixel

    private val fast = format == PixelFormat.XRGB_8888_LE

    /** Le pixel de [bytesPerPixel] octets lu à [offset] dans [bytes], en ARGB opaque. */
    fun pixel(bytes: ByteArray, offset: Int): Int =
        if (fast) {
            OPAQUE or
                (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)
        } else {
            format.decodePixel(bytes, offset)
        }

    /** Convertit [count] pixels contigus lus dans [bytes] à partir de l'index 0 vers `out[0 until count]`. */
    fun convert(bytes: ByteArray, count: Int, out: IntArray) {
        if (fast) {
            var s = 0
            for (i in 0 until count) {
                out[i] = OPAQUE or
                    (bytes[s].toInt() and 0xFF) or
                    ((bytes[s + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[s + 2].toInt() and 0xFF) shl 16)
                s += 4
            }
        } else {
            var s = 0
            for (i in 0 until count) {
                out[i] = format.decodePixel(bytes, s)
                s += bytesPerPixel
            }
        }
    }

    private companion object {
        const val OPAQUE: Int = 0xFF000000.toInt()
    }
}
