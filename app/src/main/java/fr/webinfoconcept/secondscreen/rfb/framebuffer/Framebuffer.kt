package fr.webinfoconcept.secondscreen.rfb.framebuffer

import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import java.util.Arrays

/**
 * Stockage principal des pixels de l'écran distant (SS-020).
 *
 * Un tableau `IntArray` de [width] × [height] pixels **ARGB_8888** (`0xAARRGGBB`), rangés ligne
 * par ligne : le pixel (x, y) est à l'index `y * width + x`. 1280×800 pèse 4,1 Mio.
 *
 * **Allocation stable** : le tableau est alloué une seule fois à la construction et n'est
 * jamais réalloué ; les mises à jour ([fillRect], [writeRect], [copyRect]) n'allouent rien.
 *
 * **Limites** : toute mise à jour est validée **avant** d'écrire le moindre pixel, donc un
 * rectangle refusé ne laisse aucune écriture partielle. Les coordonnées viennent du réseau
 * (serveur non fiable) : les contrôles n'additionnent jamais `x + largeur` (dépassement d'`Int`)
 * et un rectangle hors écran lève [RfbProtocolException.RectangleOutOfBounds], jamais une
 * `ArrayIndexOutOfBoundsException`.
 *
 * **Rendu** : [pixels] est exposé tel quel pour que le rendu (SS-031) le transmette à
 * `Bitmap.setPixels(pixels, y * width + x, width, x, y, w, h)` sans copie intermédiaire. C'est un
 * choix délibéré pour éviter une copie plein écran par mise à jour (ARCHITECTURE.md) : l'appelant
 * ne doit **pas** écrire dans [pixels] en dehors de cette classe.
 *
 * **Threads** : non thread-safe. Le décodage (thread I/O) et le rendu lisent/écrivent le même
 * tableau ; leur synchronisation est à définir avec le rendu (SS-031).
 *
 * @throws IllegalArgumentException dimensions hors [1, [MAX_DIMENSION]] ou surface > [MAX_PIXELS].
 */
