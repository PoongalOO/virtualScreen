package fr.webinfoconcept.secondscreen.render

/**
 * Zones de l'écran distant modifiées depuis le dernier rendu (SS-031, SS-062).
 *
 * Deux vues du même contenu, prises ensemble par [take] :
 * - la **boîte englobante** : un seul rectangle à dessiner sur la surface (`lockCanvas` n'accepte qu'un rectangle, et tout ce
 *   qu'il contient doit être redessiné) ;
 * - au plus [MAX_RECTS] **rectangles distincts** : ce qu'il faut **réellement copier** du framebuffer dans le Bitmap.
 *
 * **Pourquoi deux vues (SS-062)** : mesuré sur la GT-P5110, la copie `setPixels` coûte ~35 ns par pixel alors que le dessin est
 * surtout un coût fixe (~18 ms) indépendant de la surface. Avec une seule boîte, deux petites zones éloignées (un curseur en
 * haut à gauche, une horloge en bas à droite) faisaient copier presque tout l'écran (644 000 pixels, 22 ms) pour quelques
 * milliers de pixels modifiés. Le Bitmap contient déjà le reste de l'image à jour : seuls les rectangles modifiés sont copiés.
 *
 * **Fusion** : un nouveau rectangle est fusionné avec un rectangle existant s'ils se **recouvrent ou se touchent**, ou si la
 * boîte qui les contient ne fait pas plus de [MERGE_WASTE_FACTOR] fois leur surface cumulée (deux lignes de texte voisines
 * ne font ainsi qu'une copie). Au-delà de [MAX_RECTS] rectangles, on fusionne les deux dont la boîte gaspille le moins. Le
 * résultat couvre **toujours** tout ce qui a été ajouté, et la surface à copier n'est jamais supérieure à celle de la boîte
 * englobante.
 *
 * **Threads** : [add] est appelé par le thread I/O (décodage), [take] par le thread qui dessine ; toutes les
 * méthodes sont synchronisées. Ce verrou est aussi ce qui garantit la **visibilité** des pixels : le décodeur
 * écrit ses pixels *puis* appelle [add], le rendu appelle [take] *puis* lit les pixels ; l'acquisition et la
 * libération du même verrou imposent cet ordre, sans verrou sur le framebuffer lui-même. Aucune allocation.
 *
 * Défensif : les rectangles sont bornés à l'écran, sans jamais calculer `x + w` en `Int` (dépassement) ; un
 * rectangle vide ou entièrement hors écran est ignoré.
 *
 * @param width largeur du framebuffer.
 * @param height hauteur du framebuffer.
 */
class DirtyRegion(private val width: Int, private val height: Int) {

    // Boîte englobante de tout ce qui a été ajouté.
    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private var dirty = false

