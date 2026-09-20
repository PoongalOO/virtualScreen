package fr.webinfoconcept.secondscreen.rfb.protocol

/**
 * Types d'encodage RFB (S32 sur le fil ; les pseudo-encodages sont négatifs).
 *
 * Connaître un numéro ne veut pas dire savoir le décoder : seuls les encodages de
 * [ADVERTISED] sont annoncés au serveur.
 */
object Encoding {
    const val RAW = 0
    const val COPY_RECT = 1
    const val HEXTILE = 5

    /**
     * Encodages annoncés au serveur dans `SetEncodings`, **par ordre de préférence**.
     *
     * Règle : un encodage n'est ajouté ici que lorsque son décodeur existe et est testé (et est
     * enregistré dans [ServerMessageReader.defaultDecoders]). Annoncer un encodage non décodable ferait
     * envoyer au serveur des rectangles que le client ne sait pas lire, ce qui coupe la connexion.
     *
     * État actuel : Hextile (SS-026), CopyRect (SS-025), RAW (SS-024). Les encodages compacts passent
     * avant RAW, qui reste en dernier recours. CopyRect n'est pas un « vrai » encodage préféré (le
     * serveur l'emploie de lui-même quand il détecte un déplacement), sa place relative à Hextile est
     * donc sans conséquence ; les serveurs choisissent l'encodage des pixels dans l'ordre de cette liste.
     */
    val ADVERTISED: List<Int> = listOf(HEXTILE, COPY_RECT, RAW)
}
