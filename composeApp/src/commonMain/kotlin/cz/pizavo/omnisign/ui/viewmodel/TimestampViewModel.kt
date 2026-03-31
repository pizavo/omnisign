package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.GetDocumentTimestampInfoUseCase
import cz.pizavo.omnisign.ui.model.TimestampDialogState
import cz.pizavo.omnisign.ui.model.TimestampType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * → show the result.
 *
 * To avoid UI freezes when the dialog is opened, the heavyweight document
 * inspection ([GetDocumentTimestampInfoUseCase]) is pre-fetched in the background
 * as soon as a new document is loaded via [onDocumentChanged]. The [open] method
 * uses the cached result so it only needs to resolve configuration (a fast
 * in-memory operation) before transitioning to [TimestampDialogState.Ready].
 *
 * When the user selects [TimestampType.SIGNATURE_TIMESTAMP] (B-LT) and
 * revocation data cannot be obtained, the dialog offers a fallback to B-T —
 * unless the document already contains LT-level data, in which case only an
 * error is shown (level degradation is not permitted).
 *
 * @param extendDocumentUseCase Use case for extending a signed document.
 * @param getDocumentTimestampInfoUseCase Use case for inspecting the document's current timestamp state.
 * @param configRepository Repository for reading the current application configuration.
 * @param ioDispatcher Dispatcher for heavy background work.
 */