    // Rectangles distincts : left, top, right, bottom (exclus) à la suite, [count] rectangles valides.
    private val rects = IntArray(4 * MAX_RECTS)
    private var count = 0

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
        addRect(l, t, r, b)
    }

    /** Marque tout l'écran comme modifié (création de la surface, changement de taille, reprise). */
    @Synchronized
    fun addAll() = add(0, 0, width, height)

    /** `true` si aucune zone n'est en attente. */
    @Synchronized
    fun isEmpty(): Boolean = !dirty

    /**
     * Récupère la **boîte englobante** de la zone modifiée et la remet à zéro : écrit `left, top, right, bottom` (`right` et
     * `bottom` **exclus**) dans [out] (au moins 4 éléments). Renvoie `false`, sans toucher [out], s'il n'y a rien.
     */
    @Synchronized
    fun take(out: IntArray): Boolean {
        if (!dirty) return false
        out[0] = left; out[1] = top; out[2] = right; out[3] = bottom
        clearLocked()
        return true
    }

    /**
     * Comme [take] mais rend aussi les **rectangles distincts** à copier (SS-062) : écrit la boîte englobante dans [bounds]
     * (au moins 4 éléments) et les rectangles (`left, top, right, bottom` exclus, à la suite) dans [copyRects] (au moins
     * `4 × [MAX_RECTS]` éléments).
     * @return le nombre de rectangles écrits, 0 (sans rien toucher) s'il n'y a rien.
     */
    @Synchronized
    fun take(bounds: IntArray, copyRects: IntArray): Int {
        if (!dirty) return 0
        bounds[0] = left; bounds[1] = top; bounds[2] = right; bounds[3] = bottom
        System.arraycopy(rects, 0, copyRects, 0, 4 * count)
        val n = count
        clearLocked()
        return n
    }

    /** Oublie la zone en attente. */
    @Synchronized
    fun clear() = clearLocked()

    private fun clearLocked() {
        dirty = false
        count = 0
    }

    /** Ajoute (l, t, r, b) à la liste en la fusionnant si besoin. Appelé avec le verrou. */
    private fun addRect(l0: Int, t0: Int, r0: Int, b0: Int) {
        var l = l0; var t = t0; var r = r0; var b = b0
        // Absorbe tout rectangle qui recouvre, touche ou entoure de près le nouveau ; recommence tant que la fusion en attire d'autres.
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < count) {
                val o = 4 * i
                if (shouldMerge(l, t, r, b, rects[o], rects[o + 1], rects[o + 2], rects[o + 3])) {
                    l = minOf(l, rects[o]); t = minOf(t, rects[o + 1]); r = maxOf(r, rects[o + 2]); b = maxOf(b, rects[o + 3])
                    removeAt(i)
                    merged = true
                } else {
                    i++
                }
            }
        }
        if (count == MAX_RECTS) mergeCheapestPair(l, t, r, b) else append(l, t, r, b)
    }

    private fun append(l: Int, t: Int, r: Int, b: Int) {
        val o = 4 * count
        rects[o] = l; rects[o + 1] = t; rects[o + 2] = r; rects[o + 3] = b
        count++
    }

    private fun removeAt(i: Int) {
        count--
        if (i != count) System.arraycopy(rects, 4 * count, rects, 4 * i, 4) // le dernier prend la place
    }

    /** Liste pleine : le nouveau rectangle est fusionné avec celui dont la boîte commune gaspille le moins de surface. */
    private fun mergeCheapestPair(l: Int, t: Int, r: Int, b: Int) {
        var best = 0
        var bestExtra = Long.MAX_VALUE
        for (i in 0 until count) {
            val o = 4 * i
            val union = area(minOf(l, rects[o]), minOf(t, rects[o + 1]), maxOf(r, rects[o + 2]), maxOf(b, rects[o + 3]))
            val extra = union - area(l, t, r, b) - area(rects[o], rects[o + 1], rects[o + 2], rects[o + 3])
            if (extra < bestExtra) { bestExtra = extra; best = i }
        }
        val o = 4 * best
        rects[o] = minOf(l, rects[o]); rects[o + 1] = minOf(t, rects[o + 1])
        rects[o + 2] = maxOf(r, rects[o + 2]); rects[o + 3] = maxOf(b, rects[o + 3])
    }

    private fun shouldMerge(l1: Int, t1: Int, r1: Int, b1: Int, l2: Int, t2: Int, r2: Int, b2: Int): Boolean {
        // Recouvrement ou contact (bord contre bord) : fusion gratuite.
        if (l1 <= r2 && l2 <= r1 && t1 <= b2 && t2 <= b1) return true
        val union = area(minOf(l1, l2), minOf(t1, t2), maxOf(r1, r2), maxOf(b1, b2))
        return union <= MERGE_WASTE_FACTOR * (area(l1, t1, r1, b1) + area(l2, t2, r2, b2))
    }

    private fun area(l: Int, t: Int, r: Int, b: Int): Long = (r - l).toLong() * (b - t).toLong()

    companion object {
        /** Nombre maximal de rectangles distincts conservés ; au-delà on fusionne. */
        const val MAX_RECTS = 8

        /** Deux rectangles éloignés sont fusionnés si leur boîte commune ne dépasse pas ce multiple de leur surface cumulée. */
        const val MERGE_WASTE_FACTOR = 2L
    }
}
