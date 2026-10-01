package com.appblock.engine

/**
 * The private, gitignored build-time seed: blocked sites and picker-style apps that a fresh install
 * should come up with, so a reinstall no longer means rebuilding the config from memory (requested
 * 2026-08-04, after it had to be reconstructed from two screenshots).
 *
 * ## Why this is not the import [ConfigExport] refuses
 *
 * The rejected import was a **runtime** path — reinstall, edit one line, import, and everything comes
 * back minus the rule you wanted gone, without touching the gate. This file is read by Gradle and
 * compiled into the signed release APK (`BuildConfig.SEED`), so changing what it says needs the
 * laptop, the signing key and `adb install` — CONSTRAINTS §6's desk path, which already owns
 * [DefaultRules]. It moves where the values live, not who can change them.
 *
 * ⚠️ **It must never be read from a runtime path** (`/sdcard`, a content URI, a download). Anything the
 * phone can write without the key would resurrect exactly that import.
 *
 * ## Format — one entry per line, `#` starts a comment
 *
 * ```
 * site instagram.com
 * app com.reddit.frontpage 20 30 40     # weekday · weekend · exception ceiling, minutes
 * ```
 *
 * `app` lines name a package the picker could have offered: built-ins and the permanently blocked
 * packages are refused ([AppTargets.unofferablePackages]), because a seeded row for one of them would
 * be a second, weaker target beside the real one. The built-in rules themselves stay in
 * [DefaultRules] — they are public in CONSTRAINTS §1 anyway, and one source of truth beats two.
 *
 * ## How the stores apply it — add once, never overwrite, never remove
 *
 * Each line takes effect the first time a build carrying it starts, and is then recorded as applied.
 * A domain or app already present keeps its own settings, and one later removed through a change
 * window stays removed. So applying a seed is only ever a tightening — the one direction that is free.
 */
object SeedFile {

    /** What a seed file says, plus every line it could not use. */
    data class Seed(
        /** Normalized domains, in file order, no duplicates. */
        val sites: List<String>,
        /** User-package targets, in file order. */
        val apps: Map<Target, TargetSettings>,
        /** One message per rejected line (`line N: …`). Empty for a clean file. */
        val errors: List<String>,
    ) {
        companion object {
            val EMPTY = Seed(emptyList(), emptyMap(), emptyList())
        }
    }

    /**
     * Parses leniently: a bad line is reported in [Seed.errors] and skipped, never fatal. Skipping is
     * safe here because a seed only ever *adds* — a lost line enforces less than intended but nothing
     * already enforced is loosened. `SeedFileTest` fails the build-and-test loop on any error in the
     * real file, which is where a typo should be caught.
     */
    fun parse(text: String): Seed {
        val sites = LinkedHashSet<String>()
        val apps = LinkedHashMap<Target, TargetSettings>()
        val errors = mutableListOf<String>()

        text.lines().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val fail = { why: String -> errors += "line ${index + 1}: $why — \"${raw.trim()}\"" }
            val words = line.split(Regex("\\s+"))

            when (words[0].lowercase()) {
                "site" -> {
                    if (words.size != 2) return@forEachIndexed fail("expected `site <domain>`")
                    val domain = DomainMatcher.normalizeDomain(words[1])
                        ?: return@forEachIndexed fail("not a domain")
                    if (!sites.add(domain)) fail("duplicate site $domain")
                }
                "app" -> {
                    if (words.size != 5) {
                        return@forEachIndexed fail("expected `app <package> <weekday> <weekend> <ceiling>`")
                    }
                    val pkg = words[1]
                    if (!PACKAGE.matches(pkg)) return@forEachIndexed fail("not a package name")
                    if (pkg in AppTargets.unofferablePackages) {
                        return@forEachIndexed fail("$pkg is built in or always blocked — not seedable")
                    }
                    val minutes = words.drop(2).map { it.toIntOrNull()?.takeIf { m -> m in 0..MINUTES_PER_DAY } }
                    if (minutes.any { it == null }) {
                        return@forEachIndexed fail("minutes must be whole numbers 0–$MINUTES_PER_DAY")
                    }
                    val target = Target.forPackage(pkg)
                    if (target in apps) return@forEachIndexed fail("duplicate app $pkg")
                    apps[target] = TargetSettings(
                        enabled = true,
                        weekdayMinutes = minutes[0]!!,
                        weekendMinutes = minutes[1]!!,
                        exceptionMaxMinutes = minutes[2]!!,
                    )
                }
                else -> fail("unknown entry `${words[0]}` (expected `site` or `app`)")
            }
        }
        return Seed(sites.toList(), apps, errors)
    }

    private const val MINUTES_PER_DAY = 1440

    /** Java-style dotted package name; also keeps the key safe inside [EngineCodec]'s delimiters. */
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}
