package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket

/**
 * Lecture des types primitifs RFB sur une [RfbSocket] (SS-012).
 *
 * RFB est **big-endian** sur le réseau. Les valeurs non signées sont renvoyées
 * dans un type assez large pour ne jamais devenir négatives : `U8` → `Int`
 * 0..255, `U32` → `Long` 0..4 294 967 295. Un `U32` reçu du serveur est une
 * donnée non fiable : l'appelant doit le borner **avant** de s'en servir comme
 * taille ou de le convertir en `Int`.
 *
 * Non thread-safe (un seul lecteur par connexion, l'I/O worker) : le petit buffer
 * interne est réutilisé pour éviter toute allocation par lecture.
 */
internal class RfbReader(private val socket: RfbSocket) {

    private val scratch = ByteArray(4)

    fun readU8(): Int {
        socket.readFully(scratch, 0, 1)
        return scratch[0].toInt() and 0xFF
    }

    fun readU32(): Long {
        socket.readFully(scratch, 0, 4)
        return ((scratch[0].toLong() and 0xFF) shl 24) or
            ((scratch[1].toLong() and 0xFF) shl 16) or
            ((scratch[2].toLong() and 0xFF) shl 8) or
            (scratch[3].toLong() and 0xFF)
    }

    /**
     * Lit exactement [length] octets dans un nouveau tableau. [length] doit avoir été
     * borné par l'appelant : jamais une valeur brute du réseau.
     */
    fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "longueur négative : $length" }
        val out = ByteArray(length)
        socket.readFully(out, 0, length)
        return out
    }
}
