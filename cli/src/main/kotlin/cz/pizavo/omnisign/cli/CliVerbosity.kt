package cz.pizavo.omnisign.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext

/**
 * Third-party library loggers lowered to DEBUG when `--debug-extended` is set.
 *
 * A logger below them that `logback.xml` gives a level of its own, such as
 * PDFBox's parser, is listed too, since that level would otherwise keep it
 * quiet. The `eu.europa.esig.dss.tsl.*` loggers pinned to ERROR in
 * `logback.xml` are left out, so they retain that explicit level even when
 * their `eu.europa.esig` parent is lowered, and the TSL firehose stays
 * suppressed. HttpClient's `org.apache.hc.client5.http.headers` and
 * `org.apache.hc.client5.http.wire` loggers, pinned to INFO there, are left out
 * too: at DEBUG they write every request header, a timestamp server's
 * `Authorization: Basic` credentials included, to stderr and to `--log-file`.
 */
internal val CliExtendedLoggers: List<String> = listOf("eu.europa.esig", "org.apache", "org.apache.pdfbox.pdfparser")

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

/**
 * Applies the verbosity flags to [context]: the root level [rootLogLevel]
 * resolves and, with [extended], the [extendedLibraryLevel] on each of
 * [CliExtendedLoggers]. Any other logger `logback.xml` gives a level of its own
 * keeps that level.
 */
internal fun applyLogLevels(context: LoggerContext, verbose: Boolean, debug: Boolean, extended: Boolean) {
    rootLogLevel(verbose = verbose, debug = debug, extended = extended)?.let { target ->
        context.getLogger(Logger.ROOT_LOGGER_NAME).level = target
    }
    extendedLibraryLevel(extended = extended)?.let { target ->
        for (name in CliExtendedLoggers) context.getLogger(name).level = target
    }
}