class Framebuffer(val width: Int = NOMINAL_WIDTH, val height: Int = NOMINAL_HEIGHT) {

    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "dimensions hors limites : ${width}x$height (max $MAX_DIMENSION par axe)"
        }
        // Long : évite tout dépassement du produit.
        require(width.toLong() * height.toLong() <= MAX_PIXELS) {
            "surface hors limites : ${width}x$height (max $MAX_PIXELS pixels)"
        }
    }

    /** Pixels ARGB_8888, [width] × [height], ligne par ligne. Lecture seule par convention (voir la doc de classe). */
    val pixels: IntArray = IntArray(width * height).also { Arrays.fill(it, OPAQUE_BLACK) }

    /**
     * `true` si le rectangle (x, y, [w] × [h]) est entièrement dans le framebuffer. Un rectangle
     * vide (w = 0 ou h = 0) reste soumis aux contrôles : son origine doit être dans
     * [0, width] × [0, height] **et** sa dimension non nulle doit tenir dans l'écran (un rectangle de
     * largeur 0 et de hauteur 3 ne peut pas commencer à 2 lignes du bas).
     */
    fun contains(x: Int, y: Int, w: Int, h: Int): Boolean =
        // Soustractions, pas d'additions : w et h sont non négatifs à ce stade, donc
        // width - w et height - h ne peuvent pas déborder (width, height <= MAX_DIMENSION).
        x >= 0 && y >= 0 && w >= 0 && h >= 0 && x <= width - w && y <= height - h

    /** Couleur du pixel (x, y). */
    fun getPixel(x: Int, y: Int): Int {
        checkRect(x, y, 1, 1)
        return pixels[y * width + x]
    }

    /** Remplit tout l'écran avec [argb]. */
    fun clear(argb: Int = OPAQUE_BLACK) {
        Arrays.fill(pixels, argb)
    }

    /**
     * Remplit le rectangle (x, y, [w] × [h]) avec la couleur [argb].
     *
     * @throws RfbProtocolException.RectangleOutOfBounds rectangle hors écran (rien n'est écrit).
     */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        checkRect(x, y, w, h)
        if (w == 0 || h == 0) return
        var start = y * width + x
        repeat(h) {
            Arrays.fill(pixels, start, start + w, argb)
            start += width
        }
    }

    /**
     * Copie le rectangle (x, y, [w] × [h]) depuis [src] : la ligne `r` du rectangle est lue à
     * `srcOffset + r * srcStride`, sur [w] pixels. [srcStride] permet de lire une sous-zone d'un
     * tableau plus large ; par défaut les lignes de [src] sont contiguës.
     *
     * @throws RfbProtocolException.RectangleOutOfBounds rectangle hors écran (rien n'est écrit).
     * @throws IllegalArgumentException [src] trop petit, [srcOffset] négatif ou [srcStride] < [w]
     *   (erreur de programmation de l'appelant, pas du serveur).
     */
    fun writeRect(x: Int, y: Int, w: Int, h: Int, src: IntArray, srcOffset: Int = 0, srcStride: Int = w) {
        checkRect(x, y, w, h)
        if (w == 0 || h == 0) return
        require(srcOffset >= 0 && srcStride >= w) { "srcOffset/srcStride invalides" }
        // Dernier index lu, en Long : (h - 1) * srcStride peut dépasser Int.
        val lastRead = srcOffset.toLong() + (h - 1).toLong() * srcStride + w
        require(lastRead <= src.size) { "tableau source trop petit" }

        var dst = y * width + x
        var from = srcOffset
        repeat(h) {
            System.arraycopy(src, from, pixels, dst, w)
            from += srcStride
            dst += width
        }
    }

    /**
     * Copie le rectangle source ([srcX], [srcY], [w] × [h]) vers ([dstX], [dstY]) **à l'intérieur du
     * framebuffer**, source et destination pouvant se chevaucher (CopyRect, SS-025). Le résultat est
     * celui d'un `memmove` : comme si la source avait d'abord été copiée dans un tampon temporaire.
     *
     * Sans tampon temporaire ni allocation : on copie les lignes dans l'ordre qui ne détruit jamais une
     * ligne source avant de l'avoir lue. Si la destination est plus bas que la source (`dstY > srcY`) on
     * copie de bas en haut, sinon de haut en bas. Chaque ligne est copiée par `System.arraycopy`, sûr même
     * quand ses deux plages se chevauchent (déplacement horizontal sur une même ligne).
     *
     * Les deux rectangles sont validés **avant** d'écrire quoi que ce soit : les coordonnées source viennent
     * du réseau. Un rectangle vide ne fait rien.
     *
     * @throws RfbProtocolException.RectangleOutOfBounds source ou destination hors écran (rien n'est écrit).
     */
    fun copyRect(srcX: Int, srcY: Int, w: Int, h: Int, dstX: Int, dstY: Int) {
        checkRect(srcX, srcY, w, h)
        checkRect(dstX, dstY, w, h)
        if (w == 0 || h == 0 || (srcX == dstX && srcY == dstY)) return

        if (dstY > srcY) {
            // Destination plus bas : les dernières lignes d'abord, sinon on écraserait des lignes encore à lire.
            for (row in h - 1 downTo 0) {
                System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (dstY + row) * width + dstX, w)
            }
        } else {
            for (row in 0 until h) {
                System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (dstY + row) * width + dstX, w)
            }
        }
    }

    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        if (!contains(x, y, w, h)) throw RfbProtocolException.RectangleOutOfBounds(x, y, w, h)
    }

    companion object {
        /** Résolution nominale de la GT-P5110 (CAHIER_DES_CHARGES.md). */
        const val NOMINAL_WIDTH = 1280
        const val NOMINAL_HEIGHT = 800

        /** Plus grande largeur ou hauteur acceptée. */
        const val MAX_DIMENSION = 4096

        /**
         * Plus grande surface acceptée : 1920×1200. Un buffer ARGB_8888 de cette taille pèse
         * 9,2 Mio ; avec le Bitmap de rendu, ~18 Mio sur un tas applicatif de 48 Mio mesuré sur la
         * GT-P5110 (SS-003). Le bureau nominal 1280×800 n'en utilise que 44 %.
         */
        const val MAX_PIXELS = 1920L * 1200L

        /** Noir opaque : valeur initiale des pixels (un écran vide s'affiche noir, pas transparent). */
        const val OPAQUE_BLACK: Int = 0xFF000000.toInt()
    }
}
