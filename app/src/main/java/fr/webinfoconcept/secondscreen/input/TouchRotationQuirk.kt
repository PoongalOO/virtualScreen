package fr.webinfoconcept.secondscreen.input

import android.view.Surface

/**
 * Correctif ciblé pour un défaut trouvé sur la GT-P5110 (SS-084, SS-088) : en orientation paysage **retournée**
 * (« seascape », `Surface.ROTATION_180`), l'affichage compense correctement la rotation physique — vérifié pixel à
 * pixel contre le framebuffer du serveur — mais **le toucher ne la compense pas** : une position touchée à l'écran
 * livre à l'application une coordonnée qui correspond en réalité au point **symétrique** de la vue, comme si
 * l'appareil n'avait pas tourné. Aucune trace de cette compensation manquante n'existe côté application ; c'est un
 * désaccord entre l'affichage et le toucher au niveau de la plateforme (Android/pilote tactile), pas un choix de ce
 * code — le correctif consiste donc à **annuler cette erreur de la plateforme**, uniquement pour l'orientation où
 * elle a été mesurée.
 *
 * **Portée volontairement étroite** : `Surface.ROTATION_180` est la seule valeur corrigée, déterminée par la mesure
 * (campagne SS-084) et non supposée. L'application ne demande que `sensorLandscape`, qui n'admet que
 * [Surface.ROTATION_0] et [Surface.ROTATION_180] : les deux autres rotations ne se produisent jamais ici, et ne sont
 * donc ni mesurées ni corrigées. Un autre appareil ou une autre version d'Android dont le défaut se manifesterait
 * différemment (une autre rotation, un autre appareil sans ce défaut) n'est **pas** couvert par ce correctif : ce
 * n'est pas une réimplémentation générale de la gestion de rotation, seulement l'annulation de cette anomalie précise.
 */
object TouchRotationQuirk {
    /**
     * Orientation dans laquelle le toucher de la GT-P5110 n'est pas compensé par la plateforme alors que l'affichage
     * l'est. Mesurée sur l'appareil réel, pas supposée : `Surface.ROTATION_180` correspond à l'orientation retournée
     * confirmée par la campagne SS-084 (rendu correct, toucher symétrique).
     */
    const val UNCOMPENSATED_ROTATION: Int = Surface.ROTATION_180

    /**
     * Position ([x], [y]) telle qu'elle doit être traitée par le reste du code, dans une vue de [width] × [height]
     * pixels actuellement dans l'orientation [rotation] (`Surface.getRotation()`).
     *
     * Sans le défaut ([rotation] différent de [UNCOMPENSATED_ROTATION]), [x] et [y] sont rendus inchangés : ce
     * correctif ne doit **rien** modifier en dehors de l'orientation où le défaut a été mesuré. Avec le défaut, la
     * position est symétrique par rapport au centre de la vue — l'inverse exact de ce que la plateforme a, à tort,
     * laissé non compensé.
     */
    fun correct(x: Float, y: Float, width: Int, height: Int, rotation: Int): Pair<Float, Float> =
        if (rotation == UNCOMPENSATED_ROTATION) (width - x) to (height - y) else x to y
}
