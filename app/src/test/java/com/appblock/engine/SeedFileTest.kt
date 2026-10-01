package com.appblock.engine

import com.appblock.ActiveRules
import com.appblock.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gitignored build-time seed: what parses, what is refused, and the real file on this machine. */
class SeedFileTest {

    private val reddit = Target.forPackage("com.reddit.frontpage")

    @Test fun `sites and apps parse, comments and blank lines are ignored`() {
        val seed = SeedFile.parse(
            """
            # a comment
            site instagram.com

            site https://www.weshop.ai/path   # pasted URL, trailing comment
            app com.reddit.frontpage 20 30 40
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), seed.errors)
        assertEquals(listOf("instagram.com", "weshop.ai"), seed.sites)
        assertEquals(mapOf(reddit to TargetSettings(true, 20, 30, 40)), seed.apps)
    }

    @Test fun `empty text is the empty seed`() {
        assertEquals(SeedFile.Seed.EMPTY, SeedFile.parse(""))
    }

    @Test fun `CRLF line endings parse the same`() {
        val seed = SeedFile.parse("site instagram.com\r\napp com.reddit.frontpage 20 30 40\r\n")
        assertEquals(emptyList<String>(), seed.errors)
        assertEquals(listOf("instagram.com"), seed.sites)
        assertEquals(setOf(reddit), seed.apps.keys)
    }

    /**
     * A built-in or always-blocked package is refused, because the picker refuses it too: a seeded
     * `pkg:` row for TikTok would be a second, weaker target beside the real one, and one for Shizuku
     * would hand a permanently blocked package an editable rule a change window could switch off.
     */
    @Test fun `unofferable packages are refused`() {
        AppTargets.unofferablePackages.forEach { pkg ->
            val seed = SeedFile.parse("app $pkg 30 30 60")
            assertTrue(pkg, seed.apps.isEmpty())
            assertEquals(pkg, 1, seed.errors.size)
        }
    }

    @Test fun `bad lines are reported with their line number and skipped`() {
        val seed = SeedFile.parse(
            listOf(
                "site instagram.com",
                "site cat videos",                          // 2: two words after site
                "site notadomain",                          // 3: no dot
                "app com.reddit.frontpage 20 30",           // 4: missing ceiling
                "app com.reddit.frontpage 20 30 1441",      // 5: over a day
                "app com.reddit.frontpage 20 -5 40",        // 6: negative
                "app not-a-package 20 30 40",               // 7
                "block reddit.com",                         // 8: unknown entry
                "site instagram.com",                       // 9: duplicate
                "app com.reddit.frontpage 20 30 40",
                "app com.reddit.frontpage 10 10 10",        // 11: duplicate
            ).joinToString("\n"),
        )
        assertEquals(listOf("instagram.com"), seed.sites)
        assertEquals(mapOf(reddit to TargetSettings(true, 20, 30, 40)), seed.apps)
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 9, 11), seed.errors.map { it.substringAfter("line ").substringBefore(':').toInt() })
    }

    /** A seeded key has to survive [EngineCodec]'s delimiters, or the stored config would drop it. */
    @Test fun `every accepted package is an encodable key`() {
        val seed = SeedFile.parse("app com.reddit.frontpage 20 30 40\napp org.example_x.y2 1 1 1")
        assertEquals(2, seed.apps.size)
        seed.apps.keys.forEach { assertTrue(it.key, Target.isEncodableKey(it.key)) }
    }

    @Test fun `the committed example parses cleanly`() {
        val seed = SeedFile.parse(repoFile("seed.example.txt").readText())
        assertEquals(emptyList<String>(), seed.errors)
        assertTrue(seed.sites.isNotEmpty() && seed.apps.isNotEmpty())
    }

    /**
     * The real, gitignored `seed.txt` — when this machine has one. A typo there is otherwise silent:
     * the app skips the line and simply enforces less than the file says. On CI there is no file and
     * this passes trivially.
     */
    @Test fun `this machine's seed txt has no bad lines`() {
        val file = repoFile("seed.txt")
        if (!file.exists()) return
        assertEquals(emptyList<String>(), SeedFile.parse(file.readText()).errors)
    }

    /**
     * Only release builds carry the seed. If the debug variant ever picked it up, every unit test
     * touching the stores would pass on CI and fail on a laptop with a `seed.txt` — or the reverse.
     * In the release variant, it pins Gradle's string escaping: the compiled seed must equal the file.
     */
    @Test fun `the seed is compiled into release builds only, verbatim`() {
        if (BuildConfig.BUILD_TYPE == "release") {
            val file = repoFile("seed.txt")
            val expected = if (file.exists()) SeedFile.parse(file.readText()) else SeedFile.Seed.EMPTY
            assertEquals(expected, ActiveRules.seedFile)
        } else {
            assertEquals("", BuildConfig.SEED)
            assertEquals(SeedFile.Seed.EMPTY, ActiveRules.seedFile)
        }
    }

    /** Unit tests run with the module (`app/`) as the working directory. */
    private fun repoFile(name: String) = File("..", name)
}
