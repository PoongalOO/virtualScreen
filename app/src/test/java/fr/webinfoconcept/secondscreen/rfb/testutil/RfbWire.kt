package fr.webinfoconcept.secondscreen.rfb.testutil

/*
 * Fabrication de flux RFB côté « faux serveur » pour les tests. Volontairement écrit à part du code
 * de production : un test qui réutiliserait le code testé pour fabriquer ses données ne prouverait rien.
 */

fun u16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

/** S32 big-endian (les pseudo-encodages sont négatifs). */
fun s32(v: Int): ByteArray = u32(v.toLong() and 0xFFFFFFFFL)

/** En-tête de rectangle d'un FramebufferUpdate : x, y, largeur, hauteur, encodage. */
fun rectHeader(x: Int, y: Int, w: Int, h: Int, encoding: Int = 0): ByteArray =
    u16(x) + u16(y) + u16(w) + u16(h) + s32(encoding)

/** Début d'un FramebufferUpdate : type 0, padding, nombre de rectangles. */
fun updateHeader(rectangles: Int): ByteArray = byteArrayOf(0, 0) + u16(rectangles)

/**
 * Pixels ARGB -> octets RAW du format imposé (SS-021) : `[bleu, vert, rouge, octet inutilisé]`.
 * L'octet inutilisé vaut [unused] : un serveur réel y met ce qu'il veut.
 */
fun rawXrgb(pixels: IntArray, unused: Int = 0): ByteArray {
    val out = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        out[4 * i] = pixels[i].toByte()                // bleu
        out[4 * i + 1] = (pixels[i] shr 8).toByte()    // vert
        out[4 * i + 2] = (pixels[i] shr 16).toByte()   // rouge
        out[4 * i + 3] = unused.toByte()
    }
    return out
}

/** Un rectangle RAW complet (en-tête + pixels). */
fun rawRect(x: Int, y: Int, w: Int, h: Int, pixels: IntArray): ByteArray {
    require(pixels.size == w * h) { "${pixels.size} pixels pour ${w}x$h" }
    return rectHeader(x, y, w, h, encoding = 0) + rawXrgb(pixels)
}

/** Un FramebufferUpdate complet à partir de rectangles déjà sérialisés. */
fun framebufferUpdate(vararg rectangles: ByteArray): ByteArray =
    rectangles.fold(updateHeader(rectangles.size)) { acc, r -> acc + r }

/**
 * « Bureau de test » déterministe : un motif où chaque canal varie différemment en x et en y, donc
 * une inversion de lignes, de colonnes ou de canaux est détectée. Opaque.
 */
fun desktopPixel(x: Int, y: Int): Int =
    0xFF000000.toInt() or
        (((x * 7 + y * 3) and 0xFF) shl 16) or
        (((x xor y) and 0xFF) shl 8) or
        ((x + y * 5) and 0xFF)

/** Le bureau de test complet, ligne par ligne (même rangement que le framebuffer). */
fun desktopArgb(width: Int, height: Int): IntArray =
    IntArray(width * height) { desktopPixel(it % width, it / width) }

/** Le sous-rectangle (x, y, w, h) du bureau de test, ligne par ligne. */
fun desktopRect(x: Int, y: Int, w: Int, h: Int): IntArray =
    IntArray(w * h) { desktopPixel(x + it % w, y + it / w) }
