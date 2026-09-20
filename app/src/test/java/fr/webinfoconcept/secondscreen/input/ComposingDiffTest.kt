package fr.webinfoconcept.secondscreen.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texte provisoire des claviers virtuels (SS-046) : ce qu'il faut envoyer pour que le serveur reflète l'état courant. */
class ComposingDiffTest {
    private val diff = ComposingDiff()

    private fun ComposingDiff.Edit.text() = String(insert, 0, insert.size)

    @Test
    fun `growing composition sends only the new characters`() {
        val a = diff.update("b"); assertEquals(0, a.backspaces); assertEquals("b", a.text())
        val b = diff.update("bo"); assertEquals(0, b.backspaces); assertEquals("o", b.text())
        val c = diff.update("bon"); assertEquals(0, c.backspaces); assertEquals("n", c.text())
        assertEquals(3, diff.length)
    }

    @Test
    fun `a correction erases the differing tail then types the new one`() {
        diff.update("bonjoru")

        val edit = diff.update("bonjour")

        assertEquals("r et u remplacés par u et r : 2 effacements", 2, edit.backspaces)
        assertEquals("ur", edit.text())
    }

    @Test
    fun `replacing a whole word erases it entirely`() {
        diff.update("hello")

        val edit = diff.update("salut")

        assertEquals(5, edit.backspaces)
        assertEquals("salut", edit.text())
    }

    @Test
    fun `shrinking the composition only erases`() {
        diff.update("abcd")

        val edit = diff.update("ab")

        assertEquals(2, edit.backspaces)
        assertEquals("", edit.text())
    }

    @Test
    fun `an identical proposal changes nothing`() {
        diff.update("abc")

        assertTrue(diff.update("abc").isEmpty)
    }

    @Test
    fun `clearing the composition erases all of it`() {
        diff.update("abc")

        val edit = diff.update("")

        assertEquals(3, edit.backspaces)
        assertFalse(diff.isComposing)
    }

    @Test
    fun `finish keeps the text on screen and starts a new composition from scratch`() {
        diff.update("abc")
        diff.finish()

        val edit = diff.update("d")

        assertEquals("rien à effacer : abc est figé", 0, edit.backspaces)
        assertEquals("d", edit.text())
    }

    @Test
    fun `counts are in code points, not UTF-16 units`() {
        diff.update("a😀")
        assertEquals(2, diff.length)

        val edit = diff.update("a")

        assertEquals("un seul effacement pour un emoji de deux unités UTF-16", 1, edit.backspaces)
    }

    @Test
    fun `characters without a keysym are neither sent nor counted`() {
        val edit = diff.update("a\u0001b\u0090")

        assertEquals("ab", edit.text())
        assertEquals(2, diff.length)
        assertEquals("le retrait de b n'efface qu'un caractère réellement envoyé", 1, diff.update("a\u0001").backspaces)
    }

    @Test
    fun `reset forgets everything without producing an edit`() {
        diff.update("abc")
        diff.reset()

        assertFalse(diff.isComposing)
        assertEquals(0, diff.update("").backspaces)
    }

    @Test
    fun `sendable keeps the order and drops invalid code points`() {
        assertArrayEquals(intArrayOf('a'.code, 0xE9, 0x20AC), ComposingDiff.sendable("a\u0000é\u009f€"))
        assertArrayEquals(intArrayOf(), ComposingDiff.sendable(""))
        assertArrayEquals(intArrayOf(0x1F600), ComposingDiff.sendable("😀"))
        // une moitié de paire de substitution isolée n'est pas un caractère
        assertArrayEquals(intArrayOf('a'.code), ComposingDiff.sendable("a\uD83D"))
    }

    @Test
    fun `a random typing session always leaves the remote text equal to the last proposal`() {
        val rnd = java.util.Random(3)
        val alphabetCodePoints = intArrayOf('a'.code, 'b'.code, 'c'.code, 'é'.code, 0x20AC, 0x1F600, ' '.code, 0x01) // 0x01 : sans keysym
        repeat(300) {
            val remote = StringBuilder() // ce que le serveur afficherait
            val d = ComposingDiff()
            var lastProposal = ""
            repeat(30) {
                val proposal = buildString {
                    repeat(rnd.nextInt(6)) { appendCodePoint(alphabetCodePoints[rnd.nextInt(alphabetCodePoints.size)]) }
                }
                val cps = ComposingDiff.sendable(proposal)
                val edit = d.update(proposal)
                repeat(edit.backspaces) { remote.setLength(remote.length - Character.charCount(remote.codePointBefore(remote.length))) }
                remote.append(edit.text())
                lastProposal = String(cps, 0, cps.size)
                assertEquals(lastProposal, remote.toString())
                if (rnd.nextInt(5) == 0) { d.finish(); remote.setLength(0); lastProposal = "" } // validé : hors de portée des effacements
            }
        }
    }
}
