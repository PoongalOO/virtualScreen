package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Frappes envoyées au serveur (SS-046 texte, SS-047 touches spéciales) : ce que le serveur reçoit, message par message.
 * Un « serveur simulé » rejoue les événements et suit l'état de chaque touche.
 */
class KeyboardInputTest {

    /** Événement de touche décodé : « v » = enfoncée, « ^ » = relâchée, suivi du keysym en hexadécimal. */
    private fun decode(messages: List<ByteArray>): List<String> = messages.flatMap { m ->
        assertEquals("message de touches multiple de 8 octets", 0, m.size % 8)
        (0 until m.size step 8).map { i ->
            assertEquals("type KeyEvent", 4, m[i].toInt())
            val keysym = ((m[i + 4].toLong() and 255) shl 24) or ((m[i + 5].toLong() and 255) shl 16) or
                ((m[i + 6].toLong() and 255) shl 8) or (m[i + 7].toLong() and 255)
            (if (m[i + 1].toInt() == 1) "v" else "^") + keysym.toString(16)
        }
    }

    private class RecordingSink : MessageSink {
        val messages = mutableListOf<ByteArray>()
        var accept = true
        override fun send(message: ByteArray): Boolean { if (accept) messages += message; return accept }
        override fun sendMove(message: ByteArray): Boolean = send(message)
    }

    private val sink = RecordingSink()
    private val kb = KeyboardInput(sink)

    private fun events() = decode(sink.messages)
    private fun press(c: Char) = listOf("v" + c.code.toString(16), "^" + c.code.toString(16))
    private fun press(keysym: Int) = listOf("v" + keysym.toString(16), "^" + keysym.toString(16))

    // ================================================================== texte (SS-046)

    @Test
    fun `typing ascii sends one press per character, in order`() {
        assertEquals(5, kb.typeText("Hello"))

        assertEquals(press('H') + press('e') + press('l') + press('l') + press('o'), events())
    }

    @Test
    fun `typing Latin-1 sends the character code itself`() {
        kb.typeText("éà£Ç")

        assertEquals(press(0xE9) + press(0xE0) + press(0xA3) + press(0xC7), events())
    }

    @Test
    fun `characters above Latin-1 use Unicode keysyms`() {
        kb.typeText("€œ")

        assertEquals(press(0x010020AC) + press(0x01000153), events())
    }

    @Test
    fun `an emoji is one key press with a Unicode keysym, not two`() {
        assertEquals(1, kb.typeText("😀"))

        assertEquals(press(0x0101F600), events())
    }

    @Test
    fun `newline is Return and tab is Tab`() {
        kb.typeText("a\nb\tc")

        assertEquals(press('a') + press(Keysyms.RETURN) + press('b') + press(Keysyms.TAB) + press('c'), events())
    }

    @Test
    fun `characters without a keysym are skipped and not counted`() {
        assertEquals(2, kb.typeText("a\u0001\u0090b\uD83D"))

        assertEquals(press('a') + press('b'), events())
    }

