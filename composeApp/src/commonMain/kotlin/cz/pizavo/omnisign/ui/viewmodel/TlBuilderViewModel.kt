package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import cz.pizavo.omnisign.ui.model.ServiceEditState
import cz.pizavo.omnisign.ui.model.TlBuilderDialogState
import cz.pizavo.omnisign.ui.model.TspEditState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel driving the Trusted List builder dialog.
 *
 * Manages the in-memory editing state, validates the form, builds a
 * [CustomTrustedListDraft], compiles it via [TrustedListCompilerPort],
 * and returns a [CustomTrustedListConfig] for the caller to register.
 *
 * No intermediate draft persistence is performed — the entire trusted list
 * is built in memory and saved only when the user clicks "Compile & Save".
 *
 * @param compilerPort Platform-specific compiler, or `null` on platforms that
 *   cannot compile (e.g., Wasm).
 * @param ioDispatcher Dispatcher for the compilation I/O work.
 */
class TlBuilderViewModel(
	private val compilerPort: TrustedListCompilerPort? = null,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

	private val _state = MutableStateFlow<TlBuilderDialogState>(TlBuilderDialogState.Idle)

	/** Observable builder dialog state. */
	val state: StateFlow<TlBuilderDialogState> = _state.asStateFlow()

	/**
	 * Open the builder dialog with an empty editing form.
	 *
	 * @param defaultOutputDir Optional directory prefix for the default output path.
	 */
	fun open(defaultOutputDir: String = "") {
		_state.value = TlBuilderDialogState.Editing(
			outputPath = if (defaultOutputDir.isNotBlank()) "$defaultOutputDir/" else "",
		)
	}

	/**
	 * Apply a field-level transform to the current [TlBuilderDialogState.Editing] state.
	 *
	 * @param transform Function receiving the current editing state and returning the updated one.
	 */
	fun updateState(transform: (TlBuilderDialogState.Editing) -> TlBuilderDialogState.Editing) {
		_state.update { current ->
			if (current is TlBuilderDialogState.Editing) transform(current) else current
		}
	}

	/**
	 * Append a new empty [TspEditState] to the TSP list.
	 */
	fun addTsp() {
		updateState { it.copy(tsps = it.tsps + TspEditState()) }
	}

	/**
	 * Remove the TSP at [index] from the list.
	 */
	fun removeTsp(index: Int) {
		updateState { editing ->
			editing.copy(tsps = editing.tsps.toMutableList().apply { removeAt(index) })
		}
	}

	/**
	 * Append a new empty [ServiceEditState] to the TSP at [tspIndex].
	 */
	fun addService(tspIndex: Int) {
		updateState { editing ->
			editing.copy(
				tsps = editing.tsps.mapIndexed { i, tsp ->
					if (i == tspIndex) tsp.copy(services = tsp.services + ServiceEditState())
					else tsp
				}
			)
		}
	}

	/**
	 * Remove the service at [serviceIndex] from the TSP at [tspIndex].
	 */
	fun removeService(tspIndex: Int, serviceIndex: Int) {
		updateState { editing ->
			editing.copy(
				tsps = editing.tsps.mapIndexed { i, tsp ->
					if (i == tspIndex) tsp.copy(
						services = tsp.services.toMutableList().apply { removeAt(serviceIndex) }
					)
					else tsp
				}
			)
		}
	}

	/**
	 * Validate the current form, compile the trusted list to XML, and
	 * transition to [TlBuilderDialogState.Success] or [TlBuilderDialogState.Error].
	 */
	fun compile() {
		val editing = _state.value as? TlBuilderDialogState.Editing ?: return
		val error = validate(editing)
		if (error != null) {
			_state.value = editing.copy(error = error)
			return
		}

		if (compilerPort == null) {
			_state.value = TlBuilderDialogState.Error(
				message = "Trusted list compilation is not available on this platform.",
			)
			return
		}

		_state.value = TlBuilderDialogState.Compiling
		val draft = toDraft(editing)
		val outputPath = editing.outputPath.trim()

		viewModelScope.launch {
			withContext(ioDispatcher) {
				compilerPort.compileTo(draft, outputPath)
			}.fold(
				ifLeft = { opError ->
					_state.value = TlBuilderDialogState.Error(
						message = opError.message,
						details = opError.details,
					)
				},
				ifRight = {
					val tlConfig = if (editing.registerAfterCompile) {
						CustomTrustedListConfig(
							name = editing.name.trim(),
							source = "file:///$outputPath",
						)
					} else {
						null
					}
					_state.value = TlBuilderDialogState.Success(
						outputFile = outputPath,
						tlConfig = tlConfig,
					)
				},
			)
		}
	}

	/**
	 * Reset the dialog to [TlBuilderDialogState.Idle].
	 */
	fun dismiss() {
		_state.value = TlBuilderDialogState.Idle
	}

	/**
	 * Validate the editing state and return an error message, or `null` if valid.
	 */
	private fun validate(editing: TlBuilderDialogState.Editing): String? {
		if (editing.name.isBlank()) return "Name is required."
		if (editing.territory.isBlank()) return "Territory code is required."
		if (editing.schemeOperatorName.isBlank()) return "Scheme operator name is required."
		if (editing.outputPath.isBlank()) return "Output file path is required."
		if (editing.tsps.isEmpty()) return "At least one Trust Service Provider is required."

		editing.tsps.forEachIndexed { tspIdx, tsp ->
			if (tsp.name.isBlank()) return "TSP #${tspIdx + 1}: name is required."
			if (tsp.services.isEmpty()) return "TSP '${tsp.name}': at least one service is required."

			tsp.services.forEachIndexed { svcIdx, svc ->
				if (svc.name.isBlank()) return "TSP '${tsp.name}', Service #${svcIdx + 1}: name is required."
				if (svc.typeIdentifier.isBlank()) return "TSP '${tsp.name}', Service '${svc.name}': type identifier is required."
				if (svc.status.isBlank()) return "TSP '${tsp.name}', Service '${svc.name}': status is required."
				if (svc.certificatePath.isBlank()) return "TSP '${tsp.name}', Service '${svc.name}': certificate path is required."
			}
		}

		return null
	}

	/**
	 * Convert the editing state into a [CustomTrustedListDraft] suitable for the compiler.
	 */
	private fun toDraft(editing: TlBuilderDialogState.Editing): CustomTrustedListDraft =
		CustomTrustedListDraft(
			name = editing.name.trim(),
			territory = editing.territory.trim().uppercase(),
			schemeOperatorName = editing.schemeOperatorName.trim(),
			trustServiceProviders = editing.tsps.map { tsp ->
				TrustServiceProviderDraft(
					name = tsp.name.trim(),
					tradeName = tsp.tradeName.trim().ifBlank { null },
					infoUrl = tsp.infoUrl.trim(),
					services = tsp.services.map { svc ->
						TrustServiceDraft(
							name = svc.name.trim(),
							typeIdentifier = svc.typeIdentifier.trim(),
							status = svc.status.trim(),
							certificatePath = svc.certificatePath.trim(),
						)
					}
				)
			}
		)
}


