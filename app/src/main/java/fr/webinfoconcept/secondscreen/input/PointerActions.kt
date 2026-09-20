package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages

/**
 * Traduit une action de l'utilisateur en messages `PointerEvent` envoyés au serveur (SS-040, SS-041) : convertit la
 * position de la vue en pixel du framebuffer ([PointerMapper]), compose le message ([ClientMessages]) et le confie à
 * [PointerSender]. Appelée sur le thread UI ; ne bloque jamais.
 *
 * @param mapper taille du framebuffer distant ; à remplacer si elle change (redimensionnement).
 */
class PointerActions(@Volatile var mapper: PointerMapper, private val sender: PointerSender) {

    private val pixel = IntArray(2) // réutilisé : appelé depuis le seul thread UI

    /**
     * Clic gauche à la position ([viewX], [viewY]) de la vue.
     * @return `true` si le clic a été mis en file ; `false` s'il est hors du framebuffer ou refusé par l'envoyeur.
     */
    fun tap(viewX: Float, viewY: Float): Boolean {
        if (!mapper.map(viewX, viewY, pixel)) return false
        return sender.send(ClientMessages.leftClick(pixel[0], pixel[1]))
    }
}
