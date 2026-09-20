package fr.webinfoconcept.secondscreen.profile

import fr.webinfoconcept.secondscreen.session.ConnectionParams

/**
 * Stockage clé/valeur minimal, sous [ProfileStore]. Sur Android c'est [PreferencesStore] ; les tests utilisent une
 * version en mémoire, donc rien ici ne dépend d'Android.
 */
interface KeyValueStore {
    /** Toutes les paires enregistrées. */
    fun all(): Map<String, String>

    /** Applique [changes] d'un seul geste : `null` supprime la clé. */
    fun apply(changes: Map<String, String?>)
}

/** Trop de profils enregistrés ([ProfileStore.MAX_PROFILES]). */
class TooManyProfilesException : RuntimeException("Trop de connexions enregistrées")

/**
 * Profils de connexion enregistrés (SS-051) : nom, hôte, port. **Le mot de passe n'est jamais stocké** : cette classe
 * n'a pas de champ pour lui et [ConnectionProfile] non plus (SECURITY.md : saisie à chaque connexion ; la mémorisation
 * d'un mot de passe serait une fonctionnalité séparée avec analyse de risque, Android 4.2 n'ayant pas de coffre
 * sécurisé).
 *
 * Format : une clé par champ, `profile.<id>.name|host|port`, plus `last_profile_id` et `next_profile_id`. Les données
 * lues sont **non fiables** (fichier modifiable sur un appareil rooté, résidu d'une ancienne version) : un profil
 * incomplet ou invalide est ignoré, jamais une exception.
 *
 * Non thread-safe : appelé depuis le thread UI.
 */
class ProfileStore(private val store: KeyValueStore) {

    /** Les profils valides, triés par nom (sans tenir compte de la casse) puis par identifiant. */
    fun list(): List<ConnectionProfile> {
        val all = store.all()
        val ids = all.keys.mapNotNull { idOf(it) }.toSortedSet()
        return ids.mapNotNull { read(all, it) }
            .sortedWith(compareBy<ConnectionProfile> { it.name.lowercase() }.thenBy { it.id })
    }

    fun get(id: Long): ConnectionProfile? = read(store.all(), id)

    /**
     * Enregistre un profil. Sans [id] : un nouveau profil, **sauf** s'il en existe déjà un du même nom (sans tenir
     * compte de la casse), qui est alors mis à jour (« même nom = même profil »).
     *
     * @throws IllegalArgumentException [form] invalide.
     * @throws TooManyProfilesException [MAX_PROFILES] atteint (nouveau profil seulement).
     */
    fun save(form: ProfileForm, id: Long? = null): ConnectionProfile {
        require(form.isValid) { "formulaire invalide" }
        val name = form.name!!
        val all = store.all()
        val target = id ?: list().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
        val isNew = target == null || read(all, target) == null

        val changes = LinkedHashMap<String, String?>()
        val finalId: Long
        if (isNew) {
            if (list().size >= MAX_PROFILES) throw TooManyProfilesException()
            finalId = nextId(all)
            changes[NEXT_ID] = (finalId + 1).toString()
        } else {
            finalId = target!!
        }
        changes[key(finalId, "name")] = name
        changes[key(finalId, "host")] = form.host
        changes[key(finalId, "port")] = form.port.toString()
        store.apply(changes)
        return ConnectionProfile(finalId, name, form.host!!, form.port!!)
    }

    /** Supprime le profil [id] ; s'il était le dernier utilisé, il ne l'est plus. Sans effet s'il n'existe pas. */
    fun delete(id: Long) {
        val changes = LinkedHashMap<String, String?>()
        for (field in FIELDS) changes[key(id, field)] = null
        if (lastUsedId() == id) changes[LAST_ID] = null
        store.apply(changes)
    }

    /** Mémorise [id] comme dernier profil utilisé (reconnexion rapide, F08). Sans effet s'il n'existe pas. */
    fun markUsed(id: Long) {
        if (get(id) != null) store.apply(mapOf(LAST_ID to id.toString()))
    }

    /** Le dernier profil utilisé, s'il existe encore et est valide. */
    fun lastUsed(): ConnectionProfile? = lastUsedId()?.let { get(it) }

    private fun lastUsedId(): Long? = store.all()[LAST_ID]?.toLongOrNull()

    private fun read(all: Map<String, String>, id: Long): ConnectionProfile? {
        val name = all[key(id, "name")] ?: return null
        val host = all[key(id, "host")] ?: return null
        val portText = all[key(id, "port")]?.takeIf { it.isNotBlank() } ?: return null // vide : ni 5900 ni valide ici
        val port = ConnectionParams.parsePort(portText) ?: return null
        // Revalidation : ce qui est lu du stockage n'est pas plus fiable que ce qui vient du réseau.
        val form = ProfileForm.validate(name, host, port.toString())
        if (!form.isValid || form.name != name || form.host != host) return null
        return ConnectionProfile(id, name, host, port)
    }

    private fun nextId(all: Map<String, String>): Long {
        val stored = all[NEXT_ID]?.toLongOrNull() ?: 1L
        val maxUsed = all.keys.mapNotNull { idOf(it) }.maxOrNull() ?: 0L
        return maxOf(stored, maxUsed + 1, 1L)
    }

    // Seule la forme stricte `profile.<entier>.<champ>` définit un identifiant.
    private fun idOf(storageKey: String): Long? {
        if (!storageKey.startsWith(PREFIX)) return null
        val rest = storageKey.substring(PREFIX.length)
        val dot = rest.indexOf('.')
        if (dot <= 0) return null
        if (rest.substring(dot + 1) !in FIELDS) return null
        val digits = rest.substring(0, dot)
        return if (digits.length <= 15 && digits.all { it in '0'..'9' }) digits.toLong() else null
    }

    private fun key(id: Long, field: String) = "$PREFIX$id.$field"

    companion object {
        const val MAX_PROFILES = 50
        private const val PREFIX = "profile."
        private const val LAST_ID = "last_profile_id"
        private const val NEXT_ID = "next_profile_id"
        private val FIELDS = listOf("name", "host", "port")
    }
}
