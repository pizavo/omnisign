package cz.pizavo.omnisign.cli

import java.util.Properties

/**
 * Provides build-time constants injected via generated resources.
 */
object BuildConfig {
    /**
     * The application version read from the generated `version.properties` resource.
     * Falls back to `"unknown"` if the resource cannot be found.
     */
    val VERSION: String by lazy {
        val props = Properties()
        BuildConfig::class.java.classLoader
            ?.getResourceAsStream("version.properties")
            ?.use { props.load(it) }
        props.getProperty("version", "unknown")
    }
}
