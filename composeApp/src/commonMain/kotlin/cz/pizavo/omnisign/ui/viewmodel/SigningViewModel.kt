package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.LoadFileCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.UnlockTokenUseCase
import cz.pizavo.omnisign.ui.model.RenewalJobOfferState
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
 * [SignDocumentUseCase] → show the result.
 *
 * When the user checks "Add to renewal job" and the signing produces a B-LTA
 * document, a [RenewalJobOfferState] is populated in [pendingRenewalOffer] so
 * that the UI layer can show a follow-up dialog for renewal job assignment.
 *
 * @param signDocumentUseCase Use-case for performing the signing operation.
 * @param listCertificatesUseCase Use-case for discovering available signing certificates.
 * @param unlockTokenUseCase Use-case for unlocking a PIN-protected token on demand.
 * @param loadFileCertificatesUseCase Use-case for loading certificates from a PKCS#12 file.
 * @param configRepository Repository for reading the current application configuration.
 * @param renewalJobAssigner Shared helper for renewal job persistence and coverage checks.
 * @param ioDispatcher Dispatcher for heavy background work (certificate discovery, signing).
 */
class SigningViewModel(
	private val signDocumentUseCase: SignDocumentUseCase,
	private val listCertificatesUseCase: ListCertificatesUseCase,
	private val unlockTokenUseCase: UnlockTokenUseCase,
	private val loadFileCertificatesUseCase: LoadFileCertificatesUseCase,
	private val configRepository: ConfigRepository,
	private val renewalJobAssigner: RenewalJobAssigner? = null,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

	private val _state = MutableStateFlow<SigningDialogState>(SigningDialogState.Idle)

	/** Observable signing dialog state. */
	val state: StateFlow<SigningDialogState> = _state.asStateFlow()

	private val _pendingRenewalOffer = MutableStateFlow<RenewalJobOfferState?>(null)

	/**
	 * When non-null, the UI should show a renewal job assignment dialog for the
	 * successfully signed B-LTA document. Populated after a successful LTA signing
	 * when the user checked "Add to renewal job" in the form.
	 */
	val pendingRenewalOffer: StateFlow<RenewalJobOfferState?> = _pendingRenewalOffer.asStateFlow()

	private var currentFilePath: String? = null
	private var resolvedConfig: ResolvedConfig? = null
	private var lastReadyState: SigningDialogState.Ready? = null
	private var addToRenewalJobFlag: Boolean = false
	private var cachedRenewalJobs: List<RenewalJob> = emptyList()

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
				cachedRenewalJobs = appConfig.renewalJobs.values.toList()
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
								val level = config.signatureLevel
								val addSigTs = level == SignatureLevel.PADES_BASELINE_LT ||
										level == SignatureLevel.PADES_BASELINE_LTA
								val addArchTs = level == SignatureLevel.PADES_BASELINE_LTA

								val suggestedOutput = buildSuggestedOutputPath(filePath, "-signed")
								val coveringJob = RenewalJobAssigner.findCoveringJob(
									suggestedOutput, cachedRenewalJobs,
								)
								val ready = SigningDialogState.Ready(
									certificates = discovery.certificates,
									tokenWarnings = discovery.tokenWarnings,
									lockedTokens = discovery.lockedTokens,
									selectedAlias = null,
									hashAlgorithm = null,
									addSignatureTimestamp = addSigTs,
									addArchivalTimestamp = addArchTs,
									configHashAlgorithm = config.hashAlgorithm,
									configAddSignatureTimestamp = addSigTs,
									configAddArchivalTimestamp = addArchTs,
									disabledHashAlgorithms = config.disabledHashAlgorithms,
									outputPath = suggestedOutput,
									addToRenewalJob = coveringJob != null,
									coveringRenewalJobName = coveringJob?.name,
								)
								lastReadyState = ready
								_state.value = ready
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
	 * After applying the transform, renewal job coverage is recomputed for the
	 * (possibly changed) output path. When the output path is covered by an
	 * existing job, [SigningDialogState.Ready.addToRenewalJob] is forced to `true`.
	 *
	 * Has no effect when the state is not [SigningDialogState.Ready].
	 *
	 * @param transform Function that receives the current ready state and returns the updated one.
	 */
	fun updateState(transform: (SigningDialogState.Ready) -> SigningDialogState.Ready) {
		_state.update { current ->
			if (current is SigningDialogState.Ready) {
				val transformed = transform(current)
				val coveringJob = RenewalJobAssigner.findCoveringJob(
					transformed.outputPath, cachedRenewalJobs,
				)
				if (coveringJob != null) {
					transformed.copy(
						coveringRenewalJobName = coveringJob.name,
						addToRenewalJob = true,
					)
				} else {
					transformed.copy(coveringRenewalJobName = null)
				}
			} else {
				current
			}
		}
	}

	/**
	 * Unlock a PIN-protected token and merge its certificates into the Ready state.
	 *
	 * Prompts the user for the token PIN and, on success, appends discovered certificates
	 * to [SigningDialogState.Ready.certificates] and removes the token from
	 * [SigningDialogState.Ready.lockedTokens].
	 *
	 * On failure the token stays in [SigningDialogState.Ready.lockedTokens] so the user
	 * can retry. Any previous warning for the same token is replaced to avoid stacking
	 * duplicate messages.
	 *
	 * @param tokenId Stable identifier of the token to unlock.
	 */
	fun unlockToken(tokenId: String) {
		if (_state.value !is SigningDialogState.Ready) return
		viewModelScope.launch {
			withContext(ioDispatcher) {
				unlockTokenUseCase(tokenId).fold(
					ifLeft = { error ->
						_state.update { current ->
							if (current is SigningDialogState.Ready) {
								current.copy(
									tokenWarnings = current.tokenWarnings.filterNot { it.tokenId == tokenId } +
											cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning(
												tokenId = tokenId,
												tokenName = current.lockedTokens.find { it.tokenId == tokenId }?.tokenName ?: tokenId,
												message = error.message,
												details = error.details,
											),
								)
							} else current
						}
					},
					ifRight = { certs ->
						_state.update { current ->
							if (current is SigningDialogState.Ready) {
								val merged = current.certificates + certs
								current.copy(
									certificates = merged,
									lockedTokens = current.lockedTokens.filterNot { it.tokenId == tokenId },
									tokenWarnings = current.tokenWarnings.filterNot { it.tokenId == tokenId },
									selectedAlias = current.selectedAlias ?: merged.firstOrNull()?.alias,
								)
							} else current
						}
					},
				)
			}
		}
	}

	/**
	 * Load certificates from a PKCS#12 file and merge them into the Ready state.
	 *
	 * Prompts the user for the file password and, on success, appends the discovered
	 * certificates to [SigningDialogState.Ready.certificates].
	 *
	 * @param filePath Absolute path to the PKCS#12 (.p12 / .pfx) file.
	 */
	fun loadPkcs12File(filePath: String) {
		if (_state.value !is SigningDialogState.Ready) return
		viewModelScope.launch {
			withContext(ioDispatcher) {
				loadFileCertificatesUseCase(filePath).fold(
					ifLeft = { error ->
						_state.update { current ->
							if (current is SigningDialogState.Ready) {
								current.copy(
									tokenWarnings = current.tokenWarnings + cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning(
										tokenId = "file-$filePath",
										tokenName = filePath.substringAfterLast('/').substringAfterLast('\\'),
										message = error.message,
										details = error.details,
									),
								)
							} else current
						}
					},
					ifRight = { certs ->
						_state.update { current ->
							if (current is SigningDialogState.Ready) {
								val merged = current.certificates + certs
								current.copy(
									certificates = merged,
									selectedAlias = current.selectedAlias ?: merged.firstOrNull()?.alias,
								)
							} else current
						}
					},
				)
			}
		}
	}

	/**
	 * Execute the signing operation with the current form state.
	 *
	 * Transitions from [SigningDialogState.Ready] through [SigningDialogState.Signing]
	 * to either [SigningDialogState.Success], [SigningDialogState.RevocationWarning],
	 * or [SigningDialogState.Error].
	 *
	 * When the effective level is ≥ B-LT and the signing result contains revocation
	 * warnings, the dialog transitions to [SigningDialogState.RevocationWarning]
	 * instead of [SigningDialogState.Success] so the user can decide to abort or continue.
	 */
	fun sign() {
		val ready = _state.value as? SigningDialogState.Ready ?: return
		val inputFile = currentFilePath ?: return
		val config = resolvedConfig ?: return

		lastReadyState = ready
		addToRenewalJobFlag = ready.addToRenewalJob &&
				ready.addArchivalTimestamp &&
				ready.coveringRenewalJobName == null
		_state.value = SigningDialogState.Signing

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val parameters = SigningParameters(
					inputFile = inputFile,
					outputFile = ready.outputPath,
					certificateAlias = ready.selectedAlias,
					hashAlgorithm = ready.hashAlgorithm ?: config.hashAlgorithm,
					signatureLevel = ready.effectiveSignatureLevel,
					reason = ready.reason.ifBlank { null },
					location = ready.location.ifBlank { null },
					contactInfo = ready.contactInfo.ifBlank { null },
					addTimestamp = ready.effectiveAddTimestamp,
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
						val levelRequiresRevocation =
							ready.effectiveSignatureLevel >= SignatureLevel.PADES_BASELINE_LT

						if (result.hasRevocationWarnings && levelRequiresRevocation) {
							_state.value = SigningDialogState.RevocationWarning(
								warnings = result.annotatedWarnings,
								outputFile = result.outputFile,
								signatureId = result.signatureId,
								signatureLevel = result.signatureLevel,
							)
						} else {
							_state.value = SigningDialogState.Success(
								outputFile = result.outputFile,
								signatureId = result.signatureId,
								signatureLevel = result.signatureLevel,
								warnings = result.annotatedWarnings,
							)
							populateRenewalOfferIfNeeded(result.outputFile)
						}
					},
				)
			}
		}
	}

	/**
	 * Accept the revocation warning and transition to the success state.
	 *
	 * Called when the user clicks "Continue anyway" on the revocation warning screen.
	 */
	fun acceptRevocationWarning() {
		val rw = _state.value as? SigningDialogState.RevocationWarning ?: return
		_state.value = SigningDialogState.Success(
			outputFile = rw.outputFile,
			signatureId = rw.signatureId,
			signatureLevel = rw.signatureLevel,
			warnings = rw.warnings,
		)
		viewModelScope.launch {
			populateRenewalOfferIfNeeded(rw.outputFile)
		}
	}

	/**
	 * Abort after a revocation warning and return to the signing form.
	 *
	 * The signed output file is left in place for potential manual inspection.
	 * Called when the user clicks "Abort" on the revocation warning screen.
	 */
	fun abortAfterRevocationWarning() {
		_state.value = lastReadyState ?: SigningDialogState.Idle
	}

	/**
	 * Dismiss the signing dialog and reset the state to [SigningDialogState.Idle].
	 *
	 * The [pendingRenewalOffer] is intentionally retained so the UI can still
	 * display the renewal job assignment dialog after the signing dialog closes.
	 */
	fun dismiss() {
		_state.value = SigningDialogState.Idle
		resolvedConfig = null
		lastReadyState = null
	}

	/**
	 * Add the output file as a glob pattern to an existing renewal job.
	 *
	 * @param jobName Name of the existing job to assign the file to.
	 */
	fun assignToExistingJob(jobName: String) {
		val offer = _pendingRenewalOffer.value ?: return
		viewModelScope.launch {
			withContext(ioDispatcher) {
				val result = renewalJobAssigner?.assignToExistingJob(jobName, offer.outputFile)
				if (result != null) {
					_pendingRenewalOffer.value = offer.copy(assignedJobName = result, error = null)
				} else {
					_pendingRenewalOffer.value = offer.copy(error = "Job '$jobName' not found.")
				}
			}
		}
	}

	/**
	 * Create a new renewal job with the output file as its initial glob.
	 *
	 * @param job The new [RenewalJob] to create.
	 */
	fun createAndAssignJob(job: RenewalJob) {
		val offer = _pendingRenewalOffer.value ?: return
		viewModelScope.launch {
			withContext(ioDispatcher) {
				val result = renewalJobAssigner?.createNewJob(job)
				if (result != null) {
					result.fold(
						onSuccess = { name ->
							_pendingRenewalOffer.value = offer.copy(assignedJobName = name, error = null)
						},
						onFailure = { e ->
							_pendingRenewalOffer.value = offer.copy(error = e.message)
						},
					)
				}
			}
		}
	}

	/**
	 * Dismiss the renewal job offer dialog and clear the pending state.
	 */
	fun dismissRenewalOffer() {
		_pendingRenewalOffer.value = null
		addToRenewalJobFlag = false
	}

	/**
	 * Populate [_pendingRenewalOffer] when the signing produced a B-LTA document
	 * and the user opted in to renewal job assignment.
	 */
	private suspend fun populateRenewalOfferIfNeeded(outputFile: String) {
		if (!addToRenewalJobFlag || renewalJobAssigner == null) return
		val offer = renewalJobAssigner.buildOfferState(outputFile)
		_pendingRenewalOffer.value = offer
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