    @Test
    fun `an empty text sends nothing`() {
        assertEquals(0, kb.typeText(""))
        assertEquals(0, kb.typeText("\u0000\u0001"))

        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun `a whole text goes in as few messages as possible, each below the limit`() {
        val text = "x".repeat(ClientMessages.MAX_KEY_PRESSES * 2 + 5)

        assertEquals(text.length, kb.typeText(text))

        assertEquals("3 messages : 32 + 32 + 5", 3, sink.messages.size)
        assertTrue(sink.messages.all { it.size <= ClientMessages.KEY_PRESS_LENGTH * ClientMessages.MAX_KEY_PRESSES })
        assertEquals(text.length * 2, events().size)
    }

    @Test
    fun `nothing is counted as typed when the message could not be sent`() {
        sink.accept = false

        assertEquals(0, kb.typeText("abc"))
    }

    // ================================================================== modificateurs (SS-047)

    @Test
    fun `a modifier toggle presses the key at once and a second toggle releases it`() {
        assertTrue(kb.toggleModifier(KeyboardInput.Modifier.CTRL))
        assertEquals(listOf("v" + Keysyms.CONTROL_L.toString(16)), events())
        assertTrue(kb.isHeld(KeyboardInput.Modifier.CTRL))

        assertFalse(kb.toggleModifier(KeyboardInput.Modifier.CTRL))
        assertEquals(listOf("v" + Keysyms.CONTROL_L.toString(16), "^" + Keysyms.CONTROL_L.toString(16)), events())
        assertFalse(kb.anyModifierHeld)
    }

    @Test
    fun `Ctrl then a letter is Ctrl+letter, and Ctrl is released after that one key`() {
        kb.toggleModifier(KeyboardInput.Modifier.CTRL)

        kb.typeText("c")

        val ctrl = Keysyms.CONTROL_L.toString(16)
        assertEquals(listOf("v$ctrl") + press('c') + listOf("^$ctrl"), events())
        assertFalse(kb.anyModifierHeld)
    }

    @Test
    fun `a modifier applies only to the first character of a longer text`() {
        kb.toggleModifier(KeyboardInput.Modifier.ALT)

        kb.typeText("abc")

        val alt = Keysyms.ALT_L.toString(16)
        assertEquals(listOf("v$alt") + press('a') + listOf("^$alt") + press('b') + press('c'), events())
    }

    @Test
    fun `Shift turns lowercase ASCII letters into capitals and leaves the rest alone`() {
        kb.toggleModifier(KeyboardInput.Modifier.SHIFT)
        kb.typeText("a")
        kb.toggleModifier(KeyboardInput.Modifier.SHIFT)
        kb.typeText("é")
        kb.toggleModifier(KeyboardInput.Modifier.SHIFT)
        kb.typeText("1")

        val e = events()
        assertTrue("A majuscule sous Maj : $e", e.contains("v41"))
        assertFalse(e.contains("v61"))
        assertTrue("é inchangé", e.contains("ve9"))
        assertTrue("1 inchangé", e.contains("v31"))
    }

    @Test
    fun `several modifiers are all released after the key, in reverse Modifier order`() {
        kb.toggleModifier(KeyboardInput.Modifier.CTRL)
        kb.toggleModifier(KeyboardInput.Modifier.ALT)

        kb.pressKey(Keysyms.DELETE)

        val ctrl = Keysyms.CONTROL_L.toString(16)
        val alt = Keysyms.ALT_L.toString(16)
        assertEquals(listOf("v$ctrl", "v$alt") + press(Keysyms.DELETE) + listOf("^$alt", "^$ctrl"), events())
    }

    @Test
    fun `a special key with no modifier is a plain press`() {
        kb.pressKey(Keysyms.ESCAPE)
        kb.pressKey(Keysyms.TAB)
        kb.pressKey(Keysyms.RETURN)
        kb.pressKey(Keysyms.BACKSPACE)

        assertEquals(press(Keysyms.ESCAPE) + press(Keysyms.TAB) + press(Keysyms.RETURN) + press(Keysyms.BACKSPACE), events())
    }

    @Test
    fun `pressing a modifier key itself does not release the held modifiers`() {
        kb.toggleModifier(KeyboardInput.Modifier.CTRL)

        kb.pressKey(Keysyms.SHIFT_L)

        assertTrue(kb.isHeld(KeyboardInput.Modifier.CTRL))
    }

    @Test
    fun `a modifier is not remembered when its message was not sent`() {
        sink.accept = false

        assertFalse(kb.toggleModifier(KeyboardInput.Modifier.CTRL))

        assertFalse(kb.anyModifierHeld)
    }

    @Test
    fun `releaseModifiers releases exactly what is held, and only once`() {
        kb.toggleModifier(KeyboardInput.Modifier.SHIFT)
        kb.toggleModifier(KeyboardInput.Modifier.CTRL)
        sink.messages.clear()

        kb.releaseModifiers()
        kb.releaseModifiers()

        assertEquals(listOf("^" + Keysyms.SHIFT_L.toString(16), "^" + Keysyms.CONTROL_L.toString(16)), events())
        assertFalse(kb.anyModifierHeld)
    }

    @Test
    fun `reset forgets the modifiers without sending anything`() {
        kb.toggleModifier(KeyboardInput.Modifier.CTRL)
        sink.messages.clear()

        kb.reset()

        assertFalse(kb.anyModifierHeld)
        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun `the listener hears every change of the modifier state, and nothing else`() {
        val heard = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        kb.listener = object : KeyboardInput.Listener {
            override fun onModifiersChanged(ctrl: Boolean, alt: Boolean, shift: Boolean) { heard += Triple(ctrl, alt, shift) }
        }

        kb.toggleModifier(KeyboardInput.Modifier.CTRL)
        kb.typeText("c") // relâche Ctrl
        kb.typeText("d") // rien ne change
        kb.reset()       // rien n'est tenu

        assertEquals(listOf(Triple(true, false, false), Triple(false, false, false)), heard)
    }

    // ================================================================== texte provisoire

    @Test
    fun `composing text is sent as it grows and corrected with backspaces`() {
        kb.setComposingText("bonjoru")
        kb.setComposingText("bonjour")
        kb.commitText("bonjour")

        val expected = "bonjoru".map { press(it) }.flatten() +
            press(Keysyms.BACKSPACE) + press(Keysyms.BACKSPACE) + press('u') + press('r')
        assertEquals(expected, events())
    }

    @Test
    fun `committing the same text that was composed sends nothing more`() {
        kb.setComposingText("ok")
        sink.messages.clear()

        kb.commitText("ok")

        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun `committing a different text replaces the composition`() {
        kb.setComposingText("teh")
        sink.messages.clear()

        kb.commitText("the")

        assertEquals(press(Keysyms.BACKSPACE) + press(Keysyms.BACKSPACE) + press('h') + press('e'), events())
    }

    @Test
    fun `a commit with no composition just types the text`() {
        kb.commitText("hi ")

        assertEquals(press('h') + press('i') + press(' '), events())
    }

    @Test
    fun `after finishComposing the previous text is frozen and never erased`() {
        kb.setComposingText("abc")
        kb.finishComposing()
        sink.messages.clear()

        kb.setComposingText("d")

        assertEquals(press('d'), events())
    }

    @Test
    fun `deleteSurrounding sends backspaces then deletes, bounded`() {
        kb.deleteSurrounding(2, 1)
        assertEquals(press(Keysyms.BACKSPACE) + press(Keysyms.BACKSPACE) + press(Keysyms.DELETE), events())

        sink.messages.clear()
        kb.deleteSurrounding(1_000_000, 1_000_000)
        assertEquals(2 * KeyboardInput.MAX_DELETE, sink.messages.size)

        sink.messages.clear()
        kb.deleteSurrounding(-5, -5)
        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun `a delete request freezes the composition`() {
        kb.setComposingText("abc")
        kb.deleteSurrounding(1, 0)
        sink.messages.clear()

        kb.setComposingText("x")

        assertEquals(press('x'), events())
    }

    // ================================================================== touches physiques

    @Test
    fun `raw key down and up are forwarded as they are`() {
        kb.keyDown(Keysyms.LEFT)
        kb.keyDown(Keysyms.LEFT) // répétition automatique : encore un appui
        kb.keyUp(Keysyms.LEFT)

        assertEquals(listOf("v" + Keysyms.LEFT.toString(16), "v" + Keysyms.LEFT.toString(16), "^" + Keysyms.LEFT.toString(16)), events())
    }

    @Test
    fun `a raw non modifier key up releases the one-shot modifiers`() {
        kb.toggleModifier(KeyboardInput.Modifier.CTRL)
        kb.keyDown('c'.code)
        sink.messages.clear()

        kb.keyUp('c'.code)

        assertEquals(listOf("^63", "^" + Keysyms.CONTROL_L.toString(16)), events())
    }

    @Test
    fun `a physical modifier key is forwarded and does not touch the latched state`() {
        kb.keyDown(Keysyms.SHIFT_L)
        kb.keyUp(Keysyms.SHIFT_L)

        assertFalse(kb.anyModifierHeld)
        assertEquals(2, sink.messages.size)
    }

    @Test
    fun `a physical modifier of the other side never disturbs the on-screen toggles`() {
        kb.toggleModifier(KeyboardInput.Modifier.SHIFT)

        kb.keyDown(Keysyms.SHIFT_R)
        kb.keyUp(Keysyms.SHIFT_R)

        assertTrue("la bascule à l'écran est intacte", kb.isHeld(KeyboardInput.Modifier.SHIFT))
    }

    // ================================================================== propriété

    /** Serveur simulé : état de chaque touche ; refuse un relâchement sans appui préalable. */
    private class ShadowKeyboard {
        val down = mutableSetOf<Long>()
        var presses = 0
        fun feed(messages: List<ByteArray>) {
            for (m in messages) for (i in 0 until m.size step 8) {
                val k = ((m[i + 4].toLong() and 255) shl 24) or ((m[i + 5].toLong() and 255) shl 16) or
                    ((m[i + 6].toLong() and 255) shl 8) or (m[i + 7].toLong() and 255)
                if (m[i + 1].toInt() == 1) { down += k; presses++ } else {
                    assertTrue("relâchement sans appui : %x".format(k), k in down)
                    down -= k
                }
            }
        }
    }

    @Test
    fun `random sequences never leave a key down once the modifiers are released, and never release a key that is not down`() {
        val rnd = java.util.Random(11)
        val words = listOf("a", "Hello", "é€", "x\ny", "😀", "", "\u0001")
        repeat(300) { seq ->
            val s = RecordingSink()
            val k = KeyboardInput(s)
            repeat(80) {
                when (rnd.nextInt(9)) {
                    0 -> k.toggleModifier(KeyboardInput.Modifier.values()[rnd.nextInt(3)])
                    1 -> k.typeText(words[rnd.nextInt(words.size)])
                    2 -> k.pressKey(intArrayOf(Keysyms.ESCAPE, Keysyms.TAB, Keysyms.LEFT, 'q'.code)[rnd.nextInt(4)])
                    3 -> k.setComposingText(words[rnd.nextInt(words.size)])
                    4 -> k.commitText(words[rnd.nextInt(words.size)])
                    5 -> k.finishComposing()
                    6 -> k.deleteSurrounding(rnd.nextInt(4), rnd.nextInt(3))
                    7 -> { val ks = intArrayOf(Keysyms.RETURN, 'z'.code, Keysyms.SHIFT_R)[rnd.nextInt(3)]; k.keyDown(ks); k.keyUp(ks) }
                    else -> k.releaseModifiers()
                }
            }
            k.releaseModifiers()
            val server = ShadowKeyboard().also { it.feed(s.messages) }
            assertTrue("séquence $seq : touches restées enfoncées : ${server.down.map { "%x".format(it) }}", server.down.isEmpty())
        }
    }

    @Test
    fun `an ignored modifier failure never leaves the client believing a key is held that the server released`() {
        // le message d'appui est perdu : le client ne mémorise pas le modificateur, donc n'enverra pas de relâchement fantôme
        sink.accept = false
        kb.toggleModifier(KeyboardInput.Modifier.CTRL)
        sink.accept = true

        kb.typeText("c")

        assertEquals(press('c'), events())
    }

    // ================================================================== sur une vraie socket

    private val pairs = mutableListOf<LoopbackPair>()

    @After
    fun tearDown() { pairs.forEach { it.close() } }

    @Test(timeout = 10_000)
    fun `keystrokes go through a real sender to the server byte for byte`() {
        val p = LoopbackPair().also { pairs += it }
        val sender = PointerSender.forSocket(p.client)
        sender.start()
        try {
            val real = KeyboardInput(sender)
            real.toggleModifier(KeyboardInput.Modifier.CTRL)
            real.typeText("ca")
            real.pressKey(Keysyms.ESCAPE)

            val expected = ClientMessages.keyEvent(true, Keysyms.CONTROL_L) + ClientMessages.keyPress('c'.code) +
                ClientMessages.keyEvent(false, Keysyms.CONTROL_L) + ClientMessages.keyPress('a'.code) +
                ClientMessages.keyPress(Keysyms.ESCAPE)
            assertEquals(expected.toList(), p.receiveExactly(expected.size).toList())
        } finally {
            sender.stop()
        }
    }

    @Test
    fun `keys are refused, not queued, when the sender is stopped`() {
        val stopped = PointerSender { }
        val real = KeyboardInput(stopped)

        assertEquals(0, real.typeText("abc"))
        assertFalse(real.toggleModifier(KeyboardInput.Modifier.ALT))
        assertFalse(real.pressKey(Keysyms.RETURN))
    }

    private val unused = LinkedBlockingQueue<ByteArray>().also { it.poll(0, TimeUnit.MILLISECONDS) }
}
