package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.ui.model.SigningDialogState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel driving the signing dialog.
 *
 * Orchestrates certificate discovery, form state, and the actual signing operation.
 * The dialog flow mirrors the CLI [cz.pizavo.omnisign.commands.Sign] command:
 * resolve config → discover certificates → build [SigningParameters] → invoke
 * [SignDocumentUseCase] → show result.
 *
 * @param signDocumentUseCase Use case for performing the signing operation.
 * @param listCertificatesUseCase Use case for discovering available signing certificates.
 * @param configRepository Repository for reading the current application configuration.
 * @param ioDispatcher Dispatcher for heavy background work (certificate discovery, signing).
 */
class SigningViewModel(
	private val signDocumentUseCase: SignDocumentUseCase,
	private val listCertificatesUseCase: ListCertificatesUseCase,
	private val configRepository: ConfigRepository,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

	private val _state = MutableStateFlow<SigningDialogState>(SigningDialogState.Idle)

	/** Observable signing dialog state. */
	val state: StateFlow<SigningDialogState> = _state.asStateFlow()

	private var currentFilePath: String? = null
	private var resolvedConfig: ResolvedConfig? = null

	/**
	 * Open the signing dialog for the given document.
	 *
	 * Triggers certificate discovery and config resolution in the background.
	 * The dialog transitions from [SigningDialogState.Loading] to
	 * [SigningDialogState.Ready] on success, or to [SigningDialogState.Error]
	 * on failure.
	 *
	 * @param filePath Absolute path to the PDF document to sign.
	 */
	fun open(filePath: String) {
		currentFilePath = filePath
		_state.value = SigningDialogState.Loading

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
						_state.value = SigningDialogState.Error(
							message = "Configuration error: ${error.message}",
						)
					},
					ifRight = { config ->
						resolvedConfig = config
						listCertificatesUseCase().fold(
							ifLeft = { error ->
								_state.value = SigningDialogState.Error(
									message = error.message,
									details = error.details,
								)
							},
							ifRight = { discovery ->
								val suggestedOutput = buildSuggestedOutputPath(filePath, "-signed")
								_state.value = SigningDialogState.Ready(
									certificates = discovery.certificates,
									tokenWarnings = discovery.tokenWarnings,
									hashAlgorithm = null,
									signatureLevel = null,
									configHashAlgorithm = config.hashAlgorithm,
									configSignatureLevel = config.signatureLevel,
									disabledHashAlgorithms = config.disabledHashAlgorithms,
									outputPath = suggestedOutput,
								)
							},
						)
					},
				)
			}
		}
	}

	/**
	 * Apply a field-level transformation to the current [SigningDialogState.Ready] state.
	 *
	 * Has no effect when the state is not [SigningDialogState.Ready].
	 *
	 * @param transform Function that receives the current ready state and returns the updated one.
	 */
	fun updateState(transform: (SigningDialogState.Ready) -> SigningDialogState.Ready) {
		_state.update { current ->
			if (current is SigningDialogState.Ready) transform(current) else current
		}
	}

	/**
	 * Execute the signing operation with the current form state.
	 *
	 * Transitions from [SigningDialogState.Ready] through [SigningDialogState.Signing]
	 * to either [SigningDialogState.Success] or [SigningDialogState.Error].
	 */
	fun sign() {
		val ready = _state.value as? SigningDialogState.Ready ?: return
		val inputFile = currentFilePath ?: return
		val config = resolvedConfig ?: return

		_state.value = SigningDialogState.Signing

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val parameters = SigningParameters(
					inputFile = inputFile,
					outputFile = ready.outputPath,
					certificateAlias = ready.selectedAlias,
					hashAlgorithm = ready.hashAlgorithm ?: config.hashAlgorithm,
					signatureLevel = ready.signatureLevel ?: config.signatureLevel,
					reason = ready.reason.ifBlank { null },
					location = ready.location.ifBlank { null },
					contactInfo = ready.contactInfo.ifBlank { null },
					addTimestamp = ready.addTimestamp,
					resolvedConfig = config,
				)

				signDocumentUseCase(parameters).fold(
					ifLeft = { error ->
						_state.value = SigningDialogState.Error(
							message = error.message,
							details = error.details,
						)
					},
					ifRight = { result ->
						_state.value = SigningDialogState.Success(
							outputFile = result.outputFile,
							signatureId = result.signatureId,
							signatureLevel = result.signatureLevel,
							warnings = result.warnings,
						)
					},
				)
			}
		}
	}

	/**
	 * Dismiss the dialog and reset the state to [SigningDialogState.Idle].
	 */
	fun dismiss() {
		_state.value = SigningDialogState.Idle
		resolvedConfig = null
	}

	companion object {
		/**
		 * Build a suggested output file path by inserting a suffix before the file extension.
		 *
		 * @param inputPath Original file path.
		 * @param suffix Suffix to append (e.g. "-signed").
		 * @return Suggested output path.
		 */
		internal fun buildSuggestedOutputPath(inputPath: String, suffix: String): String {
			val lastDot = inputPath.lastIndexOf('.')
			return if (lastDot > 0) {
				"${inputPath.substring(0, lastDot)}$suffix${inputPath.substring(lastDot)}"
			} else {
				"$inputPath$suffix"
			}
		}
	}
}

