package cz.pizavo.omnisign.legal

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Reads the generated third-party credits carried on the JVM classpath.
 *
 * The root `:generateThirdPartyNotices` task writes [CREDITS_RESOURCE] into this module's
 * resources, so every JVM package that depends on `shared` — the CLI and the server — carries the
 * list without any packaging step of its own, and it is equally present when the modules are run
 * straight from Gradle. The desktop and web apps read the same content from their Compose
 * resource instead, because the web target has no JVM classpath to read from.
 *
 * Crediting the components is not merely courteous: the weak-copyleft licences OmniSign depends
 * on require each copy of the work to name the library, carry its licence, and — where the
 * program shows copyright notices at all — show the library's among them. This reader is what
 * lets a headless package discharge that duty on demand instead of only as a file on disk.
 *
 * A missing or unreadable resource yields an empty list rather than an exception: it can only
 * mean the package was assembled wrongly, and neither a CLI command nor an HTTP probe is a
 * useful place to surface that as a crash. Both callers report the empty result plainly.
 */
class ThirdPartyCreditsReader {

    /**
     * Every credited component across all four surfaces, parsed once.
     *
     * The file is a build artifact of a few dozen entries that cannot change while the process
     * runs, so it is read on first use and kept.
     */
    private val components: List<ThirdPartyComponent> by lazy { load() }

    /**
     * Returns the components the given package actually distributes.
     *
     * @param surface Surface tag to filter by: `cli`, `server`, `desktop` or `web`.
     * @return The matching components, in the order the generator emitted them; empty when the
     *   credits resource is absent or could not be parsed.
     */
    fun read(surface: String): List<ThirdPartyComponent> =
        components.filter { surface in it.surfaces }

    private fun load(): List<ThirdPartyComponent> {
        val stream = javaClass.getResourceAsStream(CREDITS_RESOURCE)
        if (stream == null) {
            logger.warn { "Third-party credits resource $CREDITS_RESOURCE is missing from the classpath" }
            return emptyList()
        }
        return runCatching {
            stream.use { CreditsJson.decodeFromString<List<ThirdPartyComponent>>(it.readBytes().decodeToString()) }
        }.getOrElse { failure ->
            logger.warn(failure) { "Could not parse the third-party credits resource $CREDITS_RESOURCE" }
            emptyList()
        }
    }

    companion object {
        /** Classpath location of the generated credits list. */
        const val CREDITS_RESOURCE: String = "/third-party-credits.json"

        /** Lenient reader, so a future field added by the generator cannot break the callers. */
        private val CreditsJson = Json { ignoreUnknownKeys = true }
    }
}
