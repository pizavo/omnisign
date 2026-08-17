package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.error.localizableText
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.GetDocumentTimestampInfoUseCase
import cz.pizavo.omnisign.ui.model.ErrorMessage
import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
import cz.pizavo.omnisign.ui.model.RenewalJobOfferState
import cz.pizavo.omnisign.ui.model.RenewalOfferError
import cz.pizavo.omnisign.ui.model.TimestampDialogState
import cz.pizavo.omnisign.ui.model.TimestampType
import cz.pizavo.omnisign.ui.platform.SaveOutcome
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * When the user checks "Add to renewal job" and the extension produces a B-LTA
 * document, a [RenewalJobOfferState] is populated in [pendingRenewalOffer] so
 * that the UI layer can show a follow-up dialog for renewal job assignment.
 *
 * @param extendDocumentUseCase Use case for extending a signed document.
 * @param getDocumentTimestampInfoUseCase Use case for inspecting the document's current timestamp state.
 * @param configRepository Repository for reading the current application configuration.
 * @param renewalJobAssigner Shared helper for renewal job persistence and coverage checks.
 * @param ioDispatcher Dispatcher for heavy background work.
 */
class TimestampViewModel(
	private val extendDocumentUseCase: ExtendDocumentUseCase,
	private val getDocumentTimestampInfoUseCase: GetDocumentTimestampInfoUseCase,
	private val configRepository: ConfigRepository,
	private val renewalJobAssigner: RenewalJobAssigner? = null,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

	private val _state = MutableStateFlow<TimestampDialogState>(TimestampDialogState.Idle)

	/** Observable timestamp dialog state. */
	val state: StateFlow<TimestampDialogState> = _state.asStateFlow()

	private val _pendingRenewalOffer = MutableStateFlow<RenewalJobOfferState?>(null)

	/**
	 * When non-null, the UI should show a renewal job assignment dialog for the
	 * successfully extended B-LTA document. Populated after a successful LTA extension
	 * when the user checked "Add to renewal job" in the form.
	 */
	val pendingRenewalOffer: StateFlow<RenewalJobOfferState?> = _pendingRenewalOffer.asStateFlow()

	private var currentDocument: PdfDocumentInfo? = null
	private var resolvedConfig: ResolvedConfig? = null
	private var activeProfileName: String? = null
	private var lastReadyState: TimestampDialogState.Ready? = null
	private var documentAlreadyContainsLtData: Boolean = false
	private var addToRenewalJobFlag: Boolean = false
	private var cachedRenewalJobs: List<RenewalJob> = emptyList()

	/**
	 * The extended bytes and metadata produced by [extend] / [acceptRevocationWarning], held until
	 * the user picks a save location in [saveExtendedDocument]. The save dialog is the last step, so
	 * aborting or cancelling ([abortAfterRevocationWarning] / [cancelSave]) writes nothing.
	 */
	private var pendingExtension: PendingExtension? = null

	/**
	 * The extended document rebuilt from the produced bytes after a successful save, exposed for the
	 * web viewer-reload path where `loadPdfFromPath` returns `null` (no browser filesystem). Reuses
	 * the source [PdfDocumentInfo.pageCount]. `null` until a save completes; cleared on [open] /
	 * [dismiss].
	 */
	var extendedDocument: PdfDocumentInfo? = null
		private set

	/**
	 * The produced extended bytes awaiting a save destination, or `null` when no save is pending. Read
	 * by the UI to drive the platform save ([cz.pizavo.omnisign.ui.platform.saveDocument]) once the
	 * dialog reaches [TimestampDialogState.AwaitingSave].
	 */
	val pendingOutputBytes: ByteArray? get() = pendingExtension?.outputBytes

	/** Pre-fetched timestamp info, populated by [onDocumentChanged]. */
	private var cachedTimestampInfo: DocumentTimestampInfo? = null

	/** In-flight pre-fetch coroutine, cancelled when the document changes. */
	private var prefetchJob: Job? = null

	/**
	 * Notify the ViewModel that a new PDF document has been loaded (or cleared).
	 *
	 * When [document] is non-null, a background pre-fetch of the document's
	 * timestamp state is triggered so that [open] can skip the heavyweight DSS
	 * inspection and transition to [TimestampDialogState.Ready] instantly.
	 *
	 * @param document The newly loaded document, or `null` when no document is open.
	 */
	fun onDocumentChanged(document: PdfDocumentInfo?) {
		prefetchJob?.cancel()
		cachedTimestampInfo = null
		currentDocument = document
		if (document != null) {
			prefetchJob = viewModelScope.launch {
				withContext(ioDispatcher) {
					val tsInfo = getDocumentTimestampInfoUseCase(document.data).getOrNull()
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
	 * @param document The signed PDF document to extend; its bytes are forwarded to the
	 *   extension use case and its `filePath` (when present) seeds the suggested output path
	 *   on the desktop. On the web target [PdfDocumentInfo.filePath] is `null`; the dialog
	 *   falls back to deriving the suggested name from [PdfDocumentInfo.name].
	 */
	fun open(document: PdfDocumentInfo) {
		currentDocument = document
		extendedDocument = null
		pendingExtension = null

		viewModelScope.launch {
			prefetchJob?.join()

			val tsInfo = cachedTimestampInfo
				?: withContext(ioDispatcher) {
					getDocumentTimestampInfoUseCase(document.data).getOrNull()
						?: DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = false)
				}
			documentAlreadyContainsLtData = tsInfo.containsLtData

			withContext(ioDispatcher) {
				val appConfig = configRepository.getCurrentConfig()
				cachedRenewalJobs = appConfig.renewalJobs.values.toList()
				val activeProfile = appConfig.activeProfile
				activeProfileName = activeProfile
				val profileConfig = activeProfile?.let { appConfig.profiles[it] }

				val configResult = ResolvedConfig.resolve(
					global = appConfig.global,
					profile = profileConfig,
					operationOverrides = null,
				)

				configResult.fold(
					ifLeft = { error ->
						_state.value = TimestampDialogState.Error(
							content = ErrorMessage.ConfigResolution(error.localizableText()),
						)
					},
					ifRight = { config ->
						resolvedConfig = config
						val sourceName = document.filePath ?: document.name
						val currentLevel = currentLevelOf(tsInfo)
						val unavailableTypes = if (tsInfo.hasDocumentTimestamp) {
							setOf(TimestampType.SIGNATURE_TIMESTAMP)
						} else {
							emptySet()
						}
						val ready = TimestampDialogState.Ready(
							timestampType = defaultTypeFor(currentLevel),
							currentLevel = currentLevel,
							unavailableTypes = unavailableTypes,
							ltMaterialUsable = tsInfo.ltMaterialUsable,
							suggestedName = SigningViewModel.suggestedSaveName(sourceName, "-extended"),
							inputDirectory = document.filePath?.let { SigningViewModel.parentDirectory(it) },
						)
						lastReadyState = ready
						_state.value = ready
					},
				)
			}
		}
	}

	/**
	 * Derive the document's current PAdES level from its inspected timestamp state.
	 *
	 * Prefers [DocumentTimestampInfo.level], the level DSS determined from the document itself, so
	 * the dialog agrees with the validation report.
	 *
	 * The remaining branches apply only when no level could be established at all, and read the
	 * document's structure instead. They are a labelling fallback, not a conformance claim: a
	 * document timestamp maps to B-LTA because that is the level whose options the dialog must then
	 * offer — [TimestampDialogState.Ready.unavailableTypes] withdraws the B-LT option for the same
	 * structural reason, and the two have to agree or the dialog would default to an option it has
	 * disabled. There is no `containsLtData` branch, because that flag is itself derived from the
	 * level and so can never be `true` while the level is `null`.
	 *
	 * @param info The inspected timestamp state of the document.
	 * @return The PAdES level the document is currently at.
	 */
	private fun currentLevelOf(info: DocumentTimestampInfo): SignatureLevel = info.level ?: when {
		info.hasDocumentTimestamp -> SignatureLevel.PADES_BASELINE_LTA
		info.hasSignatureTimestamp -> SignatureLevel.PADES_BASELINE_T
		else -> SignatureLevel.PADES_BASELINE_B
	}

	/**
	 * The extension option pre-selected when the dialog opens for a document at [currentLevel].
	 *
	 * A B-T document most likely needs only revocation data added, so the B-LT option
	 * ([TimestampType.SIGNATURE_TIMESTAMP]) is the default there; every other level defaults to the
	 * archival option — the natural next step, and the only enabled one at B-LTA.
	 *
	 * Deliberately *not* influenced by [DocumentTimestampInfo.ltMaterialUsable]. Unusable validation
	 * material means the data was issued after the signing certificate expired — and since a
	 * response can only ever be issued before now, that condition is reachable only once the
	 * certificate is already expired. Steering such a document towards the B-LT option would
	 * recommend fetching replacement data that no longer exists. The dialog states the problem
	 * instead and leaves the choice alone.
	 *
	 * @param currentLevel The document's current PAdES level.
	 * @return The timestamp type to pre-select.
	 */
	private fun defaultTypeFor(currentLevel: SignatureLevel): TimestampType =
		if (currentLevel == SignatureLevel.PADES_BASELINE_T) {
			TimestampType.SIGNATURE_TIMESTAMP
		} else {
			TimestampType.ARCHIVAL_TIMESTAMP
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
	 * Run the extension operation, producing the extended bytes **in memory**.
	 *
	 * Transitions from [TimestampDialogState.Ready] through [TimestampDialogState.Extending] to
	 * either [TimestampDialogState.AwaitingSave] (success), [TimestampDialogState.RevocationWarning]
	 * (a B-LT extension that failed to obtain revocation data, when the document has no LT data yet),
	 * or [TimestampDialogState.Error]. **No file is written here** — the produced bytes are held in
	 * [pendingExtension] and persisted only once the user picks a destination in
	 * [saveExtendedDocument], so the save dialog is the last step and aborting writes nothing.
	 */
	fun extend() {
		val ready = _state.value as? TimestampDialogState.Ready ?: return
		val document = currentDocument ?: return
		val config = resolvedConfig ?: return

		_state.value = TimestampDialogState.Extending

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val targetLevel = ready.timestampType.targetLevel
				val parameters = ArchivingParameters(
					inputBytes = document.data,
					inputName = document.name,
					targetLevel = targetLevel,
					resolvedConfig = config,
					profileName = activeProfileName,
				)

				extendDocumentUseCase(parameters).fold(
					ifLeft = { error ->
						val isRevocationError = error is ArchivingError.RevocationInfoError
						val isLtExtension = targetLevel == SignatureLevel.PADES_BASELINE_LT

						if (isRevocationError && isLtExtension) {
							if (documentAlreadyContainsLtData) {
								_state.value = TimestampDialogState.Error(
									content = ErrorMessage.RevocationRefreshFailed(error.details),
								)
							} else {
								_state.value = TimestampDialogState.RevocationWarning(
									warnings = listOfNotNull(
										error.localizableText(),
										error.details?.let { LocalizableText.Literal(it) },
									),
									details = error.details,
								)
							}
						} else {
							_state.value = TimestampDialogState.Error(
								content = ErrorMessage.Domain(error.localizableText(), error.details),
							)
						}
					},
					ifRight = { result ->
						holdExtension(
							result = result,
							addToRenewalJob = ready.addToRenewalJob,
							isArchival = ready.timestampType == TimestampType.ARCHIVAL_TIMESTAMP,
						)
						_state.value = when {
							result.revocationDataMissing -> TimestampDialogState.RevocationWarning(
								warnings = result.annotatedWarnings.map { it.summary },
								outputHeld = true,
							)

							result.revocationNotRefreshed -> TimestampDialogState.RevocationNotRefreshed(
								warnings = result.annotatedWarnings.map { it.summary },
							)

							else -> awaitingSaveState()
						}
					},
				)
			}
		}
	}

	/**
	 * Accept the revocation warning and continue, which means one of two things depending on how the
	 * warning was reached (see [TimestampDialogState.RevocationWarning]).
	 *
	 * When extended bytes are already held
	 * ([TimestampDialogState.RevocationWarning.outputHeld]), the extension produced a document below
	 * its target level and continuing keeps it: the dialog advances straight to
	 * [TimestampDialogState.AwaitingSave] with those bytes, running nothing again.
	 *
	 * Otherwise the B-LT attempt produced no output at all, so continuing re-runs the extension at
	 * [SignatureLevel.PADES_BASELINE_T]. On success the bytes are held and the dialog advances to
	 * [TimestampDialogState.AwaitingSave] to pick a save location.
	 *
	 * [TimestampDialogState.RevocationNotRefreshed] is the third caller and behaves like the held-bytes
	 * case: the extension succeeded and only failed to improve on what was already embedded, so
	 * continuing keeps the output and nothing is re-run.
	 *
	 * Either way nothing is written until the user picks a destination. Called when the user clicks
	 * "Continue anyway" on the revocation warning.
	 */
	fun acceptRevocationWarning() {
		if (_state.value is TimestampDialogState.RevocationNotRefreshed) {
			_state.value = awaitingSaveState()
			return
		}
		val warning = _state.value as? TimestampDialogState.RevocationWarning ?: return
		if (warning.outputHeld) {
			_state.value = awaitingSaveState()
			return
		}
		val document = currentDocument ?: return
		val config = resolvedConfig ?: return

		_state.value = TimestampDialogState.Extending

		viewModelScope.launch {
			withContext(ioDispatcher) {
				val parameters = ArchivingParameters(
					inputBytes = document.data,
					inputName = document.name,
					targetLevel = SignatureLevel.PADES_BASELINE_T,
					resolvedConfig = config,
					profileName = activeProfileName,
				)

				extendDocumentUseCase(parameters).fold(
					ifLeft = { error ->
						_state.value = TimestampDialogState.Error(
							content = ErrorMessage.Domain(error.localizableText(), error.details),
						)
					},
					ifRight = { result ->
						holdAndAwaitSave(result = result, addToRenewalJob = false, isArchival = false)
					},
				)
			}
		}
	}

	/**
	 * Hold [result]'s extended bytes in [pendingExtension] and advance to
	 * [TimestampDialogState.AwaitingSave] so the UI can prompt for a save location. Used by the paths
	 * that produce output the user should save without further questions.
	 *
	 * @param addToRenewalJob Whether the user opted the output into a renewal job.
	 * @param isArchival Whether this is an archival (B-LTA) extension — a renewal offer only applies
	 *   there, so the B-T fallback passes `false`.
	 */
	private fun holdAndAwaitSave(result: ArchivingResult, addToRenewalJob: Boolean, isArchival: Boolean) {
		holdExtension(result, addToRenewalJob, isArchival)
		_state.value = awaitingSaveState()
	}

	/**
	 * Park [result]'s extended bytes in [pendingExtension] without deciding what the dialog shows
	 * next, so a caller can interpose a confirmation step before the save prompt.
	 *
	 * @param addToRenewalJob Whether the user opted the output into a renewal job.
	 * @param isArchival Whether this is an archival (B-LTA) extension.
	 */
	private fun holdExtension(result: ArchivingResult, addToRenewalJob: Boolean, isArchival: Boolean) {
		pendingExtension = PendingExtension(
			outputBytes = result.outputBytes,
			newLevel = result.newSignatureLevel,
			annotatedWarnings = result.annotatedWarnings,
			addToRenewalJob = addToRenewalJob,
			isArchival = isArchival,
			pageCount = currentDocument?.pageCount ?: 1,
		)
	}

	/**
	 * The [TimestampDialogState.AwaitingSave] state for the currently held extension, seeded with the
	 * suggested name and directory captured while the form was open.
	 */
	private fun awaitingSaveState(): TimestampDialogState.AwaitingSave {
		val ready = lastReadyState
		return TimestampDialogState.AwaitingSave(
			suggestedName = ready?.suggestedName ?: "",
			inputDirectory = ready?.inputDirectory,
		)
	}

	/**
	 * Finish the extension flow from the [outcome] of the platform save
	 * ([cz.pizavo.omnisign.ui.platform.saveDocument]) the UI ran with the held bytes. No-op when there
	 * is no [pendingExtension].
	 *
	 * - [SaveOutcome.Saved] — advance to [TimestampDialogState.Success] and rebuild [extendedDocument]
	 *   so the viewer can reopen the saved document; renewal-job coverage is resolved against the path.
	 * - [SaveOutcome.SavedNameUnknown] — the web download fallback: the file is saved but its final
	 *   name is unknown, so [extendedDocument] stays `null` (the viewer keeps the current document) and
	 *   the UI surfaces a "not reopened" notice — still a success.
	 * - [SaveOutcome.Cancelled] — discard the held bytes and return to the form; nothing was written.
	 * - [SaveOutcome.Failed] — surface a write error.
	 */
	fun completeSave(outcome: SaveOutcome) {
		val pending = pendingExtension ?: return
		when (outcome) {
			is SaveOutcome.Cancelled -> {
				pendingExtension = null
				_state.value = lastReadyState ?: TimestampDialogState.Idle
			}

			is SaveOutcome.Failed -> _state.value = TimestampDialogState.Error(
				content = ErrorMessage.WriteFailed(signed = false, reason = outcome.reason),
			)

			is SaveOutcome.Saved -> {
				val coveringJob = RenewalJobAssigner.findCoveringJob(outcome.path, cachedRenewalJobs)
				addToRenewalJobFlag = pending.addToRenewalJob && coveringJob == null
				extendedDocument = PdfDocumentInfo(
					name = outcome.path.substringAfterLast('/').substringAfterLast('\\'),
					data = pending.outputBytes,
					pageCount = pending.pageCount,
					filePath = null,
				)
				pendingExtension = null
				_state.value = TimestampDialogState.Success(
					outputFile = outcome.path,
					newLevel = pending.newLevel,
					warnings = pending.annotatedWarnings,
				)
				viewModelScope.launch { populateRenewalOfferIfNeeded(outcome.path) }
			}

			is SaveOutcome.SavedNameUnknown -> {
				addToRenewalJobFlag = false
				extendedDocument = null
				pendingExtension = null
				_state.value = TimestampDialogState.Success(
					outputFile = outcome.downloadedAs,
					newLevel = pending.newLevel,
					warnings = pending.annotatedWarnings,
				)
			}
		}
	}



	/**
	 * Abort the revocation warning and return to the extension form.
	 *
	 * Called when the user clicks "Abort" on the revocation warning screen.
	 */
	fun abortAfterRevocationWarning() {
		pendingExtension = null
		_state.value = lastReadyState ?: TimestampDialogState.Idle
	}

	/**
	 * Dismiss the dialog and reset the state to [TimestampDialogState.Idle].
	 *
	 * The pre-fetched [DocumentTimestampInfo] is retained because it is tied to
	 * the currently loaded document, not the dialog session. The [pendingRenewalOffer]
	 * is intentionally retained so the UI can still display the renewal job assignment
	 * dialog after the timestamp dialog closes.
	 */
	fun dismiss() {
		_state.value = TimestampDialogState.Idle
		resolvedConfig = null
		activeProfileName = null
		lastReadyState = null
		pendingExtension = null
		extendedDocument = null
		documentAlreadyContainsLtData = false
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
					_pendingRenewalOffer.value = offer.copy(error = RenewalOfferError.JobNotFound(jobName))
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
						onFailure = { _ ->
							_pendingRenewalOffer.value = offer.copy(error = RenewalOfferError.JobAlreadyExists(job.name))
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
	 * Populate [_pendingRenewalOffer] when the extension produced a B-LTA document
	 * and the user opted in to renewal job assignment.
	 */
	private suspend fun populateRenewalOfferIfNeeded(outputFile: String) {
		if (!addToRenewalJobFlag || renewalJobAssigner == null) return
		val offer = renewalJobAssigner.buildOfferState(outputFile)
		_pendingRenewalOffer.value = offer
	}

	/**
	 * Extended bytes produced by [extend] / [acceptRevocationWarning] plus the metadata needed to
	 * finish once the user picks a save destination in [saveExtendedDocument]. Held in memory between
	 * the steps and discarded on [abortAfterRevocationWarning] / [cancelSave] so a cancelled flow
	 * writes nothing.
	 *
	 * @property isArchival Whether the extension is archival (B-LTA). Kept for the offer's wording,
	 *   not to gate it: an output that stops short of B-LTA still owes a step, on a deadline that
	 *   cannot be recovered once missed, so it needs watching at least as much as a sealed one.
	 * @property pageCount Source-document page count, reused for the web [extendedDocument] reload.
	 */
	private class PendingExtension(
		val outputBytes: ByteArray,
		val newLevel: String,
		val annotatedWarnings: List<AnnotatedWarning>,
		val addToRenewalJob: Boolean,
		val isArchival: Boolean,
		val pageCount: Int,
	)
}
