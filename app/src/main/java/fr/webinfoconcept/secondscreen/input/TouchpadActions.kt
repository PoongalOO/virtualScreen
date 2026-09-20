package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.PointerButtons

/**
 * Traduit les gestes du mode touchpad (SS-045) en `PointerEvent` : tient la **position du pointeur distant**, la déplace de
 * `déplacement du doigt × sensibilité`, et envoie clics, glissements et molette à cet endroit. Thread UI ; ne bloque jamais.
 *
 * - **Sensibilité** ([sensitivity], relue à chaque déplacement : un réglage change l'effet immédiatement) : facteur entre le
 *   déplacement du doigt et celui du pointeur. Bornée à [MIN_SENSITIVITY]..[MAX_SENSITIVITY]. Pas d'accélération : le
 *   pointeur suit le doigt proportionnellement.
 * - **Sous-pixel** : la position est un nombre décimal ; un déplacement de 0,4 pixel n'est pas perdu, il s'ajoute au suivant.
 *   On n'envoie que quand le pixel change.
 * - **Bornes** : le pointeur reste dans le framebuffer (il s'arrête au bord, sans jamais en sortir).
 * - **Position partagée** ([PointerPosition]) : au premier geste, ou si le mode direct a déplacé le pointeur entre-temps, on
 *   repart de sa dernière position connue. Avant toute position connue, le pointeur part du **centre** de l'écran distant
 *   (le serveur ne dit pas où il est).
 * - **Glissement** : l'appui du bouton est un message d'état (jamais abandonné), les déplacements bouton enfoncé sont
 *   remplaçables, le relâchement un message d'état. Si l'appui n'a pas pu partir, les déplacements ne portent pas le
 *   bouton : un déplacement bouton enfoncé serait pris par le serveur pour un appui fantôme.
 *
 * @param mapper taille du framebuffer distant ; à remplacer à chaque session.
 * @param position dernière position du pointeur, partagée avec [PointerActions].
 * @param sensitivity donne la sensibilité courante.
 */
class TouchpadActions(
    @Volatile var mapper: PointerMapper,
    private val sink: MessageSink,
    private val position: PointerPosition,
    private val sensitivity: () -> Float = { DEFAULT_SENSITIVITY }
) : TouchpadListener, ScrollListener {

    // Position décimale du pointeur ; recalée sur [position] si elle a changé ailleurs.
    private var fx = 0f
    private var fy = 0f
    private var synced = false
    private var seenResets = 0
    private var dragging = false

    override fun onPointerMove(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        val m = mapper
        syncFromPosition(m)
        val s = sensitivity().coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        fx = clamp(fx + dx * s, m.width)
        fy = clamp(fy + dy * s, m.height)
        val px = fx.toInt()
        val py = fy.toInt()
        if (position.known && px == position.x && py == position.y) return // même pixel : rien à envoyer
        val mask = if (dragging) PointerButtons.LEFT else 0
        // Un déplacement est remplaçable ; le pixel n'est mémorisé que s'il est parti (sinon le suivant retente).
        if (sink.sendMove(ClientMessages.pointerEvent(mask, px, py))) position.set(px, py)
    }

    override fun onClick() {
        val (px, py) = current()
        sink.send(ClientMessages.leftClick(px, py))
    }

    override fun onRightClick() {
        val (px, py) = current()
        sink.send(ClientMessages.rightClick(px, py))
    }

    override fun onDragStart() {
        val (px, py) = current()
        dragging = sink.send(ClientMessages.dragStart(px, py))
    }

    override fun onDragEnd() {
        if (!dragging) return
        dragging = false
        val (px, py) = current()
        sink.send(ClientMessages.pointerEvent(0, px, py))
    }

    override fun onScrollStart(x: Float, y: Float) = Unit // en touchpad la molette agit là où est le pointeur

    override fun onScroll(clicksX: Int, clicksY: Int) {
        val (px, py) = current()
        sendScroll(sink, clicksX, clicksY, px, py)
    }

    /** Pixel courant du pointeur distant (recale la position décimale si besoin). */
    private fun current(): Pair<Int, Int> {
        val m = mapper
        syncFromPosition(m)
        val px = fx.toInt()
        val py = fy.toInt()
        position.set(px, py) // le message qui va partir porte cette position : le serveur y est ensuite
        return Pair(px, py)
    }

    /**
     * Repart de la position partagée quand elle a changé hors de ce mode (ou n'a jamais été connue : centre de l'écran).
     * La position décimale est alors placée au **milieu** du pixel pour ne pas perdre ni gagner un demi-pixel.
     */
    private fun syncFromPosition(m: PointerMapper) {
        if (position.resets != seenResets) { // nouvelle session : l'ancienne position n'a plus de sens
            seenResets = position.resets
            synced = false
        }
        if (!position.known) {
            if (!synced) { // aucune position envoyée : on repart du centre ; un déplacement refusé, lui, est conservé
                fx = m.width / 2f
                fy = m.height / 2f
                synced = true
            }
            return
        }
        if (!synced || fx.toInt() != position.x || fy.toInt() != position.y) {
            fx = clamp(position.x + 0.5f, m.width)
            fy = clamp(position.y + 0.5f, m.height)
            synced = true
        }
    }

    private fun clamp(v: Float, size: Int): Float = when {
        v <= 0f -> 0f
        v >= size -> size - EDGE
        else -> v
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 1.5f
        const val MIN_SENSITIVITY = 0.3f
        const val MAX_SENSITIVITY = 4.0f

        /** Juste sous la taille : le pixel du bord, jamais `taille`. */
        private const val EDGE = 0.001f
    }
}
