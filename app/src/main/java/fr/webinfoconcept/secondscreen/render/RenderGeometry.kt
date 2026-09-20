package fr.webinfoconcept.secondscreen.render

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer

/**
 * Comment un framebuffer de [frameWidth] × [frameHeight] est placé dans une surface de [surfaceWidth] × [surfaceHeight]
 * (SS-032, SS-033). Pure : dit *avant* de dessiner où va l'image, et sert aussi à convertir les touchers.
 *
 * ## Trois cas
 * - **Natif** ([isNative]) : mêmes tailles, un pixel du framebuffer pour un pixel de l'écran, rien de perdu.
 * - **1:1 sans mise à l'échelle** : l'écran distant est en 1280×800 ([isNominalFrame], la résolution cible de la tablette)
 *   et [fitToScreen] est faux. L'image est dessinée en (0, 0), pixel pour pixel (SS-032). Si la surface est plus petite le
 *   bord droit/bas est **tronqué** ([clippedRows] : les 48 lignes d'une barre système visible) ; si elle est plus grande le
 *   reste est **noir** ([blankColumns], [blankRows]).
 * - **Ajusté avec bandes noires** ([isScaled], SS-033, « letterbox ») : l'écran distant **n'est pas** en 1280×800, ou
 *   [fitToScreen] est vrai. L'image est **mise à l'échelle avec son ratio conservé** ([scale] : le plus petit des deux
 *   rapports surface/image), centrée, et des bandes noires ([barColumns], [barRows]) comblent le reste. Rien n'est
 *   rogné. Un serveur en 1920×1080 ou 1024×768 devient ainsi utilisable au lieu d'être coupé.
 *
 * Le rendu 1:1 exact reste la règle pour la résolution cible : on ne met à l'échelle un 1280×800 que si l'utilisateur le
 * demande, parce que toute mise à l'échelle floute un peu le texte.
 *
 * @param fitToScreen ajuster même un écran distant en 1280×800 (par exemple pour le voir en entier avec la barre système).
 */
class RenderGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val fitToScreen: Boolean = false
) {
    init {
        require(frameWidth > 0 && frameHeight > 0) { "framebuffer vide : ${frameWidth}x$frameHeight" }
        require(surfaceWidth >= 0 && surfaceHeight >= 0) { "surface négative : ${surfaceWidth}x$surfaceHeight" }
    }

    /** `true` si l'écran distant est en 1280×800, la résolution nominale. */
    val isNominalFrame: Boolean
        get() = frameWidth == Framebuffer.NOMINAL_WIDTH && frameHeight == Framebuffer.NOMINAL_HEIGHT

    /** `true` si le framebuffer et la surface ont exactement la même taille : rendu 1:1 sans rien perdre ni ajouter. */
    val isNative: Boolean get() = frameWidth == surfaceWidth && frameHeight == surfaceHeight

    /** `true` si l'image est mise à l'échelle avec bandes noires (voir la description de la classe). */
    val isScaled: Boolean =
        !(frameWidth == surfaceWidth && frameHeight == surfaceHeight) && surfaceWidth > 0 && surfaceHeight > 0 &&
            (fitToScreen || !(frameWidth == Framebuffer.NOMINAL_WIDTH && frameHeight == Framebuffer.NOMINAL_HEIGHT))

    /**
     * Rapport écran/framebuffer (1 si l'image n'est pas mise à l'échelle) : le plus petit de
     * `surfaceWidth / frameWidth` et `surfaceHeight / frameHeight`, donc l'image entre toujours en entier.
     */
    val scale: Float =
        if (isScaled) minOf(surfaceWidth.toFloat() / frameWidth, surfaceHeight.toFloat() / frameHeight) else 1f

    /** Largeur de l'image à l'écran, en pixels de la surface. */
    val destWidth: Int =
        if (isScaled) minOf(surfaceWidth, maxOf(1, Math.round(frameWidth * scale))) else minOf(frameWidth, surfaceWidth)

    /** Hauteur de l'image à l'écran, en pixels de la surface. */
    val destHeight: Int =
        if (isScaled) minOf(surfaceHeight, maxOf(1, Math.round(frameHeight * scale))) else minOf(frameHeight, surfaceHeight)

    /** Colonne de la surface où commence l'image : 0, sauf si elle est centrée (mise à l'échelle). */
    val destLeft: Int = if (isScaled) (surfaceWidth - destWidth) / 2 else 0

    /** Ligne de la surface où commence l'image : 0, sauf si elle est centrée (mise à l'échelle). */
    val destTop: Int = if (isScaled) (surfaceHeight - destHeight) / 2 else 0

    /** Largeur du framebuffer réellement visible (sans mise à l'échelle). */
    val visibleWidth: Int get() = minOf(frameWidth, surfaceWidth)

    /** Hauteur du framebuffer réellement visible (sans mise à l'échelle). */
    val visibleHeight: Int get() = minOf(frameHeight, surfaceHeight)

    /** Colonnes du framebuffer coupées à droite (surface trop étroite) ; 0 si l'image est ajustée. */
    val clippedColumns: Int get() = if (isScaled) 0 else maxOf(0, frameWidth - surfaceWidth)

    /** Lignes du framebuffer coupées en bas (surface trop basse : barre système) ; 0 si l'image est ajustée. */
    val clippedRows: Int get() = if (isScaled) 0 else maxOf(0, frameHeight - surfaceHeight)

    /** Colonnes noires à droite du framebuffer (surface plus large, sans mise à l'échelle). */
    val blankColumns: Int get() = if (isScaled) 0 else maxOf(0, surfaceWidth - frameWidth)

    /** Lignes noires sous le framebuffer (surface plus haute, sans mise à l'échelle). */
    val blankRows: Int get() = if (isScaled) 0 else maxOf(0, surfaceHeight - frameHeight)

    /** Colonnes noires des bandes latérales (les deux côtés ensemble) quand l'image est ajustée. */
    val barColumns: Int get() = if (isScaled) surfaceWidth - destWidth else 0

    /** Lignes noires des bandes haute et basse (ensemble) quand l'image est ajustée. */
    val barRows: Int get() = if (isScaled) surfaceHeight - destHeight else 0

    /** Vrai si une partie du framebuffer n'est pas affichée. */
    val isClipped: Boolean get() = clippedColumns > 0 || clippedRows > 0

    override fun toString(): String =
        "RenderGeometry(${frameWidth}x$frameHeight dans ${surfaceWidth}x$surfaceHeight, natif=$isNative, ajusté=$isScaled)"
}
