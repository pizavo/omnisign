package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.Pkcs11DiagnosticSnapshot
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.LoadFileCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.UnlockTokenUseCase
import cz.pizavo.omnisign.ui.model.RenewalJobOfferState
import cz.pizavo.omnisign.ui.model.SigningDialogState
import cz.pizavo.omnisign.ui.toast.ToastDuration
import cz.pizavo.omnisign.ui.toast.ToastMessage
import cz.pizavo.omnisign.ui.toast.ToastService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update

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
 * While the dialog is in [SigningDialogState.Ready], the ViewModel observes
 * [TokenService.discoveryRunning] and reacts to background PKCS#11 cycles triggered by PC/SC
 * reader-state events (card inserted / removed, reader plugged / unplugged).  The flag is
 * mirrored into [SigningDialogState.Ready.refreshing] so the UI can show a small inline
 * indicator, and every transition back to `false` triggers a silent (non-prompting)
 * re-fetch of the certificate list with selection preservation: the current
 * [SigningDialogState.Ready.selectedAlias] is kept when the alias is still present in the
 * refreshed list, otherwise it is cleared.
 *
 * @param signDocumentUseCase Use-case for performing the signing operation.
 * @param listCertificatesUseCase Use-case for discovering available signing certificates.
 * @param unlockTokenUseCase Use-case for unlocking a PIN-protected token on demand.
 * @param loadFileCertificatesUseCase Use-case for loading certificates from a PKCS#12 file.
 * @param configRepository Repository for reading the current application configuration.
 * @param tokenService Source of [TokenService.discoveryRunning] for background-discovery
 *   observability that drives the inline indicator and the auto-refresh of the certificate list.
 * @param renewalJobAssigner Shared helper for renewal job persistence and coverage checks.
 * @param toastService Application-wide toast dispatcher.  When non-null, [rescan] emits a
 *   user-visible acknowledgement toast through it (with a "Show diagnostic info" action
 *   when no PKCS#11 entries were detected).  `null` in tests / web / any context that
 *   doesn't wire the centralised toast surface — emission is silently skipped.
 * @param ioDispatcher Dispatcher for heavy background work (certificate discovery, signing).
 */
class SigningViewModel(
	private val signDocumentUseCase: SignDocumentUseCase,
	private val listCertificatesUseCase: ListCertificatesUseCase,
	private val unlockTokenUseCase: UnlockTokenUseCase,
	private val loadFileCertificatesUseCase: LoadFileCertificatesUseCase,
	private val configRepository: ConfigRepository,
	private val tokenService: TokenService,
	private val renewalJobAssigner: RenewalJobAssigner? = null,
	private val toastService: ToastService? = null,
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

	private val _diagnosticSnapshot = MutableStateFlow<Pkcs11DiagnosticSnapshot?>(null)

	/**
	 * When non-null, the UI should show a diagnostic-info dialog populated from this snapshot.
	 *
	 * Populated by [showDiagnostic] in response to the "Show diagnostic info" affordance in
	 * the sign-dialog empty-state banner.  Cleared by [dismissDiagnostic] when the user closes
	 * the diagnostic dialog.  Independent of [state] — the user can dismiss the diagnostic
	 * dialog without affecting the sign-dialog flow.
	 */
	val diagnosticSnapshot: StateFlow<Pkcs11DiagnosticSnapshot?> = _diagnosticSnapshot.asStateFlow()

	private var currentFilePath: String? = null
	private var resolvedConfig: ResolvedConfig? = null
	private var lastReadyState: SigningDialogState.Ready? = null
	private var addToRenewalJobFlag: Boolean = false
	private var cachedRenewalJobs: List<RenewalJob> = emptyList()

	/**
	 * Last user-selected certificate alias from a previous dialog session.
	 *
	 * When [open] runs, this is restored as the initial [SigningDialogState.Ready.selectedAlias]
	 * if the alias still appears in the freshly fetched certificate list — saving the user a
	 * click in the common case of re-signing with the same certificate they used last time.
	 * Cleared via [updateState] writes (so manual deselection sticks) and re-set every time
	 * the user picks an alias before the dialog dismisses.
	 */
	private var lastSelectedAlias: String? = null

	/**
	 * `true` while a user-initiated [rescan] is pending its follow-up cert-list refresh.
	 *
	 * The auto-refresh collector consumes this flag on the next `discoveryRunning` → `false`
	 * transition: when set, [emitRescanToast] fires a user-visible acknowledgement once the
	 * refreshed list settles.  Event-driven refreshes (PC/SC card insert / remove) leave the
	 * flag clear so background reader-state changes never surface a "rescan complete" toast.
	 *
	 * It does not gate PIN prompting: neither [open] nor [rescan] prompts for locked tokens.
	 * The per-token lock affordance ([unlockToken]) is the sole interactive PIN entry point.
	 */
	private var pendingUserRescan: Boolean = false

	init {
		viewModelScope.launch {
			tokenService.discoveryRunning.drop(1).collect { running ->
				_state.update { current ->
					if (current is SigningDialogState.Ready) current.copy(refreshing = running)
					else current
				}
				if (!running) {
					val userRescan = pendingUserRescan
					pendingUserRescan = false
					refreshCertificatesIfReady()
					if (userRescan) {
						emitRescanToast()
					}
				}
			}
		}
	}

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
				coroutineScope {
					val appConfigDeferred = async { configRepository.getCurrentConfig() }
					val discoveryDeferred = async { listCertificatesUseCase(promptForLocked = false) }

					val appConfig = appConfigDeferred.await()
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
							discoveryDeferred.await().fold(
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
									val restoredAlias = lastSelectedAlias
										?.takeIf { alias -> discovery.certificates.any { it.alias == alias } }
									val ready = SigningDialogState.Ready(
										certificates = discovery.certificates,
										tokenWarnings = discovery.tokenWarnings,
										lockedTokens = discovery.lockedTokens,
										selectedAlias = restoredAlias,
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
				lastSelectedAlias = transformed.selectedAlias
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
									selectedAlias = certs.firstOrNull()?.alias ?: current.selectedAlias,
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
				result?.fold(
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

	/**
	 * Dismiss the renewal job offer dialog and clear the pending state.
	 */
	fun dismissRenewalOffer() {
		_pendingRenewalOffer.value = null
		addToRenewalJobFlag = false
	}

	/**
	 * Trigger a manual rescan of all discoverable tokens.
	 *
	 * Fire-and-forget: returns immediately while the rescan runs in the background.  Progress
	 * is reflected through [TokenService.discoveryRunning] → the dialog's inline indicator
	 * swaps in for the rescan button, and the certificate list auto-refreshes when the cycle
	 * settles.  Intended for the "Rescan tokens" affordance in the dialog header — the
	 * single use case where the user needs to force a refresh that no PC/SC event would
	 * otherwise trigger (typically a runtime PKCS#11 middleware install).
	 *
	 * The refresh is silent: a newly-detected PIN-required token surfaces as a locked entry,
	 * not a PIN prompt.  Sets [pendingUserRescan] only so the auto-refresh collector emits a
	 * user-visible acknowledgement toast ([emitRescanToast]) once the cycle settles,
	 * distinguishing this explicit action from background PC/SC refreshes (which stay
	 * silent).  Unlocking a discovered token remains an explicit, per-token user action via
	 * the lock affordance ([unlockToken]).
	 *
	 * Safe to call from any [SigningDialogState] (no-op effect outside of [SigningDialogState.Ready]
	 * because the button isn't rendered there), but only fires the actual rescan when a
	 * platform implementation backs it; non-JVM TokenServices treat this as a no-op.
	 */
	fun rescan() {
		pendingUserRescan = true
		tokenService.rescanTokens()
	}

	/**
	 * Load the diagnostic snapshot and populate [diagnosticSnapshot] so the UI can show
	 * the diagnostic-info dialog.
	 *
	 * Lightweight — does not run subprocess probes (those happen via `omnisign diagnose
	 * pkcs11`).  Just reports current PC/SC reader state and the candidate library list
	 * discovery would consider right now.  Safe to call multiple times; each invocation
	 * refreshes the snapshot.
	 */
	fun showDiagnostic() {
		viewModelScope.launch {
			withContext(ioDispatcher) {
				val snapshot = runCatching { tokenService.getDiagnosticSnapshot() }
					.getOrDefault(Pkcs11DiagnosticSnapshot.EMPTY)
				_diagnosticSnapshot.value = snapshot
			}
		}
	}

	/**
	 * Dismiss the diagnostic-info dialog by clearing [diagnosticSnapshot].
	 */
	fun dismissDiagnostic() {
		_diagnosticSnapshot.value = null
	}

	/**
	 * Compose a user-facing acknowledgement [ToastMessage] from the *current*
	 * [SigningDialogState.Ready] state and hand it to the application-wide [ToastService]
	 * so it appears at the bottom-right of whichever window is currently visible (root
	 * layout or any open dialog).  Called from the auto-refresh collector after a
	 * user-initiated rescan settles.
	 *
	 * Two shapes:
	 *  - **No PKCS#11 entries detected** (count zero) — long-duration toast with a
	 *    "Show diagnostic info" action that opens the diagnostic dialog via
	 *    [showDiagnostic].  This is the common SafeNet/Calais-mismatch case where the
	 *    rescan ran but the cert list is unchanged, so without the toast the dialog
	 *    appears inert.
	 *  - **One or more entries detected** — short-duration informational toast, no action.
	 *
	 * Silently no-ops when [toastService] is `null` (tests / web fallback / any context
	 * that doesn't wire the centralised toast surface).  When the dialog isn't in
	 * [SigningDialogState.Ready] (user dismissed mid-rescan, refresh transitioned to
	 * Error, etc.) we publish zeros so the user still gets a "rescan ran" signal at the
	 * root host instead of total silence.
	 */
	private fun emitRescanToast() {
		val service = toastService ?: return
		val ready = _state.value as? SigningDialogState.Ready
		val pkcs11CertCount = ready?.certificates
			?.count { it.tokenType == TokenType.PKCS11.name } ?: 0
		val lockedTokenCount = ready?.lockedTokens?.size ?: 0
		val total = pkcs11CertCount + lockedTokenCount
		val text = when {
			total == 0 -> "Rescan complete — no PKCS#11 tokens detected"
			total == 1 -> "Rescan complete — 1 PKCS#11 entry detected"
			else -> "Rescan complete — $total PKCS#11 entries detected"
		}
		val message = if (total == 0) ToastMessage(
			text = text,
			actionLabel = "Show diagnostic info",
			onAction = ::showDiagnostic,
			duration = ToastDuration.Long,
		) else ToastMessage(text = text, duration = ToastDuration.Short)
		service.show(message)
	}

	/**
	 * Re-fetch the certificate list and merge it into the current [SigningDialogState.Ready]
	 * state, preserving [SigningDialogState.Ready.selectedAlias] when the same alias is still
	 * present in the refreshed list (otherwise clearing it).
	 *
	 * Called from the [tokenService] `discoveryRunning` collector on every transition back to
	 * `false`, which corresponds to a PKCS#11 rediscovery cycle finishing (startup warmup,
	 * invalidator-launched rediscovery after a PC/SC reader-state event, or a user-triggered
	 * [rescan]).
	 *
	 * Always silent (`promptForLocked = false`): a PIN-required token surfaces as a locked
	 * entry in [SigningDialogState.Ready.lockedTokens], never an unsolicited PIN dialog —
	 * regardless of whether this refresh was triggered by a background event or a manual
	 * [rescan].  Unlocking a token is an explicit, per-token user action via [unlockToken],
	 * the sole interactive PIN entry point in the dialog.
	 *
	 * Skipped silently when the dialog is not in [SigningDialogState.Ready] (Signing,
	 * Success, RevocationWarning, Error, Idle) — a background refresh has nothing to update
	 * while the user is mid-signing or seeing a result.  Also skipped on use-case failure;
	 * the existing state is retained.
	 */
	private suspend fun refreshCertificatesIfReady() {
		if (_state.value !is SigningDialogState.Ready) return
		withContext(ioDispatcher) {
			listCertificatesUseCase(promptForLocked = false).fold(
				ifLeft = { },
				ifRight = { discovery ->
					_state.update { current ->
						if (current is SigningDialogState.Ready) {
							val refreshedAliases = discovery.certificates.map { it.alias }.toSet()
							current.copy(
								certificates = discovery.certificates,
								tokenWarnings = discovery.tokenWarnings,
								lockedTokens = discovery.lockedTokens,
								selectedAlias = current.selectedAlias?.takeIf { it in refreshedAliases },
							)
						} else current
					}
				},
			)
		}
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

