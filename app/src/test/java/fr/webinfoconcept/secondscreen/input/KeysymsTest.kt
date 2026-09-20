package fr.webinfoconcept.secondscreen.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table des keysyms X11 (SS-046, SS-047) : valeurs de `X11/keysymdef.h`, recopiées ici à la main. */
class KeysymsTest {

    @Test
    fun `the special keysyms have the values of keysymdef h`() {
        assertEquals(0xFF08, Keysyms.BACKSPACE)
        assertEquals(0xFF09, Keysyms.TAB)
        assertEquals(0xFF0D, Keysyms.RETURN)
        assertEquals(0xFF1B, Keysyms.ESCAPE)
        assertEquals(0xFFFF, Keysyms.DELETE)
        assertEquals(0xFF50, Keysyms.HOME)
        assertEquals(0xFF51, Keysyms.LEFT)
        assertEquals(0xFF52, Keysyms.UP)
        assertEquals(0xFF53, Keysyms.RIGHT)
        assertEquals(0xFF54, Keysyms.DOWN)
        assertEquals(0xFF55, Keysyms.PAGE_UP)
        assertEquals(0xFF56, Keysyms.PAGE_DOWN)
        assertEquals(0xFF57, Keysyms.END)
        assertEquals(0xFF63, Keysyms.INSERT)
        assertEquals(0xFFBE, Keysyms.F1)
        assertEquals(0xFFE1, Keysyms.SHIFT_L)
        assertEquals(0xFFE2, Keysyms.SHIFT_R)
        assertEquals(0xFFE3, Keysyms.CONTROL_L)
        assertEquals(0xFFE4, Keysyms.CONTROL_R)
        assertEquals(0xFFE5, Keysyms.CAPS_LOCK)
        assertEquals(0xFFE9, Keysyms.ALT_L)
        assertEquals(0xFFEA, Keysyms.ALT_R)
        assertEquals(0xFFEB, Keysyms.SUPER_L)
        assertEquals(0xFFEC, Keysyms.SUPER_R)
    }

    @Test
    fun `printable ASCII maps to its own code`() {
        for (c in 0x20..0x7E) assertEquals(c, Keysyms.fromCodePoint(c))
        assertEquals(0x61, Keysyms.fromCodePoint('a'.code))
        assertEquals(0x41, Keysyms.fromCodePoint('A'.code))
        assertEquals(0x20, Keysyms.fromCodePoint(' '.code))
    }

    @Test
    fun `Latin-1 maps to its own code, which is the definition of Latin-1 keysyms`() {
        for (c in 0xA0..0xFF) assertEquals(c, Keysyms.fromCodePoint(c))
        assertEquals(0xE9, Keysyms.fromCodePoint('é'.code))
        assertEquals(0xE0, Keysyms.fromCodePoint('à'.code))
        assertEquals(0xC7, Keysyms.fromCodePoint('Ç'.code))
        assertEquals(0xA3, Keysyms.fromCodePoint('£'.code))
    }

    @Test
    fun `control characters with a key are mapped to that key`() {
        assertEquals(Keysyms.RETURN, Keysyms.fromCodePoint('\n'.code))
        assertEquals(Keysyms.RETURN, Keysyms.fromCodePoint('\r'.code))
        assertEquals(Keysyms.TAB, Keysyms.fromCodePoint('\t'.code))
        assertEquals(Keysyms.BACKSPACE, Keysyms.fromCodePoint(0x08))
        assertEquals(Keysyms.ESCAPE, Keysyms.fromCodePoint(0x1B))
        assertEquals(Keysyms.DELETE, Keysyms.fromCodePoint(0x7F))
    }

    @Test
    fun `other control characters have no keysym`() {
        for (c in (0x00..0x1F) - setOf(0x08, 0x09, 0x0A, 0x0D, 0x1B)) assertEquals("U+%04X".format(c), 0, Keysyms.fromCodePoint(c))
        for (c in 0x80..0x9F) assertEquals("C1 U+%04X".format(c), 0, Keysyms.fromCodePoint(c))
    }

