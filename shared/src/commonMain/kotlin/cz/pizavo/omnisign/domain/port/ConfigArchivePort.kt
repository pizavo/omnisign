package cz.pizavo.omnisign.domain.port

import cz.pizavo.omnisign.domain.model.result.OperationResult

/**
 * Port for exporting and importing the full application configuration as a portable archive — the
 * configuration plus the trusted certificates it depends on.
 *
 * Implemented on the JVM by [cz.pizavo.omnisign.domain.usecase.ConfigArchiveUseCase]; absent on
 * targets without a file backend (web), where the desktop Backup controls are disabled.
 */
interface ConfigArchivePort {
	/**
	 * Build the full-configuration archive (global settings, every profile, and all trusted
	 * certificates) as ZIP bytes.
	 */
	suspend fun exportFullConfig(): OperationResult<ByteArray>

	/**
	 * Replace the entire current configuration with the contents of the [archive] ZIP bytes.
	 */
	suspend fun importFullConfig(archive: ByteArray): OperationResult<Unit>
}
