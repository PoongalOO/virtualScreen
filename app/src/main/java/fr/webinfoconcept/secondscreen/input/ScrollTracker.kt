package fr.webinfoconcept.secondscreen.input

/**
 * Convertit le déplacement du **centre de deux doigts** en crans de molette (SS-044) : un cran par [Scroll.stepPx] pixels,
 * le reste étant conservé pour le déplacement suivant, un seul axe à la fois (l'axe au plus grand chemin cumulé ; l'autre
 * est oublié, donc pas de dérapage latéral), et au plus [Scroll.MAX_CLICKS_PER_EVENT] crans par événement (le surplus d'un
 * saut énorme est oublié). Partagé par le mode direct ([TouchGestureDetector]) et le mode touchpad ([TouchpadDetector]).
 *
 * Pure : aucune dépendance Android. Les coordonnées non finies sont ignorées.
 */
class ScrollTracker(private val config: Scroll) {
    private var lastX = 0f
    private var lastY = 0f
    private var accumX = 0f
    private var accumY = 0f

    /** Deux doigts viennent de se poser, leur centre est en ([centerX], [centerY]). @return `false` si non fini. */
    fun start(centerX: Float, centerY: Float): Boolean {
        if (!centerX.isFinite() || !centerY.isFinite()) return false
        lastX = centerX
        lastY = centerY
        accumX = 0f
        accumY = 0f
        config.listener.onScrollStart(centerX, centerY)
        return true
    }

    /** Le centre des doigts se déplace en ([centerX], [centerY]) : émet les crans correspondants. */
    fun move(centerX: Float, centerY: Float) {
        if (!centerX.isFinite() || !centerY.isFinite()) return
        accumX += centerX - lastX
        accumY += centerY - lastY
        lastX = centerX
        lastY = centerY

        if (Math.abs(accumY) >= Math.abs(accumX)) accumX = 0f else accumY = 0f
        val stepsX = (accumX / config.stepPx).toInt() // vers zéro
        val stepsY = (accumY / config.stepPx).toInt()
        if (stepsX == 0 && stepsY == 0) return
        val limit = Scroll.MAX_CLICKS_PER_EVENT
        accumX = if (Math.abs(stepsX) > limit) 0f else accumX - stepsX * config.stepPx
        accumY = if (Math.abs(stepsY) > limit) 0f else accumY - stepsY * config.stepPx

        // Sens : « naturel » = le contenu suit les doigts, donc doigts vers le haut => molette vers le bas.
        val sign = if (config.natural) -1 else 1
        config.listener.onScroll(
            (sign * stepsX).coerceIn(-limit, limit),
            (sign * stepsY).coerceIn(-limit, limit)
        )
    }
}
