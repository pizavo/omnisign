package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.ui.model.TimestampDialogState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel driving the timestamp / extension dialog.
 *
 * Orchestrates the extension of an already-signed PDF to a higher PAdES level.
 * The flow mirrors the CLI [cz.pizavo.omnisign.commands.Timestamp] command:
 * resolve config → build [ArchivingParameters] → invoke [ExtendDocumentUseCase]
 * → show result.
 *
 * @param extendDocumentUseCase Use case for extending a signed document.
 * @param configRepository Repository for reading the current application configuration.
 * @param ioDispatcher Dispatcher for heavy background work.
 */
class TimestampViewModel(
	private val extendDocumentUseCase: ExtendDocumentUseCase,
	private val configRepository: ConfigRepository,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

	private val _state = MutableStateFlow<TimestampDialogState>(TimestampDialogState.Idle)

	/** Observable timestamp dialog state. */
	val state: StateFlow<TimestampDialogState> = _state.asStateFlow()

	private var currentFilePath: String? = null
	private var resolvedConfig: ResolvedConfig? = null

	/**
	 * Open the timestamp dialog for the given document.
	 *
	 * Resolves the current configuration and transitions to [TimestampDialogState.Ready].
	 *
	 * @param filePath Absolute path to the signed PDF document to extend.
	 */
	fun open(filePath: String) {
		currentFilePath = filePath

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val appConfig = configRepository.getCurrentConfig()
				val activeProfile = appConfig.activeProfile
				val profileConfig = activeProfile?.let { appConfig.profiles[it] }

				val configResult = ResolvedConfig.resolve(
					global = appConfig.global,
					profile = profileConfig,
					operationOverrides = null,
				)

				configResult.fold(
					ifLeft = { error ->
						_state.value = TimestampDialogState.Error(
							message = "Configuration error: ${error.message}",
						)
					},
					ifRight = { config ->
						resolvedConfig = config
						val suggestedOutput = SigningViewModel.buildSuggestedOutputPath(filePath, "-extended")
						_state.value = TimestampDialogState.Ready(
							targetLevel = SignatureLevel.PADES_BASELINE_T,
							outputPath = suggestedOutput,
						)
					},
				)
			}
		}
	}

	/**
	 * Apply a field-level transformation to the current [TimestampDialogState.Ready] state.
	 *
	 * Has no effect when the state is not [TimestampDialogState.Ready].
	 *
	 * @param transform Function that receives the current ready state and returns the updated one.
	 */
	fun updateState(transform: (TimestampDialogState.Ready) -> TimestampDialogState.Ready) {
		_state.update { current ->
			if (current is TimestampDialogState.Ready) transform(current) else current
		}
	}

	/**
	 * Execute the extension operation with the current form state.
	 *
	 * Transitions from [TimestampDialogState.Ready] through [TimestampDialogState.Extending]
	 * to either [TimestampDialogState.Success] or [TimestampDialogState.Error].
	 */
	fun extend() {
		val ready = _state.value as? TimestampDialogState.Ready ?: return
		val inputFile = currentFilePath ?: return
		val config = resolvedConfig ?: return

		_state.value = TimestampDialogState.Extending

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val parameters = ArchivingParameters(
					inputFile = inputFile,
					outputFile = ready.outputPath,
					targetLevel = ready.targetLevel,
					resolvedConfig = config,
				)

				extendDocumentUseCase(parameters).fold(
					ifLeft = { error ->
						_state.value = TimestampDialogState.Error(
							message = error.message,
							details = error.details,
						)
					},
					ifRight = { result ->
						_state.value = TimestampDialogState.Success(
							outputFile = result.outputFile,
							newLevel = result.newSignatureLevel,
							warnings = result.warnings,
						)
					},
				)
			}
		}
	}

	/**
	 * Dismiss the dialog and reset the state to [TimestampDialogState.Idle].
	 */
	fun dismiss() {
		_state.value = TimestampDialogState.Idle
		resolvedConfig = null
	}

	companion object {
		/** PAdES levels valid as extension targets (excludes B-B which is not an extension). */
		val EXTENDABLE_LEVELS: List<SignatureLevel> =
			SignatureLevel.entries.filter { it != SignatureLevel.PADES_BASELINE_B }
	}
}

