package fr.webinfoconcept.secondscreen.rfb.protocol

/** Message serveur lu par [ServerMessageReader]. */
sealed class ServerMessage {
    /** Un `FramebufferUpdate` de [rectangles] rectangles a été décodé dans le framebuffer. */
    class FramebufferUpdated(val rectangles: Int) : ServerMessage()

    /** `Bell` : rien à lire, l'application peut l'ignorer. */
    object Bell : ServerMessage()

    /** `ServerCutText` : [length] octets de texte lus et **ignorés** (presse-papiers non géré dans le MVP). */
    class CutTextIgnored(val length: Int) : ServerMessage()
}

/**
 * Notifié après chaque rectangle décodé d'un `FramebufferUpdate`, dans l'ordre du message, sur le
 * thread I/O. Permet au rendu (SS-031) de retenir les zones modifiées sans que le décodeur
 * connaisse l'affichage. Ne doit pas allouer ni bloquer.
 */
interface RectangleListener {
    fun onRectangle(x: Int, y: Int, w: Int, h: Int)
}
