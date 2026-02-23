package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for registering a custom trusted list source.
 *
 * The source may be an HTTPS URL or a `file://`-prefixed path to a locally compiled
 * TL XML.  An optional PEM/DER certificate can be supplied to verify the TL's own
 * XML signature.  When [profile] is specified the entry is stored inside that profile's
 * validation config; otherwise it is stored in the global config.
 */
class TrustedListAdder : CliktCommand(name = "add"), KoinComponent {
    private val manageTl: ManageTrustedListsUseCase by inject()

    private val name by option(
        "--name", "-n",
        help = "Unique label for this trusted list (used in profiles and `tl remove`)"
    ).required()

    private val source by option(
        "--source", "-s",
        help = "Source of the TL XML: HTTPS URL (e.g. https://example.com/tl.xml) " +
            "or a file path (e.g. /path/to/tl.xml)"
    ).required()

    private val signingCert by option(
        "--signing-cert",
        help = "Path to the PEM or DER certificate used to verify the TL's XML signature " +
            "(strongly recommended for non-EU trusted lists)"
    ).path(mustExist = true, canBeDir = false, mustBeReadable = true)

    private val profile by option(
        "--profile", "-p",
        help = "Store this trusted list in the given profile's validation config instead of the global config"
    )

    override fun help(context: Context): String =
        "Register a custom trusted list source (URL or local file)"

    override fun run(): Unit = runBlocking {
        val effectiveSource = resolveSource()
        val tl = CustomTrustedListConfig(
            name = name,
            source = effectiveSource,
            signingCertPath = signingCert?.toString()
        )
        manageTl.addTrustedList(tl, profile).fold(
            ifLeft = { error ->
                echo("❌ ${error.message}", err = true)
                error.details?.let { echo("Details: $it", err = true) }
            },
            ifRight = {
                val scope = profile?.let { "profile '$it'" } ?: "global config"
                echo("✅ Trusted list '$name' registered in $scope.")
                if (tl.signingCertPath == null) {
                    echo("⚠️  No signing certificate provided — TL signature will not be verified.")
                }
            }
        )
    }

    /**
     * Normalise a plain filesystem path to a `file://` URL so that DSS
     * can load it consistently.  Absolute HTTPS/HTTP/file URLs are passed through unchanged.
     */
    private fun resolveSource(): String {
        if (source.startsWith("http://") || source.startsWith("https://") ||
            source.startsWith("file://")
        ) return source
        val file = java.io.File(source)
        return file.toURI().toString()
    }
}

/**
 * CLI subcommand for listing all registered custom trusted list sources.
 *
 * When [profile] is specified, lists only the TLs registered in that profile;
 * otherwise lists the global TLs.
 */
class TrustedListLister : CliktCommand(name = "list"), KoinComponent {
    private val manageTl: ManageTrustedListsUseCase by inject()

    private val profile by option(
        "--profile", "-p",
        help = "List trusted lists from the given profile instead of the global config"
    )

    override fun help(context: Context): String =
        "List all registered custom trusted list sources"

    override fun run(): Unit = runBlocking {
        manageTl.listTrustedLists(profile).fold(
            ifLeft = { error -> echo("❌ ${error.message}", err = true) },
            ifRight = { lists ->
                val scope = profile?.let { "profile '$it'" } ?: "global config"
                if (lists.isEmpty()) {
                    echo("No custom trusted lists registered in $scope. Add one with: config tl add --name <n> --source <url>")
                } else {
                    echo("Custom trusted lists ($scope):")
                    lists.forEach { tl ->
                        echo("  ● ${tl.name}")
                        echo("    Source      : ${tl.source}")
                        tl.signingCertPath?.let { echo("    Signing cert: $it") }
                            ?: echo("    Signing cert: (none — signature not verified)")
                    }
                }
            }
        )
    }
}

/**
 * CLI subcommand for removing a registered custom trusted list source by name.
 *
 * When [profile] is specified, removes the entry from that profile's validation config;
 * otherwise removes it from the global config.
 */
class TrustedListRemover : CliktCommand(name = "remove"), KoinComponent {
    private val manageTl: ManageTrustedListsUseCase by inject()

    private val name by argument(help = "Name of the trusted list to remove")

    private val profile by option(
        "--profile", "-p",
        help = "Remove the trusted list from the given profile instead of the global config"
    )

    override fun help(context: Context): String =
        "Remove a registered custom trusted list source"

    override fun run(): Unit = runBlocking {
        manageTl.removeTrustedList(name, profile).fold(
            ifLeft = { error -> echo("❌ ${error.message}", err = true) },
            ifRight = {
                val scope = profile?.let { "profile '$it'" } ?: "global config"
                echo("✅ Trusted list '$name' removed from $scope.")
            }
        )
    }
}