class TimestampViewModel(
	private val extendDocumentUseCase: ExtendDocumentUseCase,
	private val getDocumentTimestampInfoUseCase: GetDocumentTimestampInfoUseCase,
	private val configRepository: ConfigRepository,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

	private val _state = MutableStateFlow<TimestampDialogState>(TimestampDialogState.Idle)

	/** Observable timestamp dialog state. */
	val state: StateFlow<TimestampDialogState> = _state.asStateFlow()

	private var currentFilePath: String? = null
	private var resolvedConfig: ResolvedConfig? = null
	private var lastReadyState: TimestampDialogState.Ready? = null
	private var documentAlreadyContainsLtData: Boolean = false

	/** Pre-fetched timestamp info, populated by [onDocumentChanged]. */
	private var cachedTimestampInfo: DocumentTimestampInfo? = null

	/** In-flight pre-fetch coroutine, cancelled when the document changes. */
	private var prefetchJob: Job? = null

	/**
	 * Notify the ViewModel that a new PDF document has been loaded (or cleared).
	 *
	 * When [filePath] is non-null, a background pre-fetch of the document's
	 * timestamp state is triggered so that [open] can skip the heavyweight DSS
	 * inspection and transition to [TimestampDialogState.Ready] instantly.
	 *
	 * @param filePath Absolute path to the new document, or `null` when no document is open.
	 */
	fun onDocumentChanged(filePath: String?) {
		prefetchJob?.cancel()
		cachedTimestampInfo = null
		currentFilePath = filePath
		if (filePath != null) {
			prefetchJob = viewModelScope.launch {
				withContext(ioDispatcher) {
					val tsInfo = getDocumentTimestampInfoUseCase(filePath).getOrNull()
						?: DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = false)
					cachedTimestampInfo = tsInfo
				}
			}
		}
	}

	/**
	 * Open the timestamp dialog for the given document.
	 *
	 * Uses the pre-fetched [DocumentTimestampInfo] when available (populated by
	 * [onDocumentChanged]). If the pre-fetch is still running, the method awaits
	 * its completion without blocking the UI thread. Falls back to a fresh fetch
	 * only when no prior [onDocumentChanged] call was made for this file.
	 *
	 * Configuration is always resolved freshly because the user may have changed
	 * profiles or settings since the document was loaded.
	 *
	 * @param filePath Absolute path to the signed PDF document to extend.
	 */
	fun open(filePath: String) {
		currentFilePath = filePath

		viewModelScope.launch {
			prefetchJob?.join()

			val tsInfo = cachedTimestampInfo
				?: withContext(ioDispatcher) {
					getDocumentTimestampInfoUseCase(filePath).getOrNull()
						?: DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = false)
				}
			documentAlreadyContainsLtData = tsInfo.containsLtData

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
						val disabledTypes = if (tsInfo.hasDocumentTimestamp) {
							setOf(TimestampType.SIGNATURE_TIMESTAMP)
						} else {
							emptySet()
						}
						val ready = TimestampDialogState.Ready(
							timestampType = TimestampType.ARCHIVAL_TIMESTAMP,
							disabledTypes = disabledTypes,
							outputPath = suggestedOutput,
						)
						lastReadyState = ready
						_state.value = ready
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
			if (current is TimestampDialogState.Ready) {
				val updated = transform(current)
				lastReadyState = updated
				updated
			} else {
				current
			}
		}
	}

	/**
	 * Execute the extension operation with the current form state.
	 *
	 * Transitions from [TimestampDialogState.Ready] through [TimestampDialogState.Extending]
	 * to either [TimestampDialogState.Success], [TimestampDialogState.RevocationWarning],
	 * or [TimestampDialogState.Error].
	 *
	 * When extending to B-LT and the operation fails with a revocation error,
	 * the ViewModel checks whether a B-T fallback is possible (the document must not
	 * already contain LT-level data).
	 */
	fun extend() {
		val ready = _state.value as? TimestampDialogState.Ready ?: return
		val inputFile = currentFilePath ?: return
		val config = resolvedConfig ?: return

		_state.value = TimestampDialogState.Extending

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val targetLevel = ready.timestampType.targetLevel
				val parameters = ArchivingParameters(
					inputFile = inputFile,
					outputFile = ready.outputPath,
					targetLevel = targetLevel,
					resolvedConfig = config,
				)

				extendDocumentUseCase(parameters).fold(
					ifLeft = { error ->
						val isRevocationError = error is ArchivingError.RevocationInfoError
						val isLtExtension = targetLevel == SignatureLevel.PADES_BASELINE_LT

						if (isRevocationError && isLtExtension) {
							if (documentAlreadyContainsLtData) {
								_state.value = TimestampDialogState.Error(
									message = "Revocation data could not be refreshed",
									details = "The document already contains LT-level data. " +
											"Falling back to B-T is not possible because it " +
											"would degrade the existing signature level.\n\n" +
											(error.details ?: ""),
								)
							} else {
								_state.value = TimestampDialogState.RevocationWarning(
									warnings = listOfNotNull(
										error.message,
										error.details,
									),
									details = error.details,
								)
							}
						} else {
							_state.value = TimestampDialogState.Error(
								message = error.message,
								details = error.details,
							)
						}
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
	 * Accept the revocation warning and retry the extension at B-T level.
	 *
	 * Called when the user clicks "Continue anyway" on the revocation warning screen.
	 * The operation is re-executed with [SignatureLevel.PADES_BASELINE_T] instead of B-LT.
	 */
	fun acceptRevocationWarning() {
		if (_state.value !is TimestampDialogState.RevocationWarning) return
		val inputFile = currentFilePath ?: return
		val config = resolvedConfig ?: return
		val ready = lastReadyState ?: return

		_state.value = TimestampDialogState.Extending

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val parameters = ArchivingParameters(
					inputFile = inputFile,
					outputFile = ready.outputPath,
					targetLevel = SignatureLevel.PADES_BASELINE_T,
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
	 * Abort after a revocation warning and return to the extension form.
	 *
	 * Called when the user clicks "Abort" on the revocation warning screen.
	 */
	fun abortAfterRevocationWarning() {
		_state.value = lastReadyState ?: TimestampDialogState.Idle
	}

	/**
	 * Dismiss the dialog and reset the state to [TimestampDialogState.Idle].
	 *
	 * The pre-fetched [DocumentTimestampInfo] is retained because it is tied to
	 * the currently loaded document, not the dialog session.
	 */
	fun dismiss() {
		_state.value = TimestampDialogState.Idle
		resolvedConfig = null
		lastReadyState = null
		documentAlreadyContainsLtData = false
	}
}

