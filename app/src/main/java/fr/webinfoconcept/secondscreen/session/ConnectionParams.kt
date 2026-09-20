package fr.webinfoconcept.secondscreen.session

/**
 * Où se connecter. **Ne contient jamais de mot de passe** : il est passé à part, en `CharArray`, à
 * [ConnectionController.connect], qui l'efface (SECURITY.md).
 *
 * @param host adresse IPv4 ou nom d'hôte (voir [isValidHost]).
 * @param port 1..65535 (5900 par défaut).
 * @param shared session partagée : ne déconnecte pas les autres clients du serveur.
 */
data class ConnectionParams(val host: String, val port: Int = DEFAULT_PORT, val shared: Boolean = true) {
    init {
        require(isValidHost(host)) { "hôte invalide" }
        require(port in 1..65535) { "port hors de 1..65535 : $port" }
    }

    /** `hôte:port`, pour l'affichage. */
    override fun toString(): String = "$host:$port"

    companion object {
        const val DEFAULT_PORT = 5900
        const val MAX_HOST_LENGTH = 253

        /**
         * Vrai pour un nom d'hôte ou une adresse IPv4 plausibles : 1 à [MAX_HOST_LENGTH] caractères parmi lettres,
         * chiffres, `.`, `-` et `_`, sans espace ni `/` ni `:`. Refuse d'emblée ce qui ne peut pas être un hôte (URL
         * collée, espace de fin) au lieu d'attendre une erreur de résolution DNS. Les adresses IPv6 littérales ne sont
         * pas gérées (LAN IPv4).
         */
        fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
            if (host.startsWith('.') || host.endsWith('.') || host.startsWith('-')) return false
            return host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }
        }

        /** Port saisi en texte : `null` s'il n'est pas un entier dans 1..65535. Un texte vide donne [DEFAULT_PORT]. */
        fun parsePort(text: String): Int? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return DEFAULT_PORT
            if (trimmed.length > 5 || !trimmed.all { it in '0'..'9' }) return null
            val port = trimmed.toInt()
            return if (port in 1..65535) port else null
        }
    }
}
