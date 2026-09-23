package fr.webinfoconcept.secondscreen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SS-072 : l'avertissement « connexion non chiffrée » doit être **visible d'emblée et non masquable**, pas une case à cocher
 * qu'on décoche une fois pour toutes ni un texte qu'un code pourrait cacher plus tard. Ces tests lisent le layout et le code
 * source ; ils échouent si l'avertissement est retiré, rendu masquable, ou vidé de son contenu.
 */
class ConnectionWarningTest {

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun read(path: String) = File(main, path).readText()

    private val layout by lazy { read("res/layout/activity_connect.xml") }
    private val activity by lazy { read("java/fr/webinfoconcept/secondscreen/ConnectActivity.kt") }
    private val strings by lazy { read("res/values/strings.xml") }

    private fun stringValue(name: String): String {
        val m = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(strings)
        assertTrue("chaîne « $name » introuvable", m != null)
        return m!!.groupValues[1]
    }

    @Test
    fun `the warning exists, is not hidden by default and has no visibility attribute`() {
        val block = layout.substringAfter("@+id/connect_encryption_warning").substringBefore("/>")
        assertFalse("le layout ne doit pas prévoir de le masquer", block.contains("android:visibility"))
    }

    @Test
    fun `the activity never sets a visibility on the warning - it cannot be hidden at runtime`() {
        assertFalse(
            "ConnectActivity ne doit jamais toucher à la visibilité de l'avertissement",
            Regex("""connect_encryption_warning""").containsMatchIn(activity)
        )
    }

    @Test
    fun `the warning appears before the password field and the connect button, so it is seen first`() {
        val warning = layout.indexOf("@+id/connect_encryption_warning")
        val password = layout.indexOf("@+id/connect_password\"")
        val button = layout.indexOf("@+id/connect_button")
        assertTrue("warning=$warning password=$password button=$button", warning in 0 until password && password < button)
    }

    @Test
    fun `the warning text says the connection is unencrypted, plain text, and LAN only`() {
        val text = stringValue("connect_encryption_warning")
        assertTrue("« non chiffrée » ou équivalent : $text", Regex("non chiffr|clair").containsMatchIn(text))
        assertTrue("réseau local / confiance : $text", Regex("réseau local|confiance").containsMatchIn(text))
        assertTrue("pas assez long pour être un avertissement explicite (${text.length} caractères)", text.length in 40..400)
    }

    @Test
    fun `the warning uses a colour distinct from plain body text and from the error colour`() {
        val block = layout.substringAfter("@+id/connect_encryption_warning").substringBefore("/>")
        assertTrue(block.contains("@color/warning"))
        val colors = read("res/values/colors.xml")
        val warning = Regex("""<color name="warning">(#[0-9A-Fa-f]+)</color>""").find(colors)!!.groupValues[1]
        val textSecondary = Regex("""<color name="text_secondary">(#[0-9A-Fa-f]+)</color>""").find(colors)!!.groupValues[1]
        val error = Regex("""<color name="error">(#[0-9A-Fa-f]+)</color>""").find(colors)!!.groupValues[1]
        assertTrue("distincte du texte courant", warning != textSecondary)
        assertTrue("distincte des messages d'erreur de connexion, pour ne pas les confondre", warning != error)
    }
}
