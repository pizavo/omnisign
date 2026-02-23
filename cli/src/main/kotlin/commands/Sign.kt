package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.float
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.cli.OperationConfigOptions
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI command for signing PDF documents with a PAdES digital signature.
 *
 * The active profile and global configuration are resolved at runtime and can be
 * overridden per-execution with the shared [OperationConfigOptions] group.
 */
class Sign : CliktCommand(name = "sign"), KoinComponent {

    private val signUseCase: SignDocumentUseCase by inject()
    private val configRepository: ConfigRepository by inject()

    private val inputFile by option("-f", "--file", help = "Path to the PDF file to sign")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val outputFile by option("-o", "--output", help = "Path for the signed output PDF file")
        .path(canBeDir = false)
        .required()

    private val certificate by option(
        "-c", "--certificate",
        help = "Certificate alias to use for signing. Run 'omnisign certificates list' to see available aliases."
    )

    private val reason by option("-r", "--reason", help = "Reason for signing (embedded in the signature)")

    private val location by option("--location", help = "Location of signing (embedded in the signature)")

    private val contact by option("--contact", help = "Contact information of the signer (embedded in the signature)")

    private val noTimestamp by option(
        "--no-timestamp",
        help = "Omit the RFC 3161 timestamp from the signature (produces B-B instead of B-T or higher)"
    ).flag(default = false)

    private val profile by option(
        "--profile",
        help = "Use a named configuration profile for this operation"
    )

    private val configOverrides by OperationConfigOptions()

    private val visibleSignature by option(
        "--visible",
        help = "Add a visible signature appearance to the document"
    ).flag(default = false)

    private val visPage by option("--vis-page", help = "Page number for the visible signature (default: 1)")
        .int()
        .default(1)

    private val visX by option("--vis-x", help = "X position of the visible signature in PDF user units")
        .float()

    private val visY by option("--vis-y", help = "Y position of the visible signature in PDF user units")
        .float()

    private val visWidth by option("--vis-width", help = "Width of the visible signature in PDF user units")
        .float()

    private val visHeight by option("--vis-height", help = "Height of the visible signature in PDF user units")
        .float()

    private val visText by option("--vis-text", help = "Custom text displayed inside the visible signature")

    private val visImage by option("--vis-image", help = "Path to an image for the visible signature")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)

    override fun help(context: Context): String =
        "Sign a PDF document with a PAdES digital signature"

    override fun run(): Unit = runBlocking {
        val appConfig = configRepository.getCurrentConfig()
        val activeProfile = profile ?: appConfig.activeProfile
        val profileConfig = activeProfile?.let { appConfig.profiles[it] }
        val operationConfig = configOverrides.toOperationConfig()
        val resolvedConfig = ResolvedConfig.resolve(
            global = appConfig.global,
            profile = profileConfig,
            operationOverrides = operationConfig
        )

        val parameters = SigningParameters(
            inputFile = inputFile.toAbsolutePath().toString(),
            outputFile = outputFile.toAbsolutePath().toString(),
            certificateAlias = certificate,
            hashAlgorithm = resolvedConfig.hashAlgorithm,
            signatureLevel = resolvedConfig.signatureLevel,
            reason = reason,
            location = location,
            contactInfo = contact,
            addTimestamp = !noTimestamp,
            visibleSignature = buildVisibleSignatureParameters(),
            resolvedConfig = resolvedConfig
        )

        signUseCase(parameters).fold(
            ifLeft = { error ->
                echo("❌ Signing Error: ${error.message}", err = true)
                error.details?.let { echo("Details: $it", err = true) }
                error.cause?.let { echo("Cause: ${it.message}", err = true) }
            },
            ifRight = { result ->
                printSigningResult(result.outputFile, result.signatureId, result.signatureLevel)
            }
        )
    }

    /**
     * Build [VisibleSignatureParameters] when [visibleSignature] is enabled and all
     * required geometry options (x, y, width, height) are present.
     * Emits a warning and returns null when any required geometry option is missing.
     */
    private fun buildVisibleSignatureParameters(): VisibleSignatureParameters? {
        if (!visibleSignature) return null
        
        val x = visX
        val y = visY
        val w = visWidth
        val h = visHeight
        
        if (x == null || y == null || w == null || h == null) {
            echo(
                "⚠️ --visible requires --vis-x, --vis-y, --vis-width and --vis-height. " +
                        "Visible signature will be skipped.",
                err = true
            )
            
            return null
        }
        
        return VisibleSignatureParameters(
            page = visPage,
            x = x,
            y = y,
            width = w,
            height = h,
            text = visText,
            imagePath = visImage?.toAbsolutePath()?.toString()
        )
    }

    /**
     * Print a formatted summary of the completed signing operation to stdout.
     */
    private fun printSigningResult(outputFile: String, signatureId: String, signatureLevel: String) {
        echo("═══════════════════════════════════════════════════════════════")
        echo("                      SIGNING RESULT")
        echo("═══════════════════════════════════════════════════════════════")
        echo("✅ Document signed successfully")
        echo("")
        echo("Output file    : $outputFile")
        echo("Signature ID   : $signatureId")
        echo("Signature level: $signatureLevel")
        echo("═══════════════════════════════════════════════════════════════")
    }
}

