package fr.webinfoconcept.secondscreen.render

/**
 * Zone de l'écran distant modifiée depuis le dernier rendu (SS-031).
 *
 * C'est la **boîte englobante** des rectangles ajoutés : un seul rectangle à copier dans le Bitmap et à
 * dessiner, quel que soit le nombre de rectangles d'un `FramebufferUpdate`. Le compromis est assumé : deux
 * petites zones éloignées donnent une grande boîte. C'est simple et sans allocation ; un suivi plus fin serait une
 * optimisation à justifier par une mesure (SS-062).
 *
 * **Threads** : [add] est appelé par le thread I/O (décodage), [take] par le thread qui dessine ; toutes les
 * méthodes sont synchronisées. Ce verrou est aussi ce qui garantit la **visibilité** des pixels : le décodeur
 * écrit ses pixels *puis* appelle [add], le rendu appelle [take] *puis* lit les pixels ; l'acquisition et la
 * libération du même verrou imposent cet ordre, sans verrou sur le framebuffer lui-même.
 *
 * Défensif : les rectangles sont bornés à l'écran, sans jamais calculer `x + w` en `Int` (dépassement) ; un
 * rectangle vide ou entièrement hors écran est ignoré.
 *
 * @param width largeur du framebuffer.
 * @param height hauteur du framebuffer.
 */
class DirtyRegion(private val width: Int, private val height: Int) {

    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private var dirty = false

    /** Ajoute le rectangle (x, y, [w] × [h]) à la zone modifiée. Sans allocation. */
    @Synchronized
    fun add(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val l = maxOf(0, x)
        val t = maxOf(0, y)
        // Long : x + w peut dépasser Int.MAX_VALUE avec des valeurs hostiles.
        val r = minOf(width.toLong(), x.toLong() + w.toLong()).toInt()
        val b = minOf(height.toLong(), y.toLong() + h.toLong()).toInt()
        if (r <= l || b <= t) return

        if (!dirty) {
            left = l; top = t; right = r; bottom = b
            dirty = true
        } else {
            if (l < left) left = l
            if (t < top) top = t
            if (r > right) right = r
            if (b > bottom) bottom = b
        }
    }

    /** Marque tout l'écran comme modifié (création de la surface, changement de taille, reprise). */
    @Synchronized
    fun addAll() = add(0, 0, width, height)

    /** `true` si aucune zone n'est en attente. */
    @Synchronized
    fun isEmpty(): Boolean = !dirty

    /**
     * Récupère la zone modifiée et la remet à zéro : écrit `left, top, right, bottom` (`right` et `bottom`
     * **exclus**) dans [out] (au moins 4 éléments). Renvoie `false`, sans toucher [out], s'il n'y a rien.
     */
    @Synchronized
    fun take(out: IntArray): Boolean {
        if (!dirty) return false
        out[0] = left; out[1] = top; out[2] = right; out[3] = bottom
        dirty = false
        return true
    }

    /** Oublie la zone en attente. */
    @Synchronized
    fun clear() {
        dirty = false
    }
}
