package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.Encoding
import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket

/**
 * Encodage Hextile (SS-026) : le rectangle est découpé en tuiles de 16×16 pixels, parcourues de gauche
 * à droite puis de haut en bas ; les tuiles du bord droit et du bas sont plus petites (`w mod 16`,
 * `h mod 16`). Chaque tuile commence par un octet de sous-encodage (masque de bits) :
 *
 * | Bit | Nom | Effet |
 * |---|---|---|
 * | 1 | Raw | la tuile est `w × h` pixels bruts ; tous les autres bits sont ignorés |
 * | 2 | BackgroundSpecified | un pixel suit : nouvelle couleur de fond |
 * | 4 | ForegroundSpecified | un pixel suit : nouvelle couleur de premier plan |
 * | 8 | AnySubrects | un octet suit : nombre de sous-rectangles (0–255) |
 * | 16 | SubrectsColoured | chaque sous-rectangle est précédé de son propre pixel |
 *
 * Ordre des données d'une tuile non brute : [fond] [premier plan] [nombre de sous-rectangles] puis les
 * sous-rectangles ; un sous-rectangle est [pixel si coloré] `U8 (x << 4 | y)` `U8 ((w-1) << 4 | (h-1))`.
 *
 * **État entre tuiles** : le fond et le premier plan **persistent d'une tuile à l'autre** à l'intérieur
 * d'un rectangle (une tuile sans BackgroundSpecified réutilise le fond de la précédente). Une tuile Raw ne
 * les modifie pas. Ils valent noir opaque au début de chaque rectangle : la spec impose que la première
 * tuile non brute précise son fond ; un serveur qui l'omet obtient ce noir déterministe plutôt qu'un refus.
 * Si SubrectsColoured et ForegroundSpecified sont tous deux présents (interdit par la spec), le pixel de
 * premier plan est lu pour rester aligné puis ignoré.
 *
 * **Le serveur est une entrée non fiable** :
 * - toute lecture est bornée par tuile : au plus 1 + 1 024 octets (Raw), ou 1 + 9 + 255 × 6 octets ; rien
 *   de ce qui est lu n'est proportionnel à une valeur du réseau au-delà de ces bornes fixes ;
 * - le rectangle est validé (dans le framebuffer) avant de lire quoi que ce soit ;
 * - un sous-rectangle doit tenir **dans sa tuile** (sinon [RfbProtocolException.InvalidHextileTile]) : un
 *   serveur ne peut pas écrire hors de la tuile en cours ;
 * - les bits 5 à 7 du sous-encodage sont indéfinis ; ils révèlent en pratique un flux désaligné, et sont
 *   refusés (sauf si Raw est présent, cas où le reste est ignoré par la spec) ;
 * - aucune allocation par rectangle : quatre buffers de taille fixe alloués à la construction.
 *
 * Sur erreur, le contenu du rectangle en cours est indéfini (contrat d'[EncodingDecoder]).
 *
 * @param pixelFormat format des pixels reçus, à couleurs vraies (une palette n'est pas gérée).
 */
class HextileDecoder(pixelFormat: PixelFormat = PixelFormat.XRGB_8888_LE) : EncodingDecoder {

    override val encoding: Int = Encoding.HEXTILE

    private val converter = PixelConverter(pixelFormat)
    private val bytesPerPixel = converter.bytesPerPixel

    private val mask = ByteArray(1)
    private val tileHeader = ByteArray(2 * bytesPerPixel + 1)               // fond + premier plan + nombre
    private val subrectBytes = ByteArray(MAX_SUBRECTS * (2 + bytesPerPixel)) // le cas coloré, le plus grand
    private val tileBytes = ByteArray(TILE_SIZE * TILE_SIZE * bytesPerPixel)
    private val tilePixels = IntArray(TILE_SIZE * TILE_SIZE)

