package cz.pizavo.omnisign.cli

import ch.qos.logback.classic.Level

/**
 * Third-party library loggers lowered to DEBUG when `--debug-extended` is set.
 *
 * The `eu.europa.esig.dss.tsl.*` loggers pinned to ERROR in `logback.xml`
 * retain that explicit level even when their `eu.europa.esig` parent is
 * lowered, so the TSL firehose stays suppressed.
 */
internal val CliExtendedLoggers: List<String> = listOf("eu.europa.esig", "org.apache")

/**
 * Resolves the Logback root level for this CLI invocation given the global
 * verbosity flags.
 *
 * The CLI offers a three-tier verbosity ladder:
 * - default → `null` (the root level configured in `logback.xml`, normally WARN)
 * - `--verbose` → [Level.INFO]
 * - `--debug` or `--debug-extended` (which implies `--debug`) → [Level.DEBUG]
 *
 * `--debug` / `--debug-extended` win when combined with `--verbose`.
 *
 * Returning `null` for the default case lets the caller leave the root logger
 * untouched, so the static `logback.xml` configuration (and any explicit
 * per-logger pins it sets, e.g. `eu.europa.esig` → ERROR) keep their effect.
 */
internal fun rootLogLevel(verbose: Boolean, debug: Boolean, extended: Boolean): Level? = when {
    debug || extended -> Level.DEBUG
    verbose -> Level.INFO
    else -> null
}

/**
 * Level to apply to each of [CliExtendedLoggers] when `--debug-extended` is
 * set: [Level.DEBUG] when on, `null` (leave the logger as configured) when off.
 */
internal fun extendedLibraryLevel(extended: Boolean): Level? = if (extended) Level.DEBUG else null
