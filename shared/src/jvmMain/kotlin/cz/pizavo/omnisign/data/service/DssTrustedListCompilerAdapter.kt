package cz.pizavo.omnisign.data.service

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import java.io.File

/**
 * JVM adapter that delegates to the DSS-based [TrustedListCompiler].
 *
 * Wraps compilation results and failures into [OperationResult] so that
 * calling code never needs to handle raw exceptions.
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
				message = "Failed to compile trusted list '${draft.name}': ${e.message}",
				cause = e,
			).left()
		}

	/** @inheritDoc */
	override fun compileTo(draft: CustomTrustedListDraft, outputPath: String): OperationResult<Unit> =
		try {
			compiler.compileTo(draft, File(outputPath))
			Unit.right()
		} catch (e: Exception) {
			ConfigurationError.SaveFailed(
				message = "Failed to write trusted list '${draft.name}' to $outputPath: ${e.message}",
				cause = e,
			).left()
		}
}

