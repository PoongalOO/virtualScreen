package fr.webinfoconcept.secondscreen.rfb.protocol

/*
 * Utilitaires d'analyse de messages RFB déjà lus en mémoire (SS-013).
 * RFB est big-endian ; toutes les valeurs sont lues NON SIGNÉES.
 */

/** U8 à l'index [i], 0..255. */
internal fun ByteArray.u8At(i: Int): Int = this[i].toInt() and 0xFF

/** U16 big-endian à l'index [i], 0..65535. */
internal fun ByteArray.u16At(i: Int): Int = (u8At(i) shl 8) or u8At(i + 1)

/** U32 big-endian à l'index [i], 0..4 294 967 295 (d'où `Long` : jamais négatif). */
internal fun ByteArray.u32At(i: Int): Long =
    (u8At(i).toLong() shl 24) or (u8At(i + 1).toLong() shl 16) or (u8At(i + 2).toLong() shl 8) or u8At(i + 3).toLong()

/**
 * Texte reçu du serveur, rendu sûr pour l'affichage : seul l'ASCII imprimable
 * (0x20..0x7E) est conservé, tout le reste (contrôles, UTF-8, binaire) devient `?`.
 * Un octet = un caractère, donc la longueur est celle de l'entrée.
 */
internal fun sanitizeServerText(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        out.append(if (c in 0x20..0x7E) c.toChar() else '?')
    }
    return out.toString()
}
