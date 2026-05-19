package cz.pizavo.omnisign.cli

/**
 * Global output configuration propagated from the root [cz.pizavo.omnisign.Omnisign]
 * command to every subcommand via [com.github.ajalt.clikt.core.Context.findOrSetObject].
 *
 * @property json When true, commands emit structured JSON instead of human-readable text.
 * @property verbose When true, the user asked for extra output beyond the default
 *   (any of `--verbose`, `--debug`, `--debug-extended`). Subcommands gate optional
 *   detail on this.
 * @property debug When true, the Logback root level was lowered to DEBUG for this
 *   invocation (passed via `--debug` or implied by `--debug-extended`). Implies [verbose].
 * @property extended When true, `--debug-extended` was passed; third-party library
 *   loggers (DSS, Apache) were also lowered to DEBUG. Implies [debug] and [verbose].
 * @property quiet When true, non-error informational output is suppressed.
 */
data class OutputConfig(
    val json: Boolean = false,
    val verbose: Boolean = false,
    val debug: Boolean = false,
    val extended: Boolean = false,
    val quiet: Boolean = false,
)

