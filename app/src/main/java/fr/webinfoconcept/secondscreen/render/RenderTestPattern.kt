package fr.webinfoconcept.secondscreen.render

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer

/**
 * Motif de test affiché par l'écran de rendu provisoire (SS-030), en attendant qu'une vraie connexion
 * alimente le framebuffer (SS-031, SS-050+).
 *
 * Déterministe et fonction des seules coordonnées : on peut donc comparer pixel par pixel une capture de
 * la tablette au motif attendu, et détecter n'importe quelle inversion de ligne, de colonne ou de canal :
 * - un cadre blanc d'un pixel (détecte un décalage ou une mise à l'échelle) ;
 * - quatre coins de 32×32 de couleurs différentes : rouge (haut-gauche), vert (haut-droite), bleu
 *   (bas-gauche), jaune (bas-droite) : donnent l'orientation ;
 * - au milieu, un dégradé où le rouge croît avec x, le vert avec y et le bleu varie en `x xor y`.
 */
internal object RenderTestPattern {
    private const val CORNER = 32
    private const val OPAQUE = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val RED = 0xFFFF0000.toInt()
    private const val GREEN = 0xFF00FF00.toInt()
    private const val BLUE = 0xFF0000FF.toInt()
    private const val YELLOW = 0xFFFFFF00.toInt()

    /** Couleur ARGB du pixel (x, y) d'un motif de [width] × [height] (au moins 3 × 3). */
    fun pixel(x: Int, y: Int, width: Int, height: Int): Int {
        require(width >= 3 && height >= 3) { "motif trop petit : ${width}x$height" }
        require(x in 0 until width && y in 0 until height) { "pixel hors du motif : ($x,$y)" }

        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return WHITE
        val left = x < CORNER
        val right = x >= width - CORNER
        val top = y < CORNER
        val bottom = y >= height - CORNER
        when {
            left && top -> return RED
            right && top -> return GREEN
            left && bottom -> return BLUE
            right && bottom -> return YELLOW
        }
        val r = x * 255 / (width - 1)
        val g = y * 255 / (height - 1)
        val b = (x xor y) and 0xFF
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    /** Un framebuffer de [width] × [height] rempli avec le motif. */
    fun create(width: Int = Framebuffer.NOMINAL_WIDTH, height: Int = Framebuffer.NOMINAL_HEIGHT): Framebuffer {
        val fb = Framebuffer(width, height)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = pixel(x, y, width, height)
            fb.writeRect(0, y, width, 1, row)
        }
        return fb
    }
}
