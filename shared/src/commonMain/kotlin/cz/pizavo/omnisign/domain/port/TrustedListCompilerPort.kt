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
	 * A caller registering the written list should use the returned URI as its
	 * [cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig.source] rather than build one
	 * from [outputPath]: prefixing a path with `file:///` gives no valid URI for a Windows path or
	 * one with a space, and DSS cannot load the list from such a source.
	 *
	 * @param draft The trusted list definition to compile.
	 * @param outputPath Absolute path to the destination file.
	 * @return The written file's `file:` URI, or an
	 *   [cz.pizavo.omnisign.domain.model.error.OperationError] on failure.
	 */
	fun compileTo(draft: CustomTrustedListDraft, outputPath: String): OperationResult<String>
}

