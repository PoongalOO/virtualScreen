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
     * Règle : un encodage n'est ajouté ici que lorsque son décodeur existe et est testé. Annoncer
     * un encodage non décodable ferait envoyer au serveur des rectangles que le client ne sait pas
     * lire, ce qui coupe la connexion. État actuel : RAW seul. À compléter avec CopyRect (SS-025),
     * puis Hextile (SS-026), en tête de liste (les encodages compacts sont préférables à RAW).
     */
    val ADVERTISED: List<Int> = listOf(RAW)
}
