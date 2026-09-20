package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.PointerButtons

/**
 * Traduit une action de l'utilisateur en messages `PointerEvent` envoyés au serveur (SS-040, SS-041, SS-042) : convertit
 * la position de la vue en pixel du framebuffer ([PointerMapper]), compose le message ([ClientMessages]) et le confie à
 * [PointerSender]. Appelée sur le thread UI ; ne bloque jamais.
 *
 * **Glissement** ([DragListener]) : `onDragStart` envoie survol + appui au pixel de départ, chaque `onDragMove` un
 * `PointerEvent` bouton enfoncé, `onDragEnd` le relâchement. Garde-fous :
 * - si l'appui **n'a pas pu être mis en file** (départ hors du framebuffer, file pleine), tout le glissement est
 *   ignoré : un déplacement bouton enfoncé serait interprété par le serveur comme un appui n'importe où ;
 * - un déplacement qui ne change pas le pixel visé n'envoie rien ;
 * - pendant le glissement la position est **bornée** au framebuffer (le doigt peut sortir du cadre) ;
 * - le relâchement est un message d'état, jamais abandonné avant les déplacements (voir [PointerSender]).
 *
 * @param mapper taille du framebuffer distant ; à remplacer si elle change (redimensionnement).
 */
class PointerActions(@Volatile var mapper: PointerMapper, private val sender: PointerSender) : DragListener {

    private val pixel = IntArray(2) // réutilisé : appelé depuis le seul thread UI
    private var dragging = false
    private var lastX = -1
    private var lastY = -1

    /**
     * Clic gauche à la position ([viewX], [viewY]) de la vue.
     * @return `true` si le clic a été mis en file ; `false` s'il est hors du framebuffer ou refusé par l'envoyeur.
     */
    fun tap(viewX: Float, viewY: Float): Boolean {
        if (!mapper.map(viewX, viewY, pixel)) return false
        return sender.send(ClientMessages.leftClick(pixel[0], pixel[1]))
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
