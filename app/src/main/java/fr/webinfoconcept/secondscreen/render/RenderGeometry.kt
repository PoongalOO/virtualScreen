package fr.webinfoconcept.secondscreen.render

/**
 * Comment un framebuffer de [frameWidth] × [frameHeight] tient dans une surface de [surfaceWidth] × [surfaceHeight]
 * (SS-032).
 *
 * Règle actuelle : **jamais de mise à l'échelle**. L'image est dessinée en (0, 0), un pixel du framebuffer pour
 * un pixel de l'écran. Si la surface est plus petite le bord droit/bas est **tronqué** ; si elle est plus grande
 * le reste est **noir**. Le centrage et la mise à l'échelle avec bandes noires pour un serveur qui n'est pas en
 * 1280×800 relèvent de SS-033 (P2).
 *
 * Sert à savoir *avant* de dessiner si l'affichage est fidèle ([isNative]) et à avertir l'utilisateur sinon.
 */
class RenderGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int
) {
    init {
        require(frameWidth > 0 && frameHeight > 0) { "framebuffer vide : ${frameWidth}x$frameHeight" }
        require(surfaceWidth >= 0 && surfaceHeight >= 0) { "surface négative : ${surfaceWidth}x$surfaceHeight" }
    }

    /** `true` si le framebuffer et la surface ont exactement la même taille : rendu 1:1 sans rien perdre ni ajouter. */
    val isNative: Boolean get() = frameWidth == surfaceWidth && frameHeight == surfaceHeight

    /** Largeur du framebuffer réellement visible. */
    val visibleWidth: Int get() = minOf(frameWidth, surfaceWidth)

    /** Hauteur du framebuffer réellement visible. */
    val visibleHeight: Int get() = minOf(frameHeight, surfaceHeight)

    /** Colonnes du framebuffer coupées à droite (surface trop étroite). */
    val clippedColumns: Int get() = maxOf(0, frameWidth - surfaceWidth)

    /** Lignes du framebuffer coupées en bas (surface trop basse) : par exemple sous une barre système. */
    val clippedRows: Int get() = maxOf(0, frameHeight - surfaceHeight)

    /** Colonnes noires à droite du framebuffer (surface plus large). */
    val blankColumns: Int get() = maxOf(0, surfaceWidth - frameWidth)

    /** Lignes noires sous le framebuffer (surface plus haute). */
    val blankRows: Int get() = maxOf(0, surfaceHeight - frameHeight)

    /** Vrai si une partie du framebuffer n'est pas affichée. */
    val isClipped: Boolean get() = clippedColumns > 0 || clippedRows > 0

    override fun toString(): String =
        "RenderGeometry(${frameWidth}x$frameHeight dans ${surfaceWidth}x$surfaceHeight, natif=$isNative)"
}
