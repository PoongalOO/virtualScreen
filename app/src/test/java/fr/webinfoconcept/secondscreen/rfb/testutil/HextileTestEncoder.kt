package fr.webinfoconcept.secondscreen.rfb.testutil

import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * « Bureau » de test pour Hextile, déterministe et fonction des seules coordonnées écran : des régions de
 * 64×64 alternent uniforme / deux couleurs / quatre couleurs / bruit, donc un encodeur y produit tous les
 * sous-encodages. Même formule côté serveur Python de la sonde matérielle.
 */
fun hextilePixel(x: Int, y: Int): Int {
    val region = (x / 64 + y / 64) % 4
    val r: Int
    val g: Int
    val b: Int
    when (region) {
        0 -> { r = (x / 64 * 40 + 20) and 255; g = (y / 64 * 30 + 10) and 255; b = 90 }
        1 -> if (((x + y) / 3) % 2 == 0) { r = 230; g = 230; b = 40 } else { r = 20; g = 20; b = 120 }
        2 -> when (((x / 5) + (y / 5)) % 4) {
            0 -> { r = 200; g = 0; b = 0 }
            1 -> { r = 0; g = 200; b = 0 }
            2 -> { r = 0; g = 0; b = 200 }
            else -> { r = 200; g = 200; b = 200 }
        }
        else -> { r = (x * 7 + y * 3) and 255; g = (x xor y) and 255; b = (x + y * 5) and 255 }
    }
    return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
}

/**
 * Encodeur Hextile côté test (format de pixels imposé : 4 octets `[B, G, R, 0]`), écrit à part du
 * décodeur : il suit la spec RFB, notamment la persistance du fond et du premier plan d'une tuile à l'autre.
 * Sans [random] il n'omet un fond/premier plan que s'il est identique à celui de la tuile précédente ; avec
 * [random] il choisit aussi au hasard de le répéter inutilement et de colorer des sous-rectangles à deux
 * couleurs, pour couvrir plus de combinaisons.
 *
 * [maskCounts] compte les sous-encodages réellement produits : un test doit y vérifier que chaque cas a été
 * exercé, sinon il pourrait passer en n'ayant testé que des tuiles unies.
 */
class HextileTestEncoder(private val random: Random? = null) {

    val maskCounts = IntArray(256)

    private companion object {
        const val RAW = 1
        const val BG = 2
        const val FG = 4
        const val ANY = 8
        const val COLOURED = 16
    }

    fun encode(pixel: (Int, Int) -> Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var prevBg: Int? = null // au début d'un rectangle la spec exige de préciser le fond de la 1re tuile non brute
        var prevFg: Int? = null

        var ty = 0
        while (ty < h) {
            val th = minOf(16, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(16, w - tx)
                val tile = IntArray(tw * th) { pixel(x + tx + it % tw, y + ty + it / tw) }
                val counts = LinkedHashMap<Int, Int>()
                for (p in tile) counts[p] = (counts[p] ?: 0) + 1

                if (counts.size > 8) {
                    emit(out, RAW, wire(tile))
                } else {
                    val bg = counts.maxByOrNull { it.value }!!.key
                    val forceBg = random?.nextInt(3) == 0
                    var mask = 0
                    val body = ByteArrayOutputStream()

                    if (bg != prevBg || forceBg) { mask = mask or BG; body.write(wire(intArrayOf(bg))); prevBg = bg }

                    if (counts.size > 1) {
                        val coloured = counts.size > 2 || random?.nextBoolean() == true
                        val runs = runsOfNonBackground(tile, tw, th, bg)
                        mask = mask or ANY
                        val subrects = ByteArrayOutputStream()
                        if (coloured) {
                            mask = mask or COLOURED
                        } else {
                            val fg = counts.keys.first { it != bg }
                            if (fg != prevFg || random?.nextInt(3) == 0) { mask = mask or FG; body.write(wire(intArrayOf(fg))); prevFg = fg }
                        }
                        for (run in runs) {
                            if (coloured) subrects.write(wire(intArrayOf(run.colour)))
                            subrects.write((run.x shl 4) or run.y)
                            subrects.write(((run.w - 1) shl 4) or (run.h - 1))
                        }
                        body.write(runs.size)
                        body.write(subrects.toByteArray())
                    }
                    emit(out, mask, body.toByteArray())
                }
                tx += 16
            }
            ty += 16
        }
        return out.toByteArray()
    }

    private class Run(val x: Int, val y: Int, val w: Int, val h: Int, val colour: Int)

    /** Séquences horizontales de pixels d'une même couleur différente du fond, dans l'ordre des lignes. */
    private fun runsOfNonBackground(tile: IntArray, tw: Int, th: Int, bg: Int): List<Run> {
        val runs = ArrayList<Run>()
        for (row in 0 until th) {
            var col = 0
            while (col < tw) {
                val colour = tile[row * tw + col]
                if (colour == bg) { col++; continue }
                var len = 1
                while (col + len < tw && tile[row * tw + col + len] == colour) len++
                runs += Run(col, row, len, 1, colour)
                col += len
            }
        }
        return runs
    }

    private fun emit(out: ByteArrayOutputStream, mask: Int, body: ByteArray) {
        maskCounts[mask]++
        out.write(mask)
        out.write(body)
    }

    private fun wire(pixels: IntArray): ByteArray = rawXrgb(pixels)
}
