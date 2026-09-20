// Ces drapeaux `systemUiVisibility` sont dépréciés depuis l'API 30 (remplacés par WindowInsetsController), mais ce sont
// les SEULS disponibles sur Android 4.2 (API 17), la cible du projet : la dépréciation ne s'applique pas ici.
@file:Suppress("DEPRECATION")

package fr.webinfoconcept.secondscreen.ui

import android.view.View

/**
 * Ce dont [ImmersiveController] a besoin de la fenêtre : lire/écrire la visibilité de l'interface système et
 * programmer une action différée. Abstrait pour pouvoir tester la logique en JVM, sans Android.
 */
interface SystemUiHost {
    var systemUiVisibility: Int
    fun postDelayed(action: Runnable, delayMs: Long)
    fun removeCallbacks(action: Runnable)
}

/** Adaptateur de [SystemUiHost] sur une [View] (typiquement la vue racine de l'Activity). */
class ViewSystemUiHost(private val view: View) : SystemUiHost {
    override var systemUiVisibility: Int
        get() = view.systemUiVisibility
        set(value) { view.systemUiVisibility = value }

    override fun postDelayed(action: Runnable, delayMs: Long) {
        view.postDelayed(action, delayMs)
    }

    override fun removeCallbacks(action: Runnable) {
        view.removeCallbacks(action)
    }
}

/**
 * Masque le « chrome » système (barre d'état et barre de navigation) pour l'écran distant, sur Android 4.2 (SS-034).
 *
 * **Pourquoi** : l'écran distant est un moniteur 1280×800 ; toute barre système rogne l'image (48 lignes sur la
 * GT-P5110, voir RenderGeometry) et empêche le rendu 1:1 (SS-032).
 *
 * **Limite d'API 17** : il n'existe pas de mode immersif « collant » (il apparaît avec l'API 19). Dès que l'utilisateur
 * touche l'écran, le système efface de lui-même les drapeaux et la barre réapparaît. Le contrôleur la remasque alors
 * **après [rehideDelayMs]**, pas immédiatement : c'est ce délai qui laisse le temps d'appuyer sur Retour ou Accueil.
 * Ne jamais remasquer sans délai, ce qui empêcherait de quitter l'application.
 *
 * Utilisation : [enable] quand la fenêtre prend le focus, [disable] quand elle le perd (les drapeaux sont de toute
 * façon réinitialisés par le système à ce moment-là), et brancher [onSystemUiVisibilityChange] sur
 * `OnSystemUiVisibilityChangeListener`. Tout se passe sur le thread UI.
 *
 * @param rehideDelayMs délai avant de remasquer la barre réapparue.
 */
class ImmersiveController(
    private val host: SystemUiHost,
    private val rehideDelayMs: Long = DEFAULT_REHIDE_DELAY_MS
) {
    private var enabled = false
    private var rehidePending = false

    private val rehide = Runnable {
        rehidePending = false
        if (enabled) apply()
    }

    /** `true` tant que le mode est actif. */
    val isEnabled: Boolean get() = enabled

    /** `true` si un remasquage est programmé (la barre est visible et va être cachée). */
    val isRehidePending: Boolean get() = rehidePending

    /** Masque la barre d'état et la barre de navigation. Idempotent. */
    fun enable() {
        enabled = true
        apply()
    }

    /**
     * Arrête le mode : annule tout remasquage programmé **et rend la barre système** si nos drapeaux sont encore posés.
     * Sans cela, quitter le plein écran laissait la barre masquée jusqu'au prochain toucher, et ce toucher (celui qui la
     * fait réapparaître) était annulé par Android 4.2 : le premier appui sur un bouton était perdu. Idempotent.
     */
    fun disable() {
        enabled = false
        cancelRehide()
        if (host.systemUiVisibility != View.SYSTEM_UI_FLAG_VISIBLE) host.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    /**
     * À appeler à chaque changement de visibilité de l'interface système ([visibility] = les drapeaux courants). Si la
     * barre de navigation est réapparue, programme **un seul** remasquage ; si elle est cachée, annule celui en attente.
     */
    fun onSystemUiVisibilityChange(visibility: Int) {
        if (!enabled) return
        if (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0) {
            cancelRehide() // cachée : rien à faire
        } else if (!rehidePending) {
            // Un seul minuteur à la fois : de nouveaux événements ne doivent pas repousser indéfiniment le remasquage.
            rehidePending = true
            host.postDelayed(rehide, rehideDelayMs)
        }
    }

    private fun apply() {
        if (host.systemUiVisibility != HIDE_FLAGS) host.systemUiVisibility = HIDE_FLAGS
    }

    private fun cancelRehide() {
        if (rehidePending) {
            host.removeCallbacks(rehide)
            rehidePending = false
        }
    }

    companion object {
        /** Trois secondes : de quoi viser Retour ou Accueil sur la barre qui vient de réapparaître. */
        const val DEFAULT_REHIDE_DELAY_MS = 3_000L

        /**
         * Drapeaux compatibles API 17 (tous introduits en API 14 ou 16) :
         * - `HIDE_NAVIGATION` (14) et `FULLSCREEN` (16) : masquent la barre de navigation et la barre d'état ;
         * - `LOW_PROFILE` (14) : atténue ce qui reste ;
         * - `LAYOUT_STABLE`, `LAYOUT_HIDE_NAVIGATION`, `LAYOUT_FULLSCREEN` (16) : la mise en page se fait comme si les
         *   barres étaient toujours masquées. Sans eux la surface est redimensionnée à chaque apparition de la barre
         *   (recréation de surface, image qui saute) ; avec eux elle garde sa taille et la barre la recouvre.
         */
        const val HIDE_FLAGS: Int =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LOW_PROFILE or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
}
