package fr.webinfoconcept.secondscreen.input

/**
 * Convertit une position tactile (pixels de la vue) en pixel du framebuffer distant (SS-040).
 *
 * Le rendu est **1:1** et ancré en (0, 0) ([fr.webinfoconcept.secondscreen.render.RemoteSurfaceView]) : le pixel
 * du framebuffer `n` occupe le carré `[n, n+1)` de la vue, donc `pixel = floor(coordonnée)`. Un toucher en
 * `(639.9, 400.2)` vise le pixel `(639, 400)` : arrondir au plus proche décalerait la cible d'un pixel une fois sur deux.
 *
 * Un toucher **hors du framebuffer** (marge noire d'une surface plus grande, coordonnée non finie) n'est pas
 * converti : envoyer un pixel du bord ferait cliquer là où l'utilisateur n'a pas visé. Quand le serveur ne fait pas
 * 1280×800, le centrage et la mise à l'échelle de SS-033 devront s'ajouter ici (décalage et rapport).
 *
 * Aucune allocation : le résultat est écrit dans un tableau fourni par l'appelant.
 */
class PointerMapper(val width: Int, val height: Int) {
    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "taille hors limites : ${width}x$height" }
    }

    /**
     * Écrit dans [out] (`out[0]` = x, `out[1]` = y) le pixel du framebuffer visé par ([viewX], [viewY]).
     * @return `false` (et [out] inchangé) si la position est hors du framebuffer ou n'est pas un nombre fini.
     */
    fun map(viewX: Float, viewY: Float, out: IntArray): Boolean {
        // NaN échoue à toutes les comparaisons, l'infini est hors bornes : les deux sont refusés ici.
        if (!(viewX >= 0f && viewX < width && viewY >= 0f && viewY < height)) return false
        // Un float proche de `width` peut s'arrondir à `width` : on borne pour rester dans 0..width-1.
        out[0] = minOf(viewX.toInt(), width - 1)
        out[1] = minOf(viewY.toInt(), height - 1)
        return true
    }

    /**
     * Comme [map] mais **borne** la position au framebuffer au lieu de la refuser : pour un glissement en cours, dont
     * le doigt peut sortir du cadre (marge noire, bord de l'écran) sans que le glissement doive s'interrompre ; le
     * pointeur distant reste alors sur le bord.
     * @return `false` (et [out] inchangé) seulement si une coordonnée n'est pas un nombre fini.
     */
    fun mapClamped(viewX: Float, viewY: Float, out: IntArray): Boolean {
        if (!viewX.isFinite() || !viewY.isFinite()) return false
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
