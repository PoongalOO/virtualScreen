package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.PointerButtons

/**
 * Traduit une action de l'utilisateur en messages `PointerEvent` envoyés au serveur (SS-040, SS-041, SS-042, SS-043) : convertit
 * la position de la vue en pixel du framebuffer ([PointerMapper]), compose le message ([ClientMessages]) et le confie à
 * un [MessageSink]. Appelée sur le thread UI ; ne bloque jamais.
 *
 * **Glissement** ([DragListener]) : `onDragStart` envoie survol + appui au pixel de départ, chaque `onDragMove` un
 * `PointerEvent` bouton enfoncé, `onDragEnd` le relâchement. Garde-fous :
 * - si l'appui **n'a pas pu être mis en file** (départ hors du framebuffer, file pleine), tout le glissement est
 *   ignoré : un déplacement bouton enfoncé serait interprété par le serveur comme un appui n'importe où ;
 * - un déplacement qui ne change pas le pixel visé n'envoie rien ;
 * - pendant le glissement la position est **bornée** au framebuffer (le doigt peut sortir du cadre) ;
 * - le relâchement est un message d'état, jamais abandonné avant les déplacements (voir [PointerSender]).
 *
 * **Défilement** ([ScrollListener], SS-044) : chaque cran est un appui puis un relâchement d'un bouton de molette
 * (`ClientMessages.wheel`), envoyé à la position du **centre des deux doigts au début du geste**, bornée au
 * framebuffer : c'est la fenêtre sous les doigts au départ qui défile, même si le centre traverse ensuite d'autres
 * fenêtres. Aucun bouton ordinaire n'est touché. Un message de molette est complet (chaque appui suivi de son
 * relâchement) et **remplaçable** : il passe par [MessageSink.sendMove], donc une liaison lente perd des crans,
 * jamais un relâchement de bouton ni la place réservée aux messages d'état.
 *
 * @param mapper taille du framebuffer distant ; à remplacer si elle change (redimensionnement).
 */
class PointerActions(@Volatile var mapper: PointerMapper, private val sender: MessageSink) :
    DragListener, ScrollListener {

    private val pixel = IntArray(2) // réutilisé : appelé depuis le seul thread UI
    private var dragging = false
    private var lastX = -1
    private var lastY = -1
    private var scrolling = false
    private var scrollPixelX = 0
    private var scrollPixelY = 0

    /**
     * Clic gauche à la position ([viewX], [viewY]) de la vue.
     * @return `true` si le clic a été mis en file ; `false` s'il est hors du framebuffer ou refusé par l'envoyeur.
     */
    fun tap(viewX: Float, viewY: Float): Boolean {
        if (!mapper.map(viewX, viewY, pixel)) return false
        return sender.send(ClientMessages.leftClick(pixel[0], pixel[1]))
    }

    /**
     * Clic droit à la position ([viewX], [viewY]) de la vue (appui long, SS-043). Le bouton gauche n'est jamais
     * enfoncé. Mêmes garde-fous que [tap].
     * @return `true` si le clic a été mis en file.
     */
    fun rightClick(viewX: Float, viewY: Float): Boolean {
        if (!mapper.map(viewX, viewY, pixel)) return false
        return sender.send(ClientMessages.rightClick(pixel[0], pixel[1]))
    }

    override fun onScrollStart(x: Float, y: Float) {
        scrolling = mapper.mapClamped(x, y, pixel)
        if (scrolling) {
            scrollPixelX = pixel[0]
            scrollPixelY = pixel[1]
        }
    }

    override fun onScroll(clicksX: Int, clicksY: Int) {
        if (!scrolling) return
        sendWheel(clicksY, PointerButtons.WHEEL_DOWN, PointerButtons.WHEEL_UP)
        sendWheel(clicksX, PointerButtons.WHEEL_RIGHT, PointerButtons.WHEEL_LEFT)
    }

    /** [clicks] > 0 : [positive] ; < 0 : [negative] ; 0 : rien. */
    private fun sendWheel(clicks: Int, positive: Int, negative: Int) {
        if (clicks == 0) return
        val count = minOf(Math.abs(clicks), ClientMessages.MAX_WHEEL_CLICKS)
        sender.sendMove(ClientMessages.wheel(if (clicks > 0) positive else negative, count, scrollPixelX, scrollPixelY))
    }

    override fun onDragStart(x: Float, y: Float) {
        dragging = false
        if (!mapper.map(x, y, pixel)) return
        if (!sender.send(ClientMessages.dragStart(pixel[0], pixel[1]))) return
        dragging = true
        lastX = pixel[0]
        lastY = pixel[1]
    }

    override fun onDragMove(x: Float, y: Float) {
        if (!dragging || !mapper.mapClamped(x, y, pixel)) return
        if (pixel[0] == lastX && pixel[1] == lastY) return
        // Même si le déplacement est abandonné (liaison lente), lastX/lastY ne sont mis à jour que s'il est parti :
        // le suivant, au même pixel, sera de nouveau tenté.
        if (sender.sendMove(ClientMessages.pointerEvent(PointerButtons.LEFT, pixel[0], pixel[1]))) {
            lastX = pixel[0]
            lastY = pixel[1]
        }
    }

    override fun onDragEnd(x: Float, y: Float) {
        if (!dragging) return
        dragging = false
        // Position finale : celle du relâchement si elle est valide, sinon la dernière envoyée.
        val valid = mapper.mapClamped(x, y, pixel)
        sender.send(ClientMessages.pointerEvent(0, if (valid) pixel[0] else lastX, if (valid) pixel[1] else lastY))
    }
}
