package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig

/**
 * UI state for the Trusted List builder dialog.
 *
 * The sealed interface models every phase of the builder workflow so
 * that the Compose layer can pattern-match and render the appropriate content.
 */
sealed interface TlBuilderDialogState {

	/**
	 * The dialog is closed or not yet opened.
	 */
	data object Idle : TlBuilderDialogState

	/**
	 * The builder form is active and accepting user input.
	 *
	 * @property name Unique name for the trusted list (also used as the default output file stem).
	 * @property territory ISO 3166-1 alpha-2 territory code (e.g. `CZ`, `SK`).
	 * @property schemeOperatorName Name of the entity publishing this trusted list.
	 * @property tsps Mutable list of Trust Service Provider drafts.
	 * @property outputPath File path where the compiled XML will be written.
	 * @property registerAfterCompile Whether to register the compiled file as a custom TL source.
	 * @property error Human-readable validation or compilation error, or `null`.
	 */
	data class Editing(
		val name: String = "",
		val territory: String = "XX",
		val schemeOperatorName: String = "",
		val tsps: List<TspEditState> = emptyList(),
		val outputPath: String = "",
		val registerAfterCompile: Boolean = true,
		val error: String? = null,
	) : TlBuilderDialogState

	/**
	 * The trusted list is being compiled.
	 */
	data object Compiling : TlBuilderDialogState

	/**
	 * Compilation succeeded.
	 *
	 * @property outputFile Absolute path to the generated XML file.
	 * @property tlConfig The [CustomTrustedListConfig] created for registration, or `null` if the user opted out.
	 */
	data class Success(
		val outputFile: String,
		val tlConfig: CustomTrustedListConfig?,
	) : TlBuilderDialogState

	/**
	 * Compilation failed.
	 *
	 * @property message Human-readable error summary.
	 * @property details Optional technical details (e.g., stack trace excerpt).
	 */
	data class Error(
		val message: String,
		val details: String? = null,
	) : TlBuilderDialogState
}

/**
 * Editable state for a single Trust Service Provider within the builder dialog.
 *
 * @property name Official name of the TSP.
 * @property tradeName Optional trade/brand name.
 * @property infoUrl URL pointing to the TSP's information page.
 * @property services Services provided by this TSP.
 * @property expanded Whether the TSP card is currently expanded in the UI.
 */
data class TspEditState(
	val name: String = "",
	val tradeName: String = "",
	val infoUrl: String = "",
	val services: List<ServiceEditState> = emptyList(),
	val expanded: Boolean = true,
)

/**
 * Editable state for a single trust service within a [TspEditState].
 *
 * @property name Human-readable name of the service.
 * @property typeIdentifier ETSI service type identifier URI.
 * @property status ETSI service status URI.
 * @property certificatePath Path to the PEM or DER certificate file.
 */
data class ServiceEditState(
	val name: String = "",
	val typeIdentifier: String = "",
	val status: String = "",
	val certificatePath: String = "",
)

