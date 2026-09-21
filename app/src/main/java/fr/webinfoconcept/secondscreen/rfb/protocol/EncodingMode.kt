package fr.webinfoconcept.secondscreen.rfb.protocol

/**
 * Encodages annoncés au serveur (`SetEncodings`), au choix (SS-063). **[AUTO] est le réglage normal.** Les deux autres existent
 * pour **comparer** les encodages sur l'appareil réel (PERFORMANCE.md) : ils ne sont pas plus « rapides », ils isolent un
 * encodage pour en mesurer le coût.
 *
 * Toutes les listes ne contiennent que des encodages dont le décodeur existe (règle de [Encoding.ADVERTISED]) et finissent par
 * RAW, que tout serveur sait envoyer : un serveur qui ne connaît pas Hextile ne coupe donc pas la connexion.
 */
enum class EncodingMode(val key: String, val encodings: List<Int>) {
    /** Hextile, CopyRect puis RAW : le réglage par défaut. */
    AUTO("auto", Encoding.ADVERTISED),

    /** Hextile puis RAW, **sans CopyRect** : isole le coût d'Hextile (un défilement est alors envoyé en pixels). */
    HEXTILE("hextile", listOf(Encoding.HEXTILE, Encoding.RAW)),

    /** RAW seul : tous les pixels de chaque zone modifiée, sans compression ni CopyRect. */
    RAW("raw", listOf(Encoding.RAW));

    companion object {
        /** Le mode dont la clé est [key] ; [AUTO] pour une valeur absente ou inconnue (jamais d'échec). */
        fun fromKey(key: String?): EncodingMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}
