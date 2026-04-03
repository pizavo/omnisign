package cz.pizavo.omnisign.domain.port

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.result.OperationResult

/**
 * Platform-agnostic port for compiling a [CustomTrustedListDraft] into an
 * ETSI TS 119612 XML document.
 *
 * On JVM the implementation delegates to the DSS JAXB-based
 * `TrustedListCompiler`; other platforms may provide alternative
 * implementations or leave the port unregistered.
 */
interface TrustedListCompilerPort {

	/**
	 * Compile [draft] to an ETSI TS 119612 XML string.
	 *
	 * @param draft The trusted list definition to compile.
	 * @return The serialized XML on the right, or an [cz.pizavo.omnisign.domain.model.error.OperationError] on the left.
	 */
	fun compile(draft: CustomTrustedListDraft): OperationResult<String>

	/**
	 * Compile [draft] and write the resulting XML to the file at [outputPath],
	 * creating parent directories if necessary.
	 *
	 * @param draft The trusted list definition to compile.
	 * @param outputPath Absolute path to the destination file.
	 * @return Unit on success, or an [cz.pizavo.omnisign.domain.model.error.OperationError] on failure.
	 */
	fun compileTo(draft: CustomTrustedListDraft, outputPath: String): OperationResult<Unit>
}

