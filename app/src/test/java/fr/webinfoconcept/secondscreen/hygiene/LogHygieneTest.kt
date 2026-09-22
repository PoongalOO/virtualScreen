package fr.webinfoconcept.secondscreen.hygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SS-071 : garde-fous **automatiques** contre une fuite dans les journaux. Ces tests lisent le code source de l'application ;
 * ils échouent dès qu'on ajoute un appel de journal, une interpolation de secret dans un message d'erreur, une classe de
 * données qui porte un secret, ou qu'on affaiblit un champ de mot de passe. Ils ne prouvent pas l'absence de tout défaut (un
 * secret peut fuir de mille façons) : ils interdisent les façons courantes, et empêchent que la propriété ne se perde en silence.
 */
class LogHygieneTest {

    private val main: File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private class Line(val file: String, val number: Int, val text: String) {
        override fun toString() = "$file:$number : ${text.trim()}"
    }

    /** Lignes de code Kotlin (commentaires et KDoc retirés). */
    private val code: List<Line> by lazy {
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val rel = f.relativeTo(File(main, "java/fr/webinfoconcept/secondscreen")).path
            var inBlockComment = false
            f.readLines().mapIndexedNotNull { i, raw ->
                var line = raw
                if (inBlockComment) {
                    if (line.contains("*/")) { inBlockComment = false; line = line.substringAfter("*/") } else return@mapIndexedNotNull null
                }
                val trimmed = line.trim()
                if (trimmed.startsWith("//")) return@mapIndexedNotNull null
                if (trimmed.startsWith("/*")) {
                    if (!trimmed.contains("*/")) inBlockComment = true
                    return@mapIndexedNotNull null
                }
                if (trimmed.startsWith("*")) return@mapIndexedNotNull null
                Line(rel, i + 1, stripTrailingComment(line))
            }.asSequence()
        }.toList()
    }

    /** Retire un `// commentaire` de fin de ligne (hors d'un texte entre guillemets). */
    private fun stripTrailingComment(line: String): String {
        var quotes = 0
        var i = 0
        while (i < line.length - 1) {
            if (line[i] == '"' && (i == 0 || line[i - 1] != '\\')) quotes++
            if (quotes % 2 == 0 && line[i] == '/' && line[i + 1] == '/') return line.substring(0, i)
            i++
        }
        return line
    }

    // ================================================================== 1. les sorties de journal

    @Test
    fun `the only logging call of the whole application is the numbers-only measures line`() {
        val sinks = Regex("""\b(Log\.[a-z]+\s*\(|println\s*\(|print\s*\(|System\.(out|err)\b|printStackTrace|Timber\b|Slog\.|Logger\b|java\.util\.logging|android\.util\.Log\b|Log\.isLoggable)""")
        val found = code.filter { sinks.containsMatchIn(it.text) }

        assertEquals("sorties de journal : ${found.joinToString("\n  ", prefix = "\n  ")}", 2, found.size)
        assertTrue("l'import", found.any { it.file == "RemoteActivity.kt" && it.text.trim() == "import android.util.Log" })
        val call = found.single { it.text.contains("Log.i(") }
        assertEquals("RemoteActivity.kt", call.file)
        // Étiquette et contenu viennent de PerfLogLine, dont un test prouve qu'il ne contient que des nombres.
        assertTrue(call.toString(), call.text.contains("Log.i(PerfLogLine.TAG, PerfLogLine.format("))
    }

    // ================================================================== 2. messages d'erreur et toString

    private val secretNames = "password|passwd|pwd|secret|passphrase|token|text|typed|char|chars|keysym|keysyms|clip|clipboard|" +
        "composing|committed|challenge|response|reason|name|desktopName|host|pixel|pixels"

    /** `$secret` ou `${secret}`, mais pas `${secret.size}` : une taille n'est pas le contenu. */
    private val interpolation = Regex("""\$\{?\s*($secretNames)\b(?!\s*\.\s*(size|length|count|isEmpty|isNotEmpty)\b)""")

    private val throwingOrPrinting = Regex("""(\brequire\(|\bcheck\(|\berror\(|\bthrow\b|Exception\(|IllegalArgument|IllegalState|Log\.|toString|assert)""")

    /** Affichages à l'écran de l'utilisateur, jamais journalisés : l'adresse et le nom qu'il a lui-même saisis. */
    private val displayOnly = setOf(
        "session/ConnectionParams.kt" to "override fun toString(): String = \"\$host:\$port\"",
        "profile/ConnectionProfile.kt" to "fun label(): String = \"\$name — \$host:\$port\""
    )

    @Test
    fun `no error message, log or toString interpolates a secret, a typed value, a server text or an address`() {
        val violations = code.mapIndexedNotNull { index, line ->
            if (!interpolation.containsMatchIn(line.text)) return@mapIndexedNotNull null
            if ((line.file to line.text.trim()) in displayOnly) return@mapIndexedNotNull null
            val context = code.subList(maxOf(0, index - 4), index + 1).filter { it.file == line.file }.joinToString(" ") { it.text }
            if (throwingOrPrinting.containsMatchIn(context)) line else null
        }
        assertTrue("interpolation d'une valeur sensible dans un message :${violations.joinToString("\n  ", prefix = "\n  ")}", violations.isEmpty())
    }

    @Test
    fun `the display-only exceptions are exactly the two known lines`() {
        // Si l'une de ces lignes change ou disparaît, l'exception ne doit pas rester en liste blanche « au cas où ».
        for ((file, text) in displayOnly) {
            assertTrue("$file : ligne introuvable, retirer l'exception : $text", code.any { it.file == file && it.text.trim() == text })
        }
    }

    // ================================================================== 3. classes de données

    @Test
    fun `no data class carries a secret, a typed value or a clipboard text`() {
        val forbidden = Regex("""\b(password|passwd|pwd|secret|passphrase|token|typed|text|clipboard|composing|committed|challenge|response|reason)\s*:""")
        val declaration = Regex("""\bdata class\s+\w+\s*\(""")
        val violations = mutableListOf<String>()
        val flat = code.joinToString("\n") { "${it.file}|${it.text}" }
        for (m in declaration.findAll(flat)) {
            var depth = 1
            var i = m.range.last + 1
            while (i < flat.length && depth > 0) {
                if (flat[i] == '(') depth++ else if (flat[i] == ')') depth--
                i++
            }
            val params = flat.substring(m.range.last + 1, i)
            if (forbidden.containsMatchIn(params)) violations += flat.substring(m.range.first, minOf(i, m.range.first + 120)).lines().first()
        }
        assertTrue("classe de données avec un champ sensible (son toString le montrerait) :$violations", violations.isEmpty())
    }

    // ================================================================== 4. champs de mot de passe, clavier, stockage

    private fun read(path: String) = File(main, path).readText()

    @Test
    fun `the connection password field is never saved and is a password field`() {
        val layout = read("res/layout/activity_connect.xml")
        val field = layout.split("<EditText").drop(1).single { it.contains("android:id=\"@+id/connect_password\"") }.substringBefore("/>")
        assertTrue("saveEnabled=false", field.contains("android:saveEnabled=\"false\""))
        assertTrue("textPassword", field.contains("android:inputType=\"textPassword\""))
    }

    @Test
    fun `the reconnection password dialog field is a password field and is never saved`() {
        val source = read("java/fr/webinfoconcept/secondscreen/RemoteActivity.kt")
        assertTrue(source.contains("TYPE_TEXT_VARIATION_PASSWORD"))
        assertTrue(source.contains("isSaveEnabled = false"))
        assertTrue("le champ est vidé après copie", source.contains("editable.clear()"))
    }

    @Test
    fun `the remote keyboard never lets the keyboard app learn what is typed`() {
        val source = read("java/fr/webinfoconcept/secondscreen/input/KeyboardInputView.kt")
        // Ce qu'on tape sur la tablette peut être un mot de passe de l'application distante.
        assertTrue(source.contains("TYPE_TEXT_FLAG_NO_SUGGESTIONS"))
        assertTrue(source.contains("TYPE_TEXT_VARIATION_VISIBLE_PASSWORD"))
    }

    @Test
    fun `nothing is backed up, written outside private storage, or readable by other applications`() {
        val manifest = read("AndroidManifest.xml")
        assertTrue("sauvegarde désactivée", manifest.contains("android:allowBackup=\"false\""))
        assertTrue("pas de drapeau debuggable dans le manifeste", !manifest.contains("android:debuggable"))
        val permissions = Regex("""<uses-permission android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals("seule la permission réseau, ni lecture du journal ni stockage externe", listOf("android.permission.INTERNET"), permissions)

        val storage = Regex("""getExternal|openFileOutput|FileOutputStream|FileWriter|MODE_WORLD|Environment\.|getCacheDir|getFilesDir|\.cacheDir|\.filesDir""")
        val hits = code.filter { storage.containsMatchIn(it.text) }
        assertTrue("écriture de fichier :${hits.joinToString("\n  ", prefix = "\n  ")}", hits.isEmpty())
        assertTrue("préférences privées", code.any { it.text.contains("Context.MODE_PRIVATE") } && code.none { it.text.contains("getSharedPreferences") && !it.text.contains("MODE_PRIVATE") })
    }
}
