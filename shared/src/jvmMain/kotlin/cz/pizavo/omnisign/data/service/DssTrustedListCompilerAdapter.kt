package cz.pizavo.omnisign.data.service

import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * JVM adapter that delegates to the DSS-based [TrustedListCompiler].
 *
 * Wraps compilation results and failures into [OperationResult] so that
 * calling code never needs to handle raw exceptions. A draft the compiler rejects is a
 * [ConfigurationError.InvalidConfiguration] carrying the compiler's own message, which already
 * names the list; only a compiled list that cannot be written is a [ConfigurationError.SaveFailed].
 *
 * @param compiler The JAXB-backed trusted list compiler.
 */
class DssTrustedListCompilerAdapter(
	private val compiler: TrustedListCompiler,
) : TrustedListCompilerPort {

	/** @inheritDoc */
	override fun compile(draft: CustomTrustedListDraft): OperationResult<String> =
		try {
			compiler.compile(draft).right()
		} catch (e: Exception) {
			ConfigurationError.InvalidConfiguration(
				LocalizableText.Literal(e.message ?: "Failed to compile trusted list '${draft.name}'"),
				cause = e,
			).left()
		}

	/**
	 * Compile [draft], write it to [outputPath], creating parent directories if necessary, and
	 * return the written file's URI.
	 *
	 * The URI comes from [java.nio.file.Path.toUri], so it is absolute, uses forward slashes and is
	 * percent-encoded, which is the form DSS opens as a trusted-list source. Prefixing the path with
	 * `file:///` does not give one: DSS rejects a Windows path's backslashes, and a space in any
	 * path, as illegal URI characters.
	 */
	override fun compileTo(draft: CustomTrustedListDraft, outputPath: String): OperationResult<String> =
		compile(draft).flatMap { xml ->
			try {
				val outputFile = Path(outputPath)
				outputFile.parent?.createDirectories()
				outputFile.writeText(xml)
				outputFile.toUri().toString().right()
			} catch (e: Exception) {
				ConfigurationError.SaveFailed(
					LocalizableText.Literal("Failed to write trusted list '${draft.name}' to $outputPath: ${e.message}"),
					cause = e,
				).left()
			}
		}
}
