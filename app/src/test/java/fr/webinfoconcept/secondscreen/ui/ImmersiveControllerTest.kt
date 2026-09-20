// Drapeaux systemUiVisibility : dépréciés depuis l'API 30 mais seuls disponibles sur Android 4.2 (voir ImmersiveController).
@file:Suppress("DEPRECATION")

package fr.webinfoconcept.secondscreen.ui

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode immersif API 17 (SS-034) : la barre masquée doit revenir seulement après un délai, sans jamais empêcher de
 * quitter l'application. Minuteur et fenêtre simulés : aucun chronométrage réel.
 */
class ImmersiveControllerTest {

    private class FakeHost : SystemUiHost {
        var writes = 0
        override var systemUiVisibility = 0
            set(value) { field = value; writes++ }
        val scheduled = mutableListOf<Pair<Runnable, Long>>() // actions en attente et leur délai
        var removed = 0

        override fun postDelayed(action: Runnable, delayMs: Long) { scheduled += action to delayMs }
        override fun removeCallbacks(action: Runnable) { scheduled.removeAll { it.first === action }; removed++ }

        /** Le système écrit lui-même la visibilité (la barre réapparaît après un toucher). */
        fun systemClearsFlags() { systemUiVisibility = 0; writes-- } // pas une écriture du contrôleur
        fun runScheduled() { scheduled.toList().forEach { scheduled.remove(it); it.first.run() } }
    }

    private val hide = ImmersiveController.HIDE_FLAGS
    private val navigationBarFlag = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

    private fun controller(host: FakeHost, delay: Long = 3_000) = ImmersiveController(host, delay)

    @Test
    fun `enable hides the system bars`() {
        val host = FakeHost()

        controller(host).enable()

        assertEquals(hide, host.systemUiVisibility)
    }

    @Test
    fun `the flags hide both bars and keep the layout stable`() {
        assertTrue(hide and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertTrue(hide and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertTrue(hide and View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
        // Sans ces trois-là la surface serait redimensionnée à chaque apparition de la barre.
        assertTrue(hide and View.SYSTEM_UI_FLAG_LAYOUT_STABLE != 0)
        assertTrue(hide and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0)
        assertTrue(hide and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
    }

    @Test
    fun `enable is idempotent and does not rewrite identical flags`() {
        val host = FakeHost()
        val c = controller(host)

        c.enable(); val afterFirst = host.systemUiVisibility
        c.enable(); c.enable()

        assertEquals(afterFirst, host.systemUiVisibility)
        assertTrue(c.isEnabled)
    }

    @Test
    fun `when the bar reappears the re-hide is scheduled after the delay and not immediately`() {
        val host = FakeHost()
        val c = controller(host, delay = 3_000)
        c.enable()
        host.systemClearsFlags() // l'utilisateur a touché l'écran

        c.onSystemUiVisibilityChange(host.systemUiVisibility)

        assertEquals("un remasquage programmé", 1, host.scheduled.size)
        assertEquals("après le délai complet", 3_000L, host.scheduled.single().second)
        assertEquals("la barre est encore visible : on peut appuyer sur Retour", 0, host.systemUiVisibility)
        assertTrue(c.isRehidePending)
    }

    @Test
    fun `the bar is hidden again once the delay has elapsed`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        host.systemClearsFlags()
        c.onSystemUiVisibilityChange(0)

        host.runScheduled() // le délai s'écoule

        assertEquals(hide, host.systemUiVisibility)
        assertFalse(c.isRehidePending)
    }

    @Test
    fun `repeated visibility events schedule only one re-hide`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        host.systemClearsFlags()

        repeat(10) { c.onSystemUiVisibilityChange(0) }

        assertEquals("un seul minuteur : les événements ne repoussent pas le remasquage", 1, host.scheduled.size)
    }

    @Test
    fun `the bar can reappear again after a re-hide`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        repeat(3) {
            host.systemClearsFlags()
            c.onSystemUiVisibilityChange(0)
            host.runScheduled()
            assertEquals(hide, host.systemUiVisibility)
        }
    }

    @Test
    fun `hidden bars cancel a pending re-hide`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        host.systemClearsFlags()
        c.onSystemUiVisibilityChange(0)

        c.onSystemUiVisibilityChange(navigationBarFlag) // la barre est de nouveau cachée (par autre chose)

        assertTrue(host.scheduled.isEmpty())
        assertFalse(c.isRehidePending)
    }

    @Test
    fun `disable cancels a pending re-hide so nothing fires after the window is gone`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        host.systemClearsFlags()
        c.onSystemUiVisibilityChange(0)

        c.disable()
        host.runScheduled()

        assertTrue(host.scheduled.isEmpty())
        assertEquals("rien n'a été remasqué", 0, host.systemUiVisibility)
        assertFalse(c.isEnabled)
    }

    @Test
    fun `a re-hide that was already running when disabled does nothing`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        host.systemClearsFlags()
        c.onSystemUiVisibilityChange(0)
        val queued = host.scheduled.single().first
        c.disable()

        queued.run() // le minuteur s'est déclenché juste après disable

        assertEquals(0, host.systemUiVisibility)
    }

    @Test
    fun `events are ignored while disabled`() {
        val host = FakeHost()
        val c = controller(host)

        c.onSystemUiVisibilityChange(0)

        assertTrue(host.scheduled.isEmpty())
        assertEquals(0, host.systemUiVisibility)
    }

    @Test
    fun `disable and enable are idempotent in any order`() {
        val host = FakeHost()
        val c = controller(host)

        c.disable(); c.disable(); c.enable(); c.enable(); c.disable()

        assertFalse(c.isEnabled)
        assertTrue(host.scheduled.isEmpty())
    }

    @Test
    fun `the default delay leaves three seconds to leave the app`() {
        assertEquals(3_000L, ImmersiveController.DEFAULT_REHIDE_DELAY_MS)
        val host = FakeHost()
        val c = ImmersiveController(host)
        c.enable(); host.systemClearsFlags(); c.onSystemUiVisibilityChange(0)

        assertEquals(3_000L, host.scheduled.single().second)
    }

    @Test
    fun `the bar is never re-hidden without a delay`() {
        // Propriété centrale de SS-034 : quel que soit le scénario, aucun remasquage n'est programmé à 0 ms.
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        repeat(5) { host.systemClearsFlags(); c.onSystemUiVisibilityChange(0); host.runScheduled() }

        assertTrue(host.scheduled.all { it.second > 0 })
    }

    @Test
    fun `disable gives the system bars back, so leaving fullscreen does not leave the first touch to be cancelled`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        assertEquals(hide, host.systemUiVisibility)

        c.disable()

        assertEquals("barres visibles", View.SYSTEM_UI_FLAG_VISIBLE, host.systemUiVisibility)
        assertFalse(c.isEnabled)
    }

    @Test
    fun `disable does not write when the bars are already visible`() {
        val host = FakeHost()
        val c = controller(host)

        c.disable()
        c.disable()

        assertEquals("aucune écriture inutile", 0, host.writes)
    }

    @Test
    fun `a disabled controller does not re-hide after it gave the bars back`() {
        val host = FakeHost()
        val c = controller(host)
        c.enable()
        host.systemClearsFlags()
        c.onSystemUiVisibilityChange(host.systemUiVisibility) // la barre est réapparue : remasquage programmé
        assertTrue(c.isRehidePending)

        c.disable()
        host.runScheduled()

        assertEquals(View.SYSTEM_UI_FLAG_VISIBLE, host.systemUiVisibility)
        assertFalse(c.isRehidePending)
    }
}
