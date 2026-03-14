package cz.pizavo.omnisign.cli

/**
 * Global output configuration propagated from the root [cz.pizavo.omnisign.Omnisign]
 * command to every subcommand via [com.github.ajalt.clikt.core.Context.findOrSetObject].
 *
 * @property json When true, commands emit structured JSON instead of human-readable text.
 * @property verbose When true, the Logback root level is lowered to DEBUG for this invocation.
 * @property quiet When true, non-error informational output is suppressed.
 */
data class OutputConfig(
    val json: Boolean = false,
    val verbose: Boolean = false,
    val quiet: Boolean = false,
)