    override fun decode(socket: RfbSocket, x: Int, y: Int, w: Int, h: Int, framebuffer: Framebuffer) {
        // Avant toute lecture : un rectangle invalide ne doit pas consommer de données du serveur.
        if (!framebuffer.contains(x, y, w, h)) throw RfbProtocolException.RectangleOutOfBounds(x, y, w, h)
        if (w == 0 || h == 0) return

        var background = OPAQUE_BLACK
        var foreground = OPAQUE_BLACK

        var tileY = 0
        while (tileY < h) {
            val tileHeight = minOf(TILE_SIZE, h - tileY)
            var tileX = 0
            while (tileX < w) {
                val tileWidth = minOf(TILE_SIZE, w - tileX)
                val left = x + tileX
                val top = y + tileY

                socket.readFully(mask, 0, 1)
                val subencoding = mask[0].toInt() and 0xFF

                if (subencoding and RAW != 0) {
                    readRawTile(socket, framebuffer, left, top, tileWidth, tileHeight)
                } else {
                    if (subencoding and UNDEFINED_BITS != 0) throw RfbProtocolException.InvalidHextileTile("sous-encodage inconnu")

                    // [fond] [premier plan] [nombre de sous-rectangles], en une seule lecture.
                    var headerLength = 0
                    if (subencoding and BACKGROUND_SPECIFIED != 0) headerLength += bytesPerPixel
                    if (subencoding and FOREGROUND_SPECIFIED != 0) headerLength += bytesPerPixel
                    if (subencoding and ANY_SUBRECTS != 0) headerLength += 1
                    if (headerLength > 0) socket.readFully(tileHeader, 0, headerLength)

                    var at = 0
                    if (subencoding and BACKGROUND_SPECIFIED != 0) {
                        background = converter.pixel(tileHeader, at)
                        at += bytesPerPixel
                    }
                    if (subencoding and FOREGROUND_SPECIFIED != 0) {
                        foreground = converter.pixel(tileHeader, at)
                        at += bytesPerPixel
                    }
                    val subrects = if (subencoding and ANY_SUBRECTS != 0) tileHeader[at].toInt() and 0xFF else 0

                    framebuffer.fillRect(left, top, tileWidth, tileHeight, background)
                    if (subrects > 0) {
                        drawSubrects(
                            socket, framebuffer, left, top, tileWidth, tileHeight, subrects,
                            coloured = subencoding and SUBRECTS_COLOURED != 0, foreground = foreground
                        )
                    }
                }
                tileX += TILE_SIZE
            }
            tileY += TILE_SIZE
        }
    }

    private fun readRawTile(socket: RfbSocket, framebuffer: Framebuffer, left: Int, top: Int, tileWidth: Int, tileHeight: Int) {
        val count = tileWidth * tileHeight // <= 256
        socket.readFully(tileBytes, 0, count * bytesPerPixel)
        converter.convert(tileBytes, count, tilePixels)
        framebuffer.writeRect(left, top, tileWidth, tileHeight, tilePixels, 0, tileWidth)
    }

    private fun drawSubrects(
        socket: RfbSocket, framebuffer: Framebuffer, left: Int, top: Int,
        tileWidth: Int, tileHeight: Int, count: Int, coloured: Boolean, foreground: Int
    ) {
        val pixelBytes = if (coloured) bytesPerPixel else 0
        val each = pixelBytes + 2
        socket.readFully(subrectBytes, 0, count * each) // count <= 255 : borné

        var at = 0
        repeat(count) {
            val colour = if (coloured) converter.pixel(subrectBytes, at) else foreground
            val position = subrectBytes[at + pixelBytes].toInt() and 0xFF
            val size = subrectBytes[at + pixelBytes + 1].toInt() and 0xFF
            at += each

            val sx = position ushr 4
            val sy = position and 0x0F
            val sw = (size ushr 4) + 1 // 1..16
            val sh = (size and 0x0F) + 1
            // Le sous-rectangle doit tenir dans SA tuile, pas seulement dans le framebuffer.
            if (sx + sw > tileWidth || sy + sh > tileHeight) throw RfbProtocolException.InvalidHextileTile("sous-rectangle hors tuile")

            framebuffer.fillRect(left + sx, top + sy, sw, sh, colour)
        }
    }

    private companion object {
        const val TILE_SIZE = 16
        const val MAX_SUBRECTS = 255

        // Sous-encodage
        const val RAW = 1
        const val BACKGROUND_SPECIFIED = 2
        const val FOREGROUND_SPECIFIED = 4
        const val ANY_SUBRECTS = 8
        const val SUBRECTS_COLOURED = 16
        const val UNDEFINED_BITS = 0xE0 // bits 5 à 7

        const val OPAQUE_BLACK: Int = 0xFF000000.toInt()
    }
}
