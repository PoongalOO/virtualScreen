package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.render.RenderGeometry

/**
 * Convertit une position tactile (pixels de la vue) en pixel du framebuffer distant (SS-040, SS-033).
 *
 * ## Sans mise à l'échelle
 * Le rendu est **1:1** et ancré en (0, 0) ([fr.webinfoconcept.secondscreen.render.RemoteSurfaceView]) : le pixel du
 * framebuffer `n` occupe le carré `[n, n+1)` de la vue, donc `pixel = floor(coordonnée)`. Un toucher en `(639.9, 400.2)`
 * vise le pixel `(639, 400)` : arrondir au plus proche décalerait la cible d'un pixel une fois sur deux.
 *
 * ## Avec mise à l'échelle (SS-033)
 * Quand l'image est ajustée avec bandes noires ([RenderGeometry.isScaled]), un pixel du framebuffer occupe un carré de
 * `scale` pixels d'écran, et l'image commence en ([RenderGeometry.destLeft], [RenderGeometry.destTop]) :
 * `pixel = floor((coordonnée − décalage) / scale)`. Un toucher **dans une bande noire** n'est pas converti (il ne vise
 * rien) ; pendant un glissement, [mapClamped] le ramène au bord de l'image.
 *
 * La géométrie est relue à **chaque appel** ([geometry]) : elle change quand la surface change de taille (barre système,
 * plein écran) ou quand l'utilisateur modifie l'échelle, sans qu'il faille refaire le mappeur.
 *
 * Un toucher **hors du framebuffer** (marge noire, coordonnée non finie) n'est **pas** converti : envoyer un pixel du bord
 * ferait cliquer là où l'utilisateur n'a pas visé.
 *
 * Aucune allocation : le résultat est écrit dans un tableau fourni par l'appelant.
 *
 * @param geometry donne la géométrie courante du rendu ; `null` ou non ajustée : conversion 1:1.
 */
class PointerMapper(
    val width: Int,
    val height: Int,
    private val geometry: () -> RenderGeometry? = { null }
) {
    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "taille hors limites : ${width}x$height" }
    }

    /**
     * Écrit dans [out] (`out[0]` = x, `out[1]` = y) le pixel du framebuffer visé par ([viewX], [viewY]).
     * @return `false` (et [out] inchangé) si la position est hors du framebuffer ou n'est pas un nombre fini.
     */
    fun map(viewX: Float, viewY: Float, out: IntArray): Boolean {
        // NaN échoue à toutes les comparaisons, l'infini est hors bornes : les deux sont refusés ici.
        val g = geometry()
        if (g != null && g.isScaled) {
            if (!(viewX >= g.destLeft && viewX < g.destLeft + g.destWidth && viewY >= g.destTop && viewY < g.destTop + g.destHeight)) {
                return false // dans une bande noire, ou non fini
            }
            out[0] = minOf(((viewX - g.destLeft) / g.scale).toInt(), width - 1)
            out[1] = minOf(((viewY - g.destTop) / g.scale).toInt(), height - 1)
            return true
        }
        if (!(viewX >= 0f && viewX < width && viewY >= 0f && viewY < height)) return false
        // Un float proche de `width` peut s'arrondir à `width` : on borne pour rester dans 0..width-1.
        out[0] = minOf(viewX.toInt(), width - 1)
        out[1] = minOf(viewY.toInt(), height - 1)
        return true
    }

    /**
     * Rapport écran/framebuffer courant : 1 sans mise à l'échelle, sinon [RenderGeometry.scale]. Sert au mode touchpad :
     * un déplacement de doigt en pixels d'écran vaut `déplacement / scale` pixels du framebuffer, pour que le pointeur
     * parcoure à l'écran la même distance quelle que soit l'échelle.
     */
    val viewScale: Float
        get() {
            val g = geometry()
            return if (g != null && g.isScaled) g.scale else 1f
        }

    /**
     * Comme [map] mais **borne** la position au framebuffer au lieu de la refuser : pour un glissement en cours, dont
     * le doigt peut sortir du cadre (marge noire, bord de l'écran) sans que le glissement doive s'interrompre ; le
     * pointeur distant reste alors sur le bord.
     * @return `false` (et [out] inchangé) seulement si une coordonnée n'est pas un nombre fini.
     */
    fun mapClamped(viewX: Float, viewY: Float, out: IntArray): Boolean {
        if (!viewX.isFinite() || !viewY.isFinite()) return false
        val g = geometry()
        if (g != null && g.isScaled) {
            out[0] = clampToPixel((viewX - g.destLeft) / g.scale, width)
            out[1] = clampToPixel((viewY - g.destTop) / g.scale, height)
            return true
        }
        out[0] = clampToPixel(viewX, width)
        out[1] = clampToPixel(viewY, height)
        return true
    }

    private fun clampToPixel(v: Float, size: Int): Int = when {
        v <= 0f -> 0
        v >= size -> size - 1
        else -> minOf(v.toInt(), size - 1)
    }

    companion object {
        /** Les coordonnées d'un `PointerEvent` sont des U16 : un framebuffer plus grand n'est pas adressable. */
        const val MAX_DIMENSION = 65535
    }
}
