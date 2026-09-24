package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Nothing writes into the corpus.
 *
 * The corpus used to be read straight out of the laboratory's debug
 * folders, and every skill dumped the frame it gave up on into its debug
 * folder under a name with a counter that started over each run. Five
 * oracle frames were rewritten that way in one evening. So the corpus is a
 * folder of its own, `corpus/<folder>/`, a frame gets there by being moved
 * in by hand under a name with the time of day in it, never a counter --
 * and this holds every place the sources write a file against that
 * folder's name (PLAN_STANDALONE.md 2; the laboratory's `test_oracle.py`
 * did the same over the Python). Read from it as much as you like; a write
 * site that names it is red.
 */
class CorpusTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")

    /** A line that puts bytes somewhere: a write, a copy, a move, a folder made. */
    private val writeLine = Regex(
        """\b(?:imwrite|mkdirs?|writeBytes|writeText|appendBytes|appendText|outputStream|""" +
        """FileOutputStream|FileWriter|copyTo|copyRecursively|renameTo|Files\.(?:write|copy|move|createDirectories))\(""")

    private fun sources(): List<File> {
        val roots = listOf("core/src", "app/src").map { File(repo, it) }
        val kotlin = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val gradle = listOf("build.gradle.kts", "core/build.gradle.kts", "app/build.gradle.kts").map { File(repo, it) }
        return (kotlin + gradle).filter { it.exists() }.sortedBy { it.path }
    }

    @Test
    fun `no write site names the corpus`() {
        val corpus = OracleFamilies.CORPUS
        val problems = ArrayList<String>()
        var writeSites = 0
        for (file in sources()) {
            if (file.name == "CorpusTest.kt") continue
            file.readLines().forEachIndexed { i, line ->
                if (!writeLine.containsMatchIn(line)) return@forEachIndexed
                writeSites += 1
                if (corpus in line.lowercase()) {
                    problems += "${file.relativeTo(repo).path.replace('\\', '/')}:${i + 1} writes with the corpus in the line: ${line.trim()}"
                }
            }
        }
        for (p in problems) println("  $p")
        assertTrue(problems.isEmpty(), "${problems.size} write site(s) name the corpus")
        // The scan has to be able to see a write site at all, or a silent
        // regex would pass everything: the debug package and the oracle
        // writer are what it finds.
        assertTrue(writeSites >= 3, "the write-site scan sees $writeSites write sites, which is too few to trust it")
        println("write sites: $writeSites, none names $corpus/")
    }

    /** The corpus is pictures of the game, and stays out of the repository. */
    @Test
    fun `the corpus is ignored by git`() {
        val ignore = File(repo, ".gitignore").readLines().map { it.trim() }
        assertTrue("${OracleFamilies.CORPUS}/" in ignore, ".gitignore does not list ${OracleFamilies.CORPUS}/")
    }
}