    @Test
    fun `above Latin-1 the keysym is the Unicode one, offset by 0x01000000`() {
        assertEquals(0x010020AC, Keysyms.fromCodePoint('€'.code))
        assertEquals(0x01000153, Keysyms.fromCodePoint('œ'.code))
        assertEquals(0x010003A9, Keysyms.fromCodePoint('Ω'.code))
        assertEquals(0x01000100, Keysyms.fromCodePoint(0x100))
        assertEquals(0x0101F600, Keysyms.fromCodePoint(0x1F600)) // un emoji : point de code hors plan de base
        assertEquals(0x0110FFFF, Keysyms.fromCodePoint(0x10FFFF))
    }

    @Test
    fun `invalid code points have no keysym`() {
        assertEquals(0, Keysyms.fromCodePoint(-1))
        assertEquals(0, Keysyms.fromCodePoint(0x110000))
        assertEquals(0, Keysyms.fromCodePoint(Int.MAX_VALUE))
        assertEquals(0, Keysyms.fromCodePoint(Int.MIN_VALUE))
        for (surrogate in listOf(0xD800, 0xDBFF, 0xDC00, 0xDFFF)) assertEquals(0, Keysyms.fromCodePoint(surrogate))
    }

    @Test
    fun `no code point produces a keysym that collides with a special key`() {
        val specials = setOf(Keysyms.BACKSPACE, Keysyms.TAB, Keysyms.RETURN, Keysyms.ESCAPE, Keysyms.DELETE)
        for (cp in (0x20..0x24FF) + (0x1F600..0x1F610)) {
            val k = Keysyms.fromCodePoint(cp)
            if (k != 0) assertFalse("U+%04X -> %X".format(cp, k), k in specials && cp !in setOf(0x7F))
        }
    }

    @Test
    fun `Android special keys map to their keysym`() {
        assertEquals(Keysyms.BACKSPACE, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_DEL))
        assertEquals(Keysyms.DELETE, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(Keysyms.RETURN, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_ENTER))
        assertEquals(Keysyms.RETURN, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(Keysyms.TAB, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_TAB))
        assertEquals(Keysyms.ESCAPE, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(Keysyms.LEFT, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Keysyms.UP, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Keysyms.RIGHT, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(Keysyms.DOWN, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(Keysyms.HOME, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(Keysyms.END, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(Keysyms.PAGE_UP, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(Keysyms.PAGE_DOWN, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(Keysyms.INSERT, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_INSERT))
    }

    @Test
    fun `Android modifier keys map to modifier keysyms`() {
        assertEquals(Keysyms.SHIFT_L, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(Keysyms.SHIFT_R, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(Keysyms.CONTROL_L, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(Keysyms.CONTROL_R, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(Keysyms.ALT_L, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(Keysyms.ALT_R, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals(Keysyms.SUPER_L, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(Keysyms.SUPER_R, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_META_RIGHT))
        assertEquals(Keysyms.CAPS_LOCK, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_CAPS_LOCK))
    }

    @Test
    fun `function keys F1 to F12 are consecutive keysyms`() {
        for (n in 1..12) {
            assertEquals("F$n", 0xFFBE + n - 1, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_F1 + n - 1))
        }
        assertEquals(0xFFC9, Keysyms.fromAndroidKeyCode(KeyEvent.KEYCODE_F12))
    }

    @Test
    fun `system keys and character keys have no special keysym - Android keeps the first, the character path handles the second`() {
        for (code in listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_UNKNOWN
        )) {
            assertEquals("code $code", 0, Keysyms.fromAndroidKeyCode(code))
        }
    }

    @Test
    fun `modifier detection covers shift, control, meta, alt and super, not caps lock nor ordinary keys`() {
        for (m in listOf(Keysyms.SHIFT_L, Keysyms.SHIFT_R, Keysyms.CONTROL_L, Keysyms.CONTROL_R, Keysyms.META_L, Keysyms.META_R,
            Keysyms.ALT_L, Keysyms.ALT_R, Keysyms.SUPER_L, Keysyms.SUPER_R)) assertTrue("%X".format(m), Keysyms.isModifier(m))
        for (k in listOf(Keysyms.CAPS_LOCK, Keysyms.TAB, Keysyms.RETURN, Keysyms.ESCAPE, Keysyms.DELETE, 'a'.code, 0)) {
            assertFalse("%X".format(k), Keysyms.isModifier(k))
        }
    }
}
