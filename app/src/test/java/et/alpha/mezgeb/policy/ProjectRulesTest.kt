package et.alpha.mezgeb.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The project rules in CLAUDE.md §7 and CONVENTIONS.md, as tests.
 *
 * Each of these was true when it was written and is the kind of thing a later change
 * breaks without anyone noticing: one stray import, one debug print of a message body,
 * one `Double` that looked harmless. A rule that is only written down is a rule that
 * holds until the first busy day.
 *
 * These read the source tree rather than the compiled app, because the rules are about
 * what is in the source. The unit-test working directory is the `app` module, which is
 * where the relative paths below start.
 */
class ProjectRulesTest {

    @Test
    fun `the parser module imports nothing from Android`() {
        // CONVENTIONS.md hard constraint #4. The accuracy harness runs in plain JUnit
        // only because nothing in :parser needs a device.
        val offenders = kotlinFiles(PARSER_MAIN).flatMap { file ->
            file.matchingLines(Regex("""^\s*import\s+androidx?\."""))
        }
        assertNone("Android imports in :parser", offenders)
    }

    @Test
    fun `no database builder anywhere falls back to a destructive migration`() {
        // Prose may name the method, to say it is not used. Calling it is what wipes a
        // shop's ledger on an app update.
        val offenders = (kotlinFiles(APP_SRC) + kotlinFiles(PARSER_SRC))
            .filterNot { it.name == "ProjectRulesTest.kt" }
            .flatMap { it.matchingLines(Regex("""fallbackToDestructiveMigration\w*\s*\(""")) }
        assertNone("destructive migration fallbacks", offenders)
    }

    @Test
    fun `shipped code never logs or prints`() {
        // CONVENTIONS.md hard constraint #5. There is no safe log line in this app: the
        // values worth logging are amounts, numbers and names, and those stay on the
        // device. Tests may print; shipped code may not.
        val forbidden = Regex(
            """\bandroid\.util\.Log\b|\bLog\.(v|d|i|w|e|wtf)\(|\bTimber\b|\bprintln\(|""" +
                """\bprint\(|System\.(out|err)\b|\.printStackTrace\(""",
        )
        val offenders = shippedKotlinFiles().flatMap { it.matchingLines(forbidden) }
        assertNone("log or print calls in shipped code", offenders)
    }

    @Test
    fun `money is never held in a floating-point type`() {
        // CONVENTIONS.md hard constraint #3. A value named for money, declared Float or
        // Double, anywhere in shipped code. Confidence scores are floats and are fine;
        // they are not named for money.
        val forbidden = Regex(
            """\b(\w*Minor\w*|amount\w*)\s*:\s*(Float|Double)\b""",
            RegexOption.IGNORE_CASE,
        )
        val offenders = shippedKotlinFiles().flatMap { it.matchingLines(forbidden) }
        assertNone("money held as Float or Double", offenders)
    }

    @Test
    fun `no manifest asks for the network, and only direct asks for messages`() {
        // The text of the file, comments included: the restricted-permission policy
        // covers commented-out and placeholder declarations too.
        val internet = Regex("""android\.permission\.INTERNET\b""")
        val messages = Regex("""\b(READ_SMS|RECEIVE_SMS)\b""")

        for (path in listOf(MAIN_MANIFEST, PLAY_MANIFEST, DIRECT_MANIFEST)) {
            assertNone("INTERNET in $path", File(path).matchingLines(internet))
        }
        for (path in listOf(MAIN_MANIFEST, PLAY_MANIFEST)) {
            assertNone("SMS permissions in $path", File(path).matchingLines(messages))
        }
        assertTrue(
            "the direct flavor is the one place READ_SMS belongs, and it has gone",
            File(DIRECT_MANIFEST).readText().contains("android.permission.READ_SMS"),
        )
    }

    @Test
    fun `every database version has its exported schema committed`() {
        // A version bump without its schema leaves the next migration with nothing to
        // be tested against: MigrationTest builds old databases from these files.
        val source = File(DATABASE_SOURCE).readText()
        val version = Regex("""\bversion\s*=\s*(\d+)""").find(source)
            ?.groupValues?.get(1)?.toInt()
            ?: error("no version found in $DATABASE_SOURCE")

        val missing = (1..version).filterNot { File(SCHEMA_DIR, "$it.json").isFile }
        assertEquals("schema JSON missing for versions $missing", emptyList<Int>(), missing)

        // And the newest file really is that version, not a copy under a new name.
        val newest = File(SCHEMA_DIR, "$version.json").readText()
        assertTrue(
            "$version.json does not declare version $version",
            Regex(""""version"\s*:\s*$version\b""").containsMatchIn(newest),
        )

        val ahead = SCHEMA_DIR.listFiles().orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .filter { it > version }
        assertEquals(
            "schemas committed for versions the code is not at",
            emptyList<Int>(),
            ahead,
        )
    }

    // --- Helpers ---------------------------------------------------------------------

    private fun shippedKotlinFiles(): List<File> =
        listOf(APP_MAIN, APP_PLAY, APP_DIRECT, PARSER_MAIN).flatMap(::kotlinFiles)

    private fun kotlinFiles(root: String): List<File> {
        val dir = File(root)
        // A missing directory would make every check here pass by finding nothing.
        assertTrue(
            "expected source directory $root (cwd ${File(".").absolutePath})",
            dir.isDirectory,
        )
        return dir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "kts") }
            .toList()
    }

    private fun File.matchingLines(regex: Regex): List<String> =
        readLines().mapIndexedNotNull { index, line ->
            if (regex.containsMatchIn(line)) "$path:${index + 1}: ${line.trim()}" else null
        }

    private fun assertNone(what: String, offenders: List<String>) {
        assertTrue("Found $what:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private companion object {
        const val APP_SRC = "src"
        const val APP_MAIN = "src/main"
        const val APP_PLAY = "src/play"
        const val APP_DIRECT = "src/direct"
        const val PARSER_SRC = "../parser/src"
        const val PARSER_MAIN = "../parser/src/main"

        const val MAIN_MANIFEST = "src/main/AndroidManifest.xml"
        const val PLAY_MANIFEST = "src/play/AndroidManifest.xml"
        const val DIRECT_MANIFEST = "src/direct/AndroidManifest.xml"

        const val DATABASE_SOURCE = "src/main/java/et/alpha/mezgeb/data/local/MezgebDatabase.kt"
        val SCHEMA_DIR = File("schemas/et.alpha.mezgeb.data.local.MezgebDatabase")
    }
}
