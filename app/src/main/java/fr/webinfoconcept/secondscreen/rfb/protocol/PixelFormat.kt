package fr.webinfoconcept.secondscreen.rfb.protocol

/**
 * `PIXEL_FORMAT` RFB (RFC 6143 §7.4), 16 octets sur le fil :
 *
 * | Octets | Champ |
 * |---|---|
 * | 0 | bits-per-pixel (8, 16 ou 32) |
 * | 1 | depth |
 * | 2 | big-endian-flag (≠ 0 : pixels en big-endian) |
 * | 3 | true-colour-flag (≠ 0 : couleurs vraies, 0 : palette) |
 * | 4–5, 6–7, 8–9 | red-max, green-max, blue-max (U16) |
 * | 10, 11, 12 | red-shift, green-shift, blue-shift (U8) |
 * | 13–15 | padding |
 *
 * En couleurs vraies, la composante d'un pixel `p` est `(p >> shift) & max`.
 * Toutes les valeurs sont lues non signées.
 */
data class PixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColour: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int
) {
    val bytesPerPixel: Int get() = bitsPerPixel / 8

    companion object {
        const val WIRE_SIZE = 16

        /**
         * Décode et valide un `PIXEL_FORMAT` reçu du serveur (donnée non fiable).
         * Une palette (`true-colour-flag` = 0) n'a pas de maxima/décalages à valider.
         *
         * @throws RfbProtocolException.InvalidPixelFormat format incohérent.
         * @throws IllegalArgumentException si [bytes] ne contient pas [WIRE_SIZE] octets à [offset].
         */
        fun parse(bytes: ByteArray, offset: Int = 0): PixelFormat {
            require(offset >= 0 && offset <= bytes.size - WIRE_SIZE) { "PIXEL_FORMAT hors du tableau" }

            val format = PixelFormat(
                bitsPerPixel = bytes.u8At(offset),
                depth = bytes.u8At(offset + 1),
                bigEndian = bytes.u8At(offset + 2) != 0,
                trueColour = bytes.u8At(offset + 3) != 0,
                redMax = bytes.u16At(offset + 4),
                greenMax = bytes.u16At(offset + 6),
                blueMax = bytes.u16At(offset + 8),
                redShift = bytes.u8At(offset + 10),
                greenShift = bytes.u8At(offset + 11),
                blueShift = bytes.u8At(offset + 12)
            )
            format.validate()
            return format
        }
    }

    private fun validate() {
        if (bitsPerPixel != 8 && bitsPerPixel != 16 && bitsPerPixel != 32) invalid("bits-per-pixel")
        if (depth !in 1..bitsPerPixel) invalid("depth")
        if (!trueColour) return

        val red = channelMask(redMax, redShift) ?: invalid("composante rouge")
        val green = channelMask(greenMax, greenShift) ?: invalid("composante verte")
        val blue = channelMask(blueMax, blueShift) ?: invalid("composante bleue")
        if ((red and green) != 0L || (red and blue) != 0L || (green and blue) != 0L) invalid("composantes qui se chevauchent")
    }

    /**
     * Masque de la composante dans le pixel, ou `null` si [max] n'est pas de la forme
     * 2^n - 1 (n >= 1) ou si la composante dépasse le pixel. `Long` : évite tout
     * débordement du décalage.
     */
    private fun channelMask(max: Int, shift: Int): Long? {
        if (max <= 0 || (max and (max + 1)) != 0) return null
        if (shift + Integer.bitCount(max) > bitsPerPixel) return null
        return max.toLong() shl shift
    }

    private fun invalid(detail: String): Nothing = throw RfbProtocolException.InvalidPixelFormat(detail)
}
