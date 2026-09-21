package fr.webinfoconcept.secondscreen.rfb.robustness

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.testutil.rectHeader
import fr.webinfoconcept.secondscreen.rfb.testutil.u16
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.testutil.updateHeader
import java.io.IOException
import java.util.Random

/*
 * Outils des tests de robustesse (SS-070) : un serveur hostile, c'est-à-dire des octets arbitraires, tronqués ou dont un
 * champ vaut une valeur limite. La propriété testée est toujours la même : le client **ne lève que des erreurs typées**
 * (IOException : RfbProtocolException ou RfbTransportException), n'écrit jamais hors du rectangle annoncé, n'alloue pas en
 * fonction d'une longueur annoncée, et **termine**.
 */

/** Les valeurs qui cassent les parseurs : bornes de types, de plages et de budgets. */
internal val HOSTILE_BYTES = intArrayOf(0x00, 0x01, 0x02, 0x0F, 0x10, 0x7F, 0x80, 0xE0, 0xFE, 0xFF)

/** Une IOException typée si [block] en lève une ; toute autre exception échoue le test en disant quelle entrée l'a causée. */
internal fun typedFailure(what: () -> String, block: () -> Unit): IOException? = try {
    block()
    null
} catch (e: IOException) {
    e
} catch (t: Throwable) {
    throw AssertionError("exception NON typée ${t.javaClass.name} (${t.message}) pour ${what()}", t)
}

internal fun hex(bytes: ByteArray, max: Int = 40): String =
    bytes.take(max).joinToString(" ") { "%02x".format(it) } + if (bytes.size > max) " …(${bytes.size} octets)" else ""

/** Framebuffer dont aucun pixel n'est noir ni égal à un voisin : toute écriture ou copie parasite se voit. */
internal fun patterned(width: Int, height: Int): Framebuffer {
    val fb = Framebuffer(width, height)
    for (y in 0 until height) for (x in 0 until width) {
        fb.pixels[y * width + x] = (0xFF shl 24) or ((x + 1) shl 16) or ((y + 1) shl 8) or 0x7F
    }
    return fb
}

internal fun randomBytes(random: Random, n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

/** Un flux valide dont quelques octets sont remplacés, retirés, insérés, ou le flux tronqué. */
internal fun mutate(random: Random, valid: ByteArray): ByteArray {
    var out = valid.copyOf()
    repeat(1 + random.nextInt(4)) {
        if (out.isEmpty()) return out
        val at = random.nextInt(out.size)
        when (random.nextInt(6)) {
            0 -> out[at] = (out[at].toInt() xor (1 shl random.nextInt(8))).toByte()          // un bit
            1 -> out[at] = HOSTILE_BYTES[random.nextInt(HOSTILE_BYTES.size)].toByte()        // une valeur limite
            2 -> out[at] = random.nextInt(256).toByte()                                       // un octet quelconque
            3 -> out = out.copyOfRange(0, at)                                                 // tronqué
            4 -> out = out.copyOfRange(0, at) + out.copyOfRange(at + 1, out.size)             // un octet en moins : décale tout
            else -> out = out.copyOfRange(0, at) + byteArrayOf(random.nextInt(256).toByte()) + out.copyOfRange(at, out.size)
        }
    }
    return out
}

/** Un flux de messages serveur valide, avec les trois encodages (RAW, CopyRect, Hextile de tous les types de tuile), un Bell et un texte. */
internal fun validServerStream(random: Random): ByteArray {
    fun raw(w: Int, h: Int) = randomBytes(random, w * h * 4)
    val hextile =
        byteArrayOf(0x01) + raw(16, 16) +                                                   // tuile 16x16 brute
            byteArrayOf(0x02 or 0x08 or 0x10) + raw(1, 1) + byteArrayOf(2) +               // 4x16 : fond, 2 sous-rectangles colorés
            raw(1, 1) + byteArrayOf(0x00, 0x33) + raw(1, 1) + byteArrayOf(0x24, 0x11) +
            byteArrayOf(0x04 or 0x08) + raw(1, 1) + byteArrayOf(1) + byteArrayOf(0x00, 0x33) + // 16x4 : premier plan, 1 sous-rectangle
            byteArrayOf(0x00)                                                                  // 4x4 : fond seul
    val update1 = updateHeader(3) +
        rectHeader(5, 6, 4, 3, 0) + raw(4, 3) +
        rectHeader(10, 10, 8, 8, 1) + u16(0) + u16(0) +
        rectHeader(0, 0, 20, 20, 5) + hextile
    val text = "presse-papiers".toByteArray()
    return update1 + byteArrayOf(2) + byteArrayOf(3, 0, 0, 0) + u32(text.size.toLong()) + text + updateHeader(0)
}
