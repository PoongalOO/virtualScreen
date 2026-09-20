package fr.webinfoconcept.secondscreen.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formulaire de connexion et profils enregistrés (SS-050, SS-051). */
class ProfileStoreTest {

    /** Stockage en mémoire qui garde aussi la trace de tout ce qui a été écrit. */
    private class MemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        val data = LinkedHashMap(initial)
        val everWritten = mutableListOf<String>()
        override fun all(): Map<String, String> = LinkedHashMap(data)
        override fun apply(changes: Map<String, String?>) {
            for ((k, v) in changes) {
                if (v == null) data.remove(k) else { data[k] = v; everWritten += "$k=$v" }
            }
        }
    }

    private val memory = MemoryStore()
    private val store = ProfileStore(memory)

    private fun form(name: String = "Bureau", host: String = "192.168.1.10", port: String = "5900") =
        ProfileForm.validate(name, host, port)

    // ------------------------------------------------------------- formulaire

    @Test
    fun `a valid form is normalized`() {
        val f = ProfileForm.validate("  Bureau  ", " 192.168.1.10 ", " 5901 ")

        assertTrue(f.isValid)
        assertEquals("Bureau", f.name)
        assertEquals("192.168.1.10", f.host)
        assertEquals(5901, f.port)
    }

    @Test
    fun `an empty name falls back to the host and an empty port to 5900`() {
        val f = ProfileForm.validate("", "pc.local", "")

        assertTrue(f.isValid)
        assertEquals("pc.local", f.name)
        assertEquals(5900, f.port)
    }

    @Test
    fun `each invalid field is reported on its own`() {
        val f = ProfileForm.validate("x".repeat(41), "http://pc", "70000")

        assertFalse(f.isValid)
        assertEquals(FieldError.NAME_TOO_LONG, f.nameError)
        assertEquals(FieldError.HOST_INVALID, f.hostError)
        assertEquals(FieldError.PORT_INVALID, f.portError)
        assertNull(f.name)
        assertNull(f.host)
    }

    @Test
    fun `empty host, control characters in the name and bad ports`() {
        assertEquals(FieldError.HOST_EMPTY, ProfileForm.validate("a", "   ", "5900").hostError)
        assertEquals(FieldError.NAME_INVALID, ProfileForm.validate("a\nb", "pc", "5900").nameError)
        assertEquals(FieldError.NAME_INVALID, ProfileForm.validate("a\u0000b", "pc", "5900").nameError)
        for (bad in listOf("0", "65536", "abc", "-5", "59 00")) {
            assertEquals("« $bad »", FieldError.PORT_INVALID, ProfileForm.validate("a", "pc", bad).portError)
        }
    }

    @Test
    fun `a 40 character name is accepted, 41 is not`() {
        assertTrue(ProfileForm.validate("n".repeat(40), "pc", "").isValid)
        assertFalse(ProfileForm.validate("n".repeat(41), "pc", "").isValid)
    }

    @Test
    fun `a long host used as the default name is refused as a name`() {
        val f = ProfileForm.validate("", "h".repeat(60) + ".local", "")

        assertEquals(FieldError.NAME_TOO_LONG, f.nameError)
    }

    // ------------------------------------------------------------- stockage

    @Test
    fun `save then list returns the profile`() {
        val saved = store.save(form())

        assertEquals(listOf(saved), store.list())
        assertEquals(saved, store.get(saved.id))
        assertEquals("Bureau", saved.name)
        assertEquals("192.168.1.10", saved.host)
        assertEquals(5900, saved.port)
    }

    @Test
    fun `profiles are sorted by name ignoring case`() {
        store.save(form("zèbre", "h1"))
        store.save(form("Alpha", "h2"))
        store.save(form("beta", "h3"))

        assertEquals(listOf("Alpha", "beta", "zèbre"), store.list().map { it.name })
    }

    @Test
    fun `identifiers are unique and never reused after a deletion`() {
        val a = store.save(form("a", "h1"))
        val b = store.save(form("b", "h2"))
        store.delete(b.id)
        val c = store.save(form("c", "h3"))

        assertEquals(3, setOf(a.id, b.id, c.id).size)
        assertTrue(c.id > b.id)
    }

    @Test
    fun `saving with an id updates that profile`() {
        val a = store.save(form("a", "h1"))

        val updated = store.save(form("renamed", "h2", "5901"), a.id)

        assertEquals(a.id, updated.id)
        assertEquals(listOf(updated), store.list())
        assertEquals("h2", store.get(a.id)!!.host)
    }

    @Test
    fun `saving a new profile with an existing name updates it instead of duplicating`() {
        val first = store.save(form("Bureau", "h1"))

        val second = store.save(form("bureau", "h2", "5902"))

        assertEquals(first.id, second.id)
        assertEquals(1, store.list().size)
        assertEquals("h2", store.list()[0].host)
        assertEquals(5902, store.list()[0].port)
    }

    @Test
    fun `two different names for the same host are two profiles`() {
        store.save(form("a", "h1"))
        store.save(form("b", "h1"))

        assertEquals(2, store.list().size)
    }

    @Test
    fun `delete removes only that profile and every one of its keys`() {
        val a = store.save(form("a", "h1"))
        val b = store.save(form("b", "h2"))

        store.delete(a.id)

        assertEquals(listOf(b), store.list())
        assertTrue("aucune clé orpheline", memory.data.keys.none { it.startsWith("profile.${a.id}.") })
        store.delete(a.id) // idempotent
        store.delete(999)
        assertEquals(listOf(b), store.list())
    }

    @Test
    fun `the last used profile is remembered and forgotten with its deletion`() {
        val a = store.save(form("a", "h1"))
        val b = store.save(form("b", "h2"))
        assertNull(store.lastUsed())

        store.markUsed(b.id)
        assertEquals(b, store.lastUsed())
        store.markUsed(a.id)
        assertEquals(a, store.lastUsed())
        store.delete(a.id)

        assertNull(store.lastUsed())
    }

    @Test
    fun `marking an unknown profile as used does nothing`() {
        store.markUsed(42)

        assertNull(store.lastUsed())
        assertTrue(memory.data.isEmpty())
    }

    @Test
    fun `the number of profiles is bounded`() {
        repeat(ProfileStore.MAX_PROFILES) { store.save(form("p$it", "h$it")) }

        assertThrows(TooManyProfilesException::class.java) { store.save(form("one more", "hx")) }
        // mettre à jour un existant reste possible
        val existing = store.list().first()
        store.save(form(existing.name, "changed"), existing.id)
        assertEquals("changed", store.get(existing.id)!!.host)
        assertEquals(ProfileStore.MAX_PROFILES, store.list().size)
    }

    @Test
    fun `an invalid form is refused`() {
        assertThrows(IllegalArgumentException::class.java) { store.save(ProfileForm.validate("a", "", "5900")) }
        assertTrue(memory.data.isEmpty())
    }

    // ------------------------------------------------------------- pas de secret

    @Test
    fun `nothing but name, host, port and bookkeeping ids is ever written`() {
        val a = store.save(form("Bureau", "192.168.1.10", "5901"))
        store.markUsed(a.id)

        val allowed = Regex("""profile\.\d+\.(name|host|port)|last_profile_id|next_profile_id""")
        assertTrue("clés inattendues : ${memory.data.keys}", memory.data.keys.all { allowed.matches(it) })
        assertEquals(setOf("Bureau", "192.168.1.10", "5901", a.id.toString(), (a.id + 1).toString()), memory.data.values.toSet())
    }

    @Test
    fun `neither the profile class nor the store have a place for a password`() {
        for (cls in listOf(ConnectionProfile::class.java, ProfileStore::class.java, ProfileForm::class.java)) {
            val members = (cls.declaredFields.map { it.name } + cls.declaredMethods.map { it.name }).map { it.lowercase() }
            assertTrue("$cls : $members", members.none { "pass" in it || "secret" in it || "pwd" in it })
        }
    }

    @Test
    fun `toString of a profile has no secret to show`() {
        val text = store.save(form()).toString()

        assertFalse(text.lowercase().contains("pass"))
    }

    // ------------------------------------------------------------- données corrompues

    @Test
    fun `corrupt or incomplete stored profiles are ignored, never an exception`() {
        val bad = MemoryStore(
            mapOf(
                "profile.1.name" to "ok", "profile.1.host" to "h1", "profile.1.port" to "5900", // valide
                "profile.2.name" to "sans hôte", "profile.2.port" to "5900",                     // hôte manquant
                "profile.3.name" to "port faux", "profile.3.host" to "h3", "profile.3.port" to "abc",
                "profile.4.name" to "port hors", "profile.4.host" to "h4", "profile.4.port" to "99999",
                "profile.5.name" to "hôte piégé", "profile.5.host" to "http://x/", "profile.5.port" to "5900",
                "profile.6.name" to "n".repeat(500), "profile.6.host" to "h6", "profile.6.port" to "5900",
                "profile.7.name" to "espace ", "profile.7.host" to "h7", "profile.7.port" to "5900", // non normalisé
                "profile.8.name" to "vide", "profile.8.host" to "h8", "profile.8.port" to "",
                "profile.abc.name" to "x", "profile..name" to "x", "profile.9" to "x", "profile.9.autre" to "x",
                "profile.99999999999999999999.name" to "x",
                "last_profile_id" to "pas un nombre", "next_profile_id" to "-4"
            )
        )
        val s = ProfileStore(bad)

        assertEquals(listOf("ok"), s.list().map { it.name })
        assertNull(s.lastUsed())
        // et l'on peut continuer à s'en servir
        val added = s.save(form("nouveau", "h9"))
        assertNotEquals(1L, added.id)
        assertEquals(2, s.list().size)
    }

    @Test
    fun `next id ignores a corrupt counter and stays above every stored id`() {
        val s = ProfileStore(MemoryStore(mapOf("profile.7.name" to "a", "profile.7.host" to "h", "profile.7.port" to "1", "next_profile_id" to "2")))

        val added = s.save(form("b", "h2"))

        assertTrue(added.id > 7)
    }

    @Test
    fun `a saved profile can be turned back into connection parameters`() {
        val p = store.save(form("a", "192.168.1.10", "5901"))

        assertEquals("192.168.1.10:5901", p.toParams().toString())
        assertEquals("a — 192.168.1.10:5901", p.label())
    }
}
