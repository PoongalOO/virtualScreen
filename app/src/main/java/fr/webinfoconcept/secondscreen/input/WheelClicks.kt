package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.PointerButtons

/**
 * Envoie des crans de molette signés à ([x], [y]) (SS-044) : [clicks] > 0 vers [positive], < 0 vers [negative], 0 rien. Un
 * message complet et **remplaçable** (`sendMove`) : une liaison lente perd des crans, jamais un relâchement de bouton.
 * Partagé par [PointerActions] et [TouchpadActions].
 */
internal fun sendWheelClicks(sink: MessageSink, clicks: Int, positive: Int, negative: Int, x: Int, y: Int) {
    if (clicks == 0) return
    val count = minOf(Math.abs(clicks), ClientMessages.MAX_WHEEL_CLICKS)
    sink.sendMove(ClientMessages.wheel(if (clicks > 0) positive else negative, count, x, y))
}

/** Crans verticaux puis horizontaux d'un [ScrollListener.onScroll]. */
internal fun sendScroll(sink: MessageSink, clicksX: Int, clicksY: Int, x: Int, y: Int) {
    sendWheelClicks(sink, clicksY, PointerButtons.WHEEL_DOWN, PointerButtons.WHEEL_UP, x, y)
    sendWheelClicks(sink, clicksX, PointerButtons.WHEEL_RIGHT, PointerButtons.WHEEL_LEFT, x, y)
}
