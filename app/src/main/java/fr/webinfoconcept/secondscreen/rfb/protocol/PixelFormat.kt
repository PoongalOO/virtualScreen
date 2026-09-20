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

    /**
     * Écrit ce format en [WIRE_SIZE] octets à [offset] (padding à zéro), tel qu'attendu dans
     * `SetPixelFormat`. Les octets hors de cette plage ne sont pas modifiés.
     *
     * @throws IllegalArgumentException format incohérent (jamais envoyé au serveur), valeur hors
     *   plage U8/U16, ou tableau trop petit.
     */
    fun writeTo(out: ByteArray, offset: Int = 0) {
        require(offset >= 0 && offset <= out.size - WIRE_SIZE) { "PIXEL_FORMAT hors du tableau" }
        problem()?.let { throw IllegalArgumentException("PIXEL_FORMAT invalide ($it)") }

        out.putU8At(offset, bitsPerPixel)
        out.putU8At(offset + 1, depth)
        out.putU8At(offset + 2, if (bigEndian) 1 else 0)
        out.putU8At(offset + 3, if (trueColour) 1 else 0)
        out.putU16At(offset + 4, redMax)
        out.putU16At(offset + 6, greenMax)
        out.putU16At(offset + 8, blueMax)
        out.putU8At(offset + 10, redShift)
        out.putU8At(offset + 11, greenShift)
        out.putU8At(offset + 12, blueShift)
        out.fill(0, offset + 13, offset + WIRE_SIZE) // padding
    }

    /**
     * Convertit le pixel de [bytesPerPixel] octets lu à [offset] dans [bytes] en ARGB_8888
     * (`0xFFRRGGBB`, alpha toujours opaque : RFB n'a pas de canal alpha ; un octet inutilisé du
     * pixel est ignoré). Chaque composante est ramenée sur 8 bits par `(v * 255 + max / 2) / max`,
     * exact pour max = 255 (identité) et arrondi au plus proche sinon.
     *
     * Conversion **de référence**, générique et volontairement simple : elle définit le sens
     * du format et sert d'oracle aux tests. Les décodeurs (RAW, SS-024) auront un chemin rapide
     * pour [XRGB_8888_LE] et devront produire les mêmes valeurs.
     *
     * Le format doit être valide : obtenu par [parse] ou [XRGB_8888_LE], jamais construit à la main
     * avec un maximum nul.
     *
     * @throws IllegalStateException format à palette (non supporté : le client n'en demande jamais).
     * @throws IllegalArgumentException [bytes] trop court à [offset].
     */
    fun decodePixel(bytes: ByteArray, offset: Int = 0): Int {
        check(trueColour) { "format à palette non supporté" }
        val n = bytesPerPixel
        require(offset >= 0 && offset <= bytes.size - n) { "pixel hors du tableau" }

        // Long : un pixel de 32 bits ne tient pas dans un Int signé.
        var value = 0L
        if (bigEndian) {
            for (i in 0 until n) value = (value shl 8) or bytes.u8At(offset + i).toLong()
        } else {
            for (i in n - 1 downTo 0) value = (value shl 8) or bytes.u8At(offset + i).toLong()
        }

        val r = to8Bits(((value ushr redShift) and redMax.toLong()).toInt(), redMax)
        val g = to8Bits(((value ushr greenShift) and greenMax.toLong()).toInt(), greenMax)
        val b = to8Bits(((value ushr blueShift) and blueMax.toLong()).toInt(), blueMax)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun to8Bits(component: Int, max: Int): Int =
        if (max == 255) component else (component * 255 + max / 2) / max

    /** Motif d'invalidité (libellé fixe du client, jamais du texte reçu), ou `null` si le format est valide. */
    private fun problem(): String? {
        if (bitsPerPixel != 8 && bitsPerPixel != 16 && bitsPerPixel != 32) return "bits-per-pixel"
        if (depth !in 1..bitsPerPixel) return "depth"
        if (!trueColour) return null

        val red = channelMask(redMax, redShift) ?: return "composante rouge"
        val green = channelMask(greenMax, greenShift) ?: return "composante verte"
        val blue = channelMask(blueMax, blueShift) ?: return "composante bleue"
        if ((red and green) != 0L || (red and blue) != 0L || (green and blue) != 0L) return "composantes qui se chevauchent"
        return null
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

    companion object {
        const val WIRE_SIZE = 16

        /**
         * Format de pixels que le client impose au serveur (`SetPixelFormat`, SS-021) :
         * 32 bits par pixel, profondeur 24, **little-endian**, couleurs vraies, rouge/vert/bleu sur
         * 8 bits aux décalages 16/8/0.
         *
         * Sur le fil un pixel occupe 4 octets dans cet ordre : `[bleu, vert, rouge, inutilisé]`. Lu
         * comme un U32 little-endian il vaut `0x00RRGGBB` ; le framebuffer stocke `0xFF000000 | pixel`.
         *
         * Pourquoi celui-là : little-endian est l'ordre natif de l'ARM de la GT-P5110 (assemblage d'un
         * `Int` sans inversion d'octets), et c'est le format natif 24 bits de x11vnc/TigerVNC, donc le
         * serveur n'a en général aucune conversion à faire. L'octet inutilisé est ignoré par le client.
         */
        val XRGB_8888_LE = PixelFormat(
            bitsPerPixel = 32, depth = 24, bigEndian = false, trueColour = true,
            redMax = 255, greenMax = 255, blueMax = 255,
            redShift = 16, greenShift = 8, blueShift = 0
        )

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
            format.problem()?.let { throw RfbProtocolException.InvalidPixelFormat(it) }
            return format
        }
    }
}
