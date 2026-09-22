package fr.webinfoconcept.secondscreen.perf

import fr.webinfoconcept.secondscreen.rfb.protocol.EncodingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random

/**
 * SS-071 : la seule ligne de journal de l'application. Elle est fabriquée à partir de nombres et d'une énumération : **rien
 * d'autre ne peut y entrer**. Le test le prouve en la comparant à une grammaire stricte, avec des valeurs extrêmes.
 */
class PerfLogLineTest {

    private val number = """(-?\d+(\.\d+)?|NaN|-?Infinity)"""
    private val field = """[a-z_/]+=$number(\(max $number\))?"""
    private val grammar = Regex("""$field( $field)* enc=(auto|hextile|raw)""")

    private fun snapshot(random: Random?): PerfSnapshot {
        fun f(): Float = if (random == null) Float.NaN else random.nextInt(8).let { when (it) { 0 -> Float.NaN; 1 -> Float.POSITIVE_INFINITY; 2 -> Float.NEGATIVE_INFINITY; 3 -> Float.MAX_VALUE; 4 -> -Float.MAX_VALUE; else -> random.nextFloat() * 1000 } }
        fun l(): Long = if (random == null) Long.MIN_VALUE else when (random.nextInt(5)) { 0 -> Long.MIN_VALUE; 1 -> Long.MAX_VALUE; 2 -> 0; else -> random.nextLong() }
        return PerfSnapshot(f(), f(), f(), l(), l(), f(), f(), f(), f(), f(), f(), l(), l(), l(), random?.nextInt() ?: Int.MIN_VALUE, l(),
            l(), l(), l(), f(), l(), l(), l())
    }

    @Test
    fun `the line is only numbers and the key of an encoding, whatever the values`() {
        val random = Random(71)
        for (mode in EncodingMode.values()) {
            repeat(2_000) {
                val line = PerfLogLine.format(snapshot(if (it == 0) null else random), mode)
                assertTrue("ligne hors grammaire : $line", grammar.matches(line))
                assertTrue("ligne trop longue (${line.length}) : $line", line.length < 1_000) // bornée : des nombres, même extrêmes (Float.MAX_VALUE = 39 chiffres)
                assertTrue("l'encodage est celui demandé : $line", line.endsWith("enc=${mode.key}"))
            }
        }
    }

    @Test
    fun `the decimal separator is a dot whatever the language of the tablet`() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.FRANCE, Locale.GERMANY, Locale("ar"), Locale.JAPAN)) {
                Locale.setDefault(locale)
                val line = PerfLogLine.format(PerfSnapshot.EMPTY.copy(updatesPerSecond = 12.5f), EncodingMode.AUTO)
                assertTrue("$locale : $line", line.startsWith("maj/s=12.5 "))
                assertTrue("$locale : $line", grammar.matches(line))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the tag is the one the analysis scripts read`() {
        assertEquals("SecondScreenPerf", PerfLogLine.TAG)
    }
}
