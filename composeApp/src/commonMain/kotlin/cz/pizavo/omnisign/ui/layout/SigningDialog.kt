package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.LockedTokenInfo
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.progressindicators.CircularProgressIndicator
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.ErrorMessage
import cz.pizavo.omnisign.ui.model.SigningDialogState
import cz.pizavo.omnisign.ui.model.localized
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.formattedDate
import cz.pizavo.omnisign.ui.platform.isWebPlatform
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen modal dialog for configuring and executing a document signing operation.
 *
 * The dialog adapts its content to the current [SigningDialogState]:
 * - [SigningDialogState.Loading]: certificate discovery spinner.
 * - [SigningDialogState.Ready]: signing form with certificate selector, algorithm options,
 *   metadata fields, and output path. Locked tokens show an unlock action; a button
 *   allows importing certificates from a PKCS#12 file.
 * - [SigningDialogState.Signing]: progress indicator.
 * - [SigningDialogState.RevocationWarning]: revocation data warning with abort/continue options.
 * - [SigningDialogState.Success]: summary of the created signature.
 * - [SigningDialogState.Error]: error message with a retry option.
 *
 * @param state Current signing dialog state from [cz.pizavo.omnisign.ui.viewmodel.SigningViewModel].
 * @param canTimestamp Whether the server permits timestamping. When `false` the signature /
 *   archival timestamp options are hidden, constraining the form to B-B signatures (signing-time
 *   timestamps use the server's TSA, which a TIMESTAMP-disabled institution does not want
 *   produced). When the resolved level *requires* a timestamp the ViewModel blocks before this
 *   form opens (see [SigningDialogState.TimestampingUnavailable]), so a hidden option can never
 *   silently downgrade a timestamped profile to B-B.
 * @param onFieldChange Called with a transform to update a field in the [SigningDialogState.Ready] state.
 * @param onSign Called when the user clicks the Sign button.
 * @param onAbortRevocation Called when the user aborts after a revocation warning.
 * @param onAcceptRevocation Called when the user continues despite revocation warnings.
 * @param onUnlockToken Called with a token ID when the user clicks Unlock on a locked token.
 * @param onImportPkcs12 Called with the absolute file path when the user picks a PKCS#12 file to
 *   import. The upload control is desktop-only — hidden on the web target, which has no local
 *   token access and signs server-side.
 * @param onRescan Called when the user clicks the rescan button in the header to force a fresh
 *   token discovery cycle.  Shown in [SigningDialogState.Ready] when no discovery is currently in
 *   flight (mutually exclusive with the refreshing indicator), and only where local token
 *   discovery applies — hidden on the web target.
 * @param onShowDiagnostic Called when the user clicks any "Show diagnostic info" affordance —
 *   the info icon in the header, the link in the empty-state banner, or the action button on a
 *   "no PKCS#11 tokens detected" rescan toast.  These PKCS#11 diagnostic affordances apply only
 *   where local tokens do and are hidden on the web target.  Opens the diagnostic snapshot dialog
 *   so the user can see what PC/SC reports and where to add missing PKCS#11 library paths.
 * @param onDismiss Called when the user cancels or closes the dialog.
 */
@Composable
fun SigningDialog(
	state: SigningDialogState,
	canTimestamp: Boolean = true,
	onFieldChange: ((SigningDialogState.Ready) -> SigningDialogState.Ready) -> Unit,
	onSign: () -> Unit,
	onAbortRevocation: () -> Unit,
	onAcceptRevocation: () -> Unit,
	onUnlockToken: (tokenId: String) -> Unit,
	onImportPkcs12: (filePath: String) -> Unit,
	onRescan: () -> Unit,
	onShowDiagnostic: () -> Unit,
	onDismiss: () -> Unit,
) {
	val inProgress = state is SigningDialogState.Signing || state is SigningDialogState.AwaitingSave
	Dialog(
		onDismissRequest = {
			if (!inProgress) onDismiss()
		},
		modifier = Modifier
			.widthIn(min = 560.dp, max = 720.dp)
			.heightIn(min = 400.dp, max = 640.dp),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			val readyState = state as? SigningDialogState.Ready
			SigningDialogHeader(
				onClose = onDismiss,
				closeable = !inProgress,
				refreshing = readyState?.refreshing == true,
				onRescan = if (readyState != null && !readyState.refreshing && !isWebPlatform()) onRescan else null,
				onShowDiagnostic = if (readyState != null && !isWebPlatform()) onShowDiagnostic else null,
			)

			HorizontalDivider()

			Box(modifier = Modifier.weight(1f)) {
				when (state) {
					is SigningDialogState.Idle -> {}
					is SigningDialogState.Loading -> LoadingContent(stringResource(Res.string.signing_discovering_certificates))
					is SigningDialogState.TimestampingUnavailable -> TimestampingUnavailableContent(state)
					is SigningDialogState.Ready -> SigningFormContent(
						state = state,
						canTimestamp = canTimestamp,
						onFieldChange = onFieldChange,
						onUnlockToken = onUnlockToken,
						onImportPkcs12 = onImportPkcs12,
						onShowDiagnostic = onShowDiagnostic,
					)

					is SigningDialogState.Signing -> LoadingContent(stringResource(Res.string.signing_signing_document))
					is SigningDialogState.AwaitingSave -> LoadingContent(stringResource(Res.string.signing_signing_document))
					is SigningDialogState.RevocationWarning -> RevocationWarningContent(state)
					is SigningDialogState.Success -> SigningSuccessContent(state)
					is SigningDialogState.Error -> ErrorContent(error = state.content)
				}
			}

			HorizontalDivider()

			SigningDialogFooter(
				state = state,
				onSign = onSign,
				onAbortRevocation = onAbortRevocation,
				onAcceptRevocation = onAcceptRevocation,
				onDismiss = onDismiss,
			)
		}
	}
}

/**
 * Header row with the dialog title and close button.
 *
 * The slot next to the title hosts two independent affordances:
 *
 * - **Refresh slot (mutually exclusive)** — bound to [refreshing]:
 *   - `true` shows a small inline progress indicator while a background discovery cycle is
 *     running (warmup, PC/SC-event-driven rediscovery, or user-triggered rescan).
 *   - `false` with non-null [onRescan] shows a refresh icon button that manually triggers a
 *     full rescan; covers the edge case of installing new PKCS#11 middleware while the app is
 *     running where no PC/SC event would fire.
 * - **Diagnostic slot (always-on while [onShowDiagnostic] is non-null)** — an info icon button
 *   that opens the PKCS#11 diagnostic snapshot dialog.  Coexists with the refresh slot
 *   regardless of `refreshing` because the diagnostic surface is a read-only inspection
 *   affordance, not a duplicate trigger of the in-flight cycle.  Needed so users can reach the
 *   diagnostic dialog even when other sources (Windows-MY, PKCS#12 files) populated the
 *   certificate list and the empty-state banner — which carries its own "Show diagnostic info"
 *   link — never appears.
 *
 * Outside of [SigningDialogState.Ready] (Loading / Signing / Success / etc) both slots are
 * suppressed by passing null callbacks.  The certificate list stays visible underneath the
 * indicator so the user can keep interacting while a refresh runs in the background.
 *
 * @param onClose Callback invoked when the close button is clicked.
 * @param closeable Whether the close button is enabled.
 * @param refreshing Whether a background token-discovery cycle is currently in flight.
 * @param onRescan Callback for the rescan button, or `null` to suppress it.  Must be
 *   `null` whenever [refreshing] is `true` to preserve mutual exclusivity with the indicator.
 * @param onShowDiagnostic Callback for the always-on diagnostic info icon, or `null` to
 *   suppress it (e.g. outside [SigningDialogState.Ready]).
 */
@Composable
private fun SigningDialogHeader(
	onClose: () -> Unit,
	closeable: Boolean,
	refreshing: Boolean,
	onRescan: (() -> Unit)? = null,
	onShowDiagnostic: (() -> Unit)? = null,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(text = stringResource(Res.string.signing_header_title), style = LumoTheme.typography.h3)
			when {
				refreshing -> CircularProgressIndicator(
					modifier = Modifier.size(14.dp),
					strokeWidth = 2.dp,
				)
				onRescan != null -> TooltipBox(
					tooltip = { Tooltip { Text(text = stringResource(Res.string.signing_rescan_tokens)) } },
					state = rememberTooltipState(),
				) {
					IconButton(
						variant = IconButtonVariant.Ghost,
						onClick = onRescan,
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_refresh),
							contentDescription = stringResource(Res.string.signing_rescan_tokens),
							modifier = Modifier.size(16.dp),
						)
					}
				}
			}
			if (onShowDiagnostic != null) {
				TooltipBox(
					tooltip = { Tooltip { Text(text = stringResource(Res.string.signing_show_pkcs11_diagnostic_info)) } },
					state = rememberTooltipState(),
				) {
					IconButton(
						variant = IconButtonVariant.Ghost,
						onClick = onShowDiagnostic,
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_alert_info),
							contentDescription = stringResource(Res.string.signing_show_pkcs11_diagnostic_info),
							modifier = Modifier.size(16.dp),
						)
					}
				}
			}
		}
		IconButton(
			variant = IconButtonVariant.Ghost,
			enabled = closeable,
			onClick = onClose,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = stringResource(Res.string.action_close),
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

/**
 * Scrollable form section for configuring the signing operation.
 *
 * @param state Current [SigningDialogState.Ready] state.
 * @param onFieldChange Called with a transform to update a field.
 * @param onUnlockToken Called with a token ID when the user clicks Unlock.
 * @param onImportPkcs12 Called with the file path when the user imports a PKCS#12 file.
 * @param canTimestamp Whether the server permits timestamping; when `false` the signature /
 *   archival timestamp options are not rendered. Configurations that *require* a timestamp are
 *   blocked before this form opens, so hiding only ever drops genuinely optional timestamps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SigningFormContent(
	state: SigningDialogState.Ready,
	canTimestamp: Boolean,
	onFieldChange: ((SigningDialogState.Ready) -> SigningDialogState.Ready) -> Unit,
	onUnlockToken: (tokenId: String) -> Unit,
	onImportPkcs12: (filePath: String) -> Unit,
	onShowDiagnostic: () -> Unit,
) {
	val pkcs12Picker = rememberFilePickerLauncher(
		type = FileKitType.File(extensions = listOf("p12", "pfx")),
	) { file: PlatformFile? ->
		if (file != null) {
			val path = platformFilePath(file) ?: file.name
			onImportPkcs12(path)
		}
	}
	
	VerticalScrollableColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		val lockedTokenIds = state.lockedTokens.map { it.tokenId }.toSet()
		val generalWarningsByToken = state.tokenWarnings
			.filter { it.tokenId !in lockedTokenIds }
			.groupBy { it.tokenId }
		
		if (state.lockedTokens.isNotEmpty()) {
			LockedTokensAccordion(
				lockedTokens = state.lockedTokens,
				tokenWarnings = state.tokenWarnings,
				onUnlockToken = onUnlockToken,
			)
		}
		
		generalWarningsByToken.forEach { (_, warnings) ->
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_alert_warning),
						contentDescription = null,
						modifier = Modifier.size(14.dp),
						tint = LumoTheme.colors.warning,
					)
					Text(
						text = warnings.first().tokenName,
						style = LumoTheme.typography.body2,
						color = LumoTheme.colors.textSecondary,
					)
				}
				
				warnings.forEach { warning ->
					TokenWarningRow(warning.message)
				}
			}
		}
		
		if (state.certificates.isEmpty() && state.lockedTokens.isEmpty() && !state.refreshing) {
			EmptyTokenBanner(onShowDiagnostic = if (!isWebPlatform()) onShowDiagnostic else null)
		}

		val certOptions = state.certificates.map { it.alias }
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.Bottom,
			) {
				DropdownSelector(
					selected = state.selectedAlias,
					options = certOptions,
					onSelect = { alias -> onFieldChange { it.copy(selectedAlias = alias) } },
					label = { Text(stringResource(Res.string.signing_certificate_label)) },
					nullLabel = stringResource(Res.string.signing_select_certificate_placeholder),
					showNullOption = false,
					itemLabel = { alias ->
						val cert = state.certificates.find { it.alias == alias }
						if (cert != null) {
							val source = cert.tokenName.takeIf { it.isNotBlank() }?.let { "; $it" } ?: ""
							stringResource(Res.string.signing_cert_dropdown_item_label, cert.commonName(), cert.validTo.formattedDate(), source)
						} else {
							alias
						}
					},
					itemContent = { alias ->
						val cert = state.certificates.find { it.alias == alias }
						CertDropdownRow(alias = alias, cert = cert)
					},
					modifier = Modifier.weight(1f),
				)
				if (!isWebPlatform()) {
					IconButton(
						variant = IconButtonVariant.Ghost,
						onClick = { pkcs12Picker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_upload),
							contentDescription = stringResource(Res.string.signing_import_pkcs12_file),
							modifier = Modifier.size(20.dp),
						)
					}
				}
			}
			val selectedCert = state.certificates.find { it.alias == state.selectedAlias }
			if (selectedCert != null) {
				CertQualificationBadge(cert = selectedCert)
			}
		}
		
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			DropdownSelector(
				selected = state.hashAlgorithm,
				options = HashAlgorithm.entries.toList(),
				onSelect = { alg -> onFieldChange { it.copy(hashAlgorithm = alg) } },
				label = { Text(stringResource(Res.string.signing_hash_algorithm_label)) },
				nullLabel = stringResource(Res.string.signing_hash_algorithm_default, state.configHashAlgorithm.name),
				showNullOption = true,
				disabledOptions = state.disabledHashAlgorithms,
				itemLabel = { it.name },
				modifier = Modifier.weight(1f),
			)
		}
		
		if (canTimestamp) {
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Checkbox(
					checked = state.addSignatureTimestamp,
					onCheckedChange = { checked -> onFieldChange { it.copy(addSignatureTimestamp = checked) } },
					enabled = !state.addArchivalTimestamp,
				)
				Text(text = stringResource(Res.string.label_signature_timestamp), style = LumoTheme.typography.body2)
				InfoTooltip(text = stringResource(Res.string.label_produces_b_lt))
			}
			
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Checkbox(
					checked = state.addArchivalTimestamp,
					onCheckedChange = { checked ->
						onFieldChange {
							if (checked) it.copy(addArchivalTimestamp = true, addSignatureTimestamp = true)
							else it.copy(addArchivalTimestamp = false, addToRenewalJob = false)
						}
					},
				)
				Text(text = stringResource(Res.string.label_archival_timestamp), style = LumoTheme.typography.body2)
				InfoTooltip(text = stringResource(Res.string.label_produces_b_lta))
			}
			
			if (state.addArchivalTimestamp && !isWebPlatform()) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Checkbox(
						checked = state.addToRenewalJob,
						onCheckedChange = { checked ->
							onFieldChange { it.copy(addToRenewalJob = checked) }
						},
					)
					Text(text = stringResource(Res.string.label_add_to_renewal_job), style = LumoTheme.typography.body2)
					InfoTooltip(
						text = stringResource(Res.string.signing_add_to_renewal_job_tooltip),
					)
				}
			}
		}
		
		}

		UnderlinedTextField(
			value = state.reason,
			onValueChange = { v -> onFieldChange { it.copy(reason = v) } },
			singleLine = true,
			label = { Text(stringResource(Res.string.signing_reason_label)) },
			modifier = Modifier.fillMaxWidth(),
		)
		
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			UnderlinedTextField(
				value = state.location,
				onValueChange = { v -> onFieldChange { it.copy(location = v) } },
				singleLine = true,
				label = { Text(stringResource(Res.string.signing_location_label)) },
				modifier = Modifier.weight(1f),
			)
			
			UnderlinedTextField(
				value = state.contactInfo,
				onValueChange = { v -> onFieldChange { it.copy(contactInfo = v) } },
				singleLine = true,
				label = { Text(stringResource(Res.string.signing_contact_info_label)) },
				modifier = Modifier.weight(1f),
			)
		}
	}
}

/**
 * Success summary shown after a successful signing operation.
 *
 * @param state The [SigningDialogState.Success] state with result details.
 */
@Composable
private fun SigningSuccessContent(state: SigningDialogState.Success) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_check),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				tint = LumoTheme.colors.success,
			)
			Text(text = stringResource(Res.string.signing_document_signed_successfully), style = LumoTheme.typography.h4)
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		SigningResultRow(label = stringResource(Res.string.signing_output_file_label), value = state.outputFile)
		SigningResultRow(label = stringResource(Res.string.signing_signature_id_label), value = state.signatureId)
		SigningResultRow(label = stringResource(Res.string.signing_signature_level_label), value = state.signatureLevel)
		
		if (state.warnings.isNotEmpty()) {
			Spacer(modifier = Modifier.height(8.dp))
			state.warnings.forEach { warning ->
				WarningRow(warning = warning)
			}
		}
	}
}

/**
 * A single label-value row in the result summary.
 *
 * @param label Field label.
 * @param value Field value.
 */
@Composable
private fun SigningResultRow(label: String, value: String) {
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(
			text = "$label:",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
		Text(text = value, style = LumoTheme.typography.body2)
	}
}

/**
 * Warning screen shown when signing completed but revocation data could not be obtained.
 *
 * @param state The [SigningDialogState.RevocationWarning] state with warning details.
 */
@Composable
private fun RevocationWarningContent(state: SigningDialogState.RevocationWarning) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_alert_warning),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				tint = LumoTheme.colors.warning,
			)
			Text(text = stringResource(Res.string.label_revocation_unavailable), style = LumoTheme.typography.h4)
		}
		
		Spacer(modifier = Modifier.height(4.dp))
		
		Text(
			text = stringResource(Res.string.signing_revocation_warning_body),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
		
		Spacer(modifier = Modifier.height(8.dp))
		
		state.warnings.forEach { warning ->
			WarningRow(warning = warning)
		}
	}
}

/**
 * Footer with Cancel and Sign / Close buttons.
 *
 * @param state Current dialog state determining which buttons to show.
 * @param onSign Called when the Sign button is clicked.
 * @param onAbortRevocation Called when the Abort button is clicked on the revocation warning.
 * @param onAcceptRevocation Called when the Continue button is clicked on the revocation warning.
 * @param onDismiss Called when Cancel or Close is clicked.
 */
@Composable
private fun SigningDialogFooter(
	state: SigningDialogState,
	onSign: () -> Unit,
	onAbortRevocation: () -> Unit,
	onAcceptRevocation: () -> Unit,
	onDismiss: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
	) {
		when (state) {
			is SigningDialogState.Ready -> {
				Button(
					text = stringResource(Res.string.action_cancel),
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
				Button(
					text = stringResource(Res.string.action_sign),
					variant = ButtonVariant.Primary,
					enabled = state.selectedAlias != null,
					onClick = onSign,
				)
			}
			
			is SigningDialogState.RevocationWarning -> {
				Button(
					text = stringResource(Res.string.action_continue_anyway),
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onAcceptRevocation,
				)
				Button(
					text = stringResource(Res.string.action_abort),
					variant = ButtonVariant.Primary,
					onClick = onAbortRevocation,
				)
			}
			
			is SigningDialogState.Success -> {
				Button(
					text = stringResource(Res.string.action_close),
					variant = ButtonVariant.Primary,
					onClick = onDismiss,
				)
			}

			is SigningDialogState.Error -> {
				Button(
					text = stringResource(Res.string.action_close),
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
			}

			is SigningDialogState.TimestampingUnavailable -> {
				Button(
					text = stringResource(Res.string.action_close),
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
			}
			else -> {}
		}
	}
}

/**
 * Centered loading indicator with a descriptive message.
 *
 * @param message Text displayed below the spinner.
 */
@Composable
internal fun LoadingContent(message: String) {
	Box(
		modifier = Modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			CircularProgressIndicator(modifier = Modifier.size(40.dp))
			Spacer(modifier = Modifier.height(12.dp))
			Text(text = message, style = LumoTheme.typography.body2)
		}
	}
}

/**
 * Error display that resolves an [ErrorMessage] to a primary message and optional details.
 *
 * DSS exception messages often contain internal identifiers (e.g. `S-<hex>`, `C-<hex>`)
 * that are meaningless to end users. [sanitizeDssDetails] strips them before display. The
 * message and details are wrapped in [SelectableContent] so they can be copied (e.g. into a
 * bug report).
 *
 * @param error Locale-agnostic error data emitted by a view model.
 */
@Composable
internal fun ErrorContent(error: ErrorMessage) {
	val (message, details) = when (error) {
		is ErrorMessage.Domain -> error.text.localized() to error.details
		is ErrorMessage.ConfigResolution -> stringResource(Res.string.error_config_resolution, error.text.localized()) to null
		is ErrorMessage.WriteFailed -> stringResource(if (error.signed) Res.string.error_write_signed else Res.string.error_write_extended) to error.reason
		is ErrorMessage.RevocationRefreshFailed -> stringResource(Res.string.error_revocation_refresh_failed) to (stringResource(Res.string.error_revocation_lt_degrade) + (error.domainDetails?.let { "\n\n$it" } ?: ""))
		ErrorMessage.CompilerUnavailable -> stringResource(Res.string.error_compiler_unavailable) to null
	}
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_alert_danger),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				tint = LumoTheme.colors.error,
			)
			SelectableContent { Text(text = message, style = LumoTheme.typography.h4) }
		}
		if (!details.isNullOrBlank()) {
			SelectableContent {
				Text(
					text = sanitizeDssDetails(details),
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
			}
		}
	}
}

/**
 * Collapsible section listing all locked (PIN-protected) tokens with individual unlock buttons.
 *
 * Starts expanded so the user immediately sees which tokens require a PIN. The caller is
 * responsible for only rendering this composable when [lockedTokens] is non-empty.
 *
 * @param lockedTokens Tokens that require a PIN but have no stored credential.
 * @param tokenWarnings All current discovery warnings; those matching each token's ID are
 *   rendered inline under that token's row.
 * @param onUnlockToken Callback invoked with the token ID when the Unlock button is clicked.
 */
@Composable
private fun LockedTokensAccordion(
	lockedTokens: List<LockedTokenInfo>,
	tokenWarnings: List<TokenDiscoveryWarning>,
	onUnlockToken: (String) -> Unit,
) {
	val accordionState = rememberAccordionState(expanded = true)
	val chevronRotation by animateFloatAsState(
		targetValue = if (accordionState.expanded) 180f else 0f,
		label = "lockedTokensChevron",
	)

	Accordion(
		state = accordionState,
		headerContent = {
			Row(
				modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					painter = painterResource(Res.drawable.icon_lock),
					contentDescription = null,
					modifier = Modifier.size(14.dp),
					tint = LumoTheme.colors.textSecondary,
				)
				val count = lockedTokens.size
				Text(
					text = pluralStringResource(Res.plurals.signing_locked_tokens_count, count, count),
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
					modifier = Modifier.weight(1f),
				)
				Icon(
					painter = painterResource(Res.drawable.icon_chevron_down),
					contentDescription = if (accordionState.expanded) stringResource(Res.string.action_collapse) else stringResource(Res.string.action_expand),
					modifier = Modifier.size(14.dp).rotate(chevronRotation),
					tint = LumoTheme.colors.textSecondary,
				)
			}
		},
		bodyContent = {
			Column(
				modifier = Modifier.padding(top = 4.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				lockedTokens.forEach { locked ->
					Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Row(
							horizontalArrangement = Arrangement.spacedBy(8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = locked.tokenName,
								style = LumoTheme.typography.body2,
								color = LumoTheme.colors.textSecondary,
								modifier = Modifier.weight(1f),
							)
							TooltipBox(
								tooltip = { Tooltip { Text(stringResource(Res.string.signing_unlock)) } },
							) {
								IconButton(
									variant = IconButtonVariant.Ghost,
									onClick = { onUnlockToken(locked.tokenId) },
								) {
									Icon(
										painter = painterResource(Res.drawable.icon_lock_open),
										contentDescription = stringResource(Res.string.signing_unlock),
										modifier = Modifier.size(20.dp),
									)
								}
							}
						}
						tokenWarnings
							.filter { it.tokenId == locked.tokenId }
							.forEach { warning -> TokenWarningRow(warning.message) }
					}
				}
			}
		},
	)
}

/**
 *
 * Renders the certificate's common name in bold with an optional eIDAS rosette icon and,
 * for certificates held on a PKCS#11 hardware token, a USB device icon alongside it.
 * Underneath, in a smaller secondary style, the expiry is shown as `valid until <date>`,
 * followed by `; <source>` when the source token name is known so the same certificate
 * present on more than one source stays distinguishable. When [cert] is `null` (alias not
 * yet matched to a loaded certificate) only the raw [alias] string is shown.
 *
 * @param alias Raw alias string used as a fallback when [cert] is unavailable.
 * @param cert The matched [AvailableCertificateInfo], or `null` if not yet resolved.
 */
@Composable
private fun CertDropdownRow(alias: String, cert: AvailableCertificateInfo?) {
	if (cert == null) {
		Text(text = alias, style = LumoTheme.typography.body2)
		return
	}
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text(
				text = cert.commonName(),
				style = LumoTheme.typography.body2,
				fontWeight = FontWeight.SemiBold,
			)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				when {
					cert.isQscd == true -> Icon(
						painter = painterResource(Res.drawable.icon_rosette_check),
						contentDescription = stringResource(Res.string.signing_cert_qualified_qscd),
						modifier = Modifier.size(14.dp),
						tint = LumoTheme.colors.icons.trustQualifiedQscd,
					)
					cert.isQualified == true -> Icon(
						painter = painterResource(Res.drawable.icon_rosette),
						contentDescription = stringResource(Res.string.signing_cert_qualified),
						modifier = Modifier.size(14.dp),
						tint = LumoTheme.colors.icons.trustQualified,
					)
				}
				if (cert.tokenType == TokenType.PKCS11.name) {
					Icon(
						painter = painterResource(Res.drawable.icon_device_usb),
						contentDescription = stringResource(Res.string.signing_cert_on_pkcs11_token),
						modifier = Modifier.size(14.dp),
						tint = LumoTheme.colors.success,
					)
				}
			}
		}
		val expiry = stringResource(Res.string.signing_cert_valid_until, cert.validTo.formattedDate())
		Text(
			text = if (cert.tokenName.isNotBlank()) stringResource(Res.string.signing_cert_valid_until_source, expiry, cert.tokenName) else expiry,
			style = LumoTheme.typography.body3,
			color = LumoTheme.colors.textSecondary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * Displays the eIDAS qualification status of [cert] as an icon-and-label row with a tooltip.
 *
 * Mirrors the rosette convention used in [SignaturePanel]: [Res.drawable.icon_rosette_check] for
 * QSCD-backed qualified certificates, [Res.drawable.icon_rosette] for qualified without confirmed
 * QSCD, and [Res.drawable.icon_shield_x] for explicitly non-qualified. Nothing is rendered when
 * both [AvailableCertificateInfo.isQscd] and [AvailableCertificateInfo.isQualified] are `null`
 * (QCStatements extension absent or unreadable on the certificate).
 *
 * @param cert The certificate whose qualification status to display.
 */
@Composable
private fun CertQualificationBadge(cert: AvailableCertificateInfo) {
	data class BadgeConfig(
		val icon: DrawableResource,
		val tint: Color,
		val label: String,
		val tooltip: String,
	)

	val config = when {
		cert.isQscd == true -> BadgeConfig(
			icon = Res.drawable.icon_rosette_check,
			tint = LumoTheme.colors.icons.trustQualifiedQscd,
			label = stringResource(Res.string.signing_cert_qualified_qscd),
			tooltip = stringResource(Res.string.signing_cert_qscd_tooltip),
		)
		cert.isQualified == true -> BadgeConfig(
			icon = Res.drawable.icon_rosette,
			tint = LumoTheme.colors.icons.trustQualified,
			label = stringResource(Res.string.signing_cert_qualified),
			tooltip = stringResource(Res.string.signing_cert_qualified_tooltip),
		)
		cert.isQualified == false -> BadgeConfig(
			icon = Res.drawable.icon_shield_x,
			tint = LumoTheme.colors.textSecondary,
			label = stringResource(Res.string.signing_cert_not_qualified),
			tooltip = stringResource(Res.string.signing_cert_not_qualified_tooltip),
		)
		else -> return
	}

	TooltipBox(
		tooltip = { Tooltip { Text(text = config.tooltip) } },
		state = rememberTooltipState(),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(config.icon),
				contentDescription = config.label,
				modifier = Modifier.size(14.dp),
				tint = config.tint,
			)
			Text(
				text = config.label,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
		}
	}
}

/**
 * A single warning message row indented under its parent token/QSCD section.
 *
 * @param message Warning text to display.
 */
@Composable
private fun TokenWarningRow(message: LocalizableText) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		verticalAlignment = Alignment.Top,
		modifier = Modifier.padding(start = 22.dp),
	) {
		Icon(
			painter = painterResource(Res.drawable.icon_alert_warning),
			contentDescription = null,
			modifier = Modifier.padding(top = 3.dp).size(14.dp),
			tint = LumoTheme.colors.warning,
		)
		SelectableContent {
			Text(
				text = message.localized(),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.warning,
			)
		}
	}
}

/**
 * Banner shown above the certificate dropdown when no tokens were discovered and no
 * background discovery cycle is in flight.
 *
 * The dropdown alone gives no signal about *what* the user can do next — they see a
 * disabled control with the placeholder "Select a certificate…" and have no way to tell
 * whether the app failed to find their token or whether they just haven't inserted it
 * yet.  This banner makes the next steps explicit:
 *  1. Insert a smart card (auto-refresh picks it up via PC/SC).
 *  2. Use the import button below to load a PKCS#12 file directly.
 *  3. Add the middleware path under Global Settings → PKCS#11 Libraries when the OS
 *     doesn't advertise it (e.g. SafeNet Authentication Client on Windows registers
 *     CSP / minidriver entries but doesn't expose a `Pkcs11Lib` value in the Calais
 *     registry, so PC/SC enumeration alone cannot find it).
 *
 * When [onShowDiagnostic] is non-null, a "Show diagnostic info" link appears at the
 * bottom — clicking it surfaces the diagnostic snapshot dialog so the user can see
 * which readers PC/SC sees, which candidate libraries discovery would probe, and
 * where to drop a library file for automatic pickup.
 *
 * @param onShowDiagnostic Callback invoked when the user clicks the diagnostic link;
 *   suppresses the link when `null`.
 */
@Composable
private fun EmptyTokenBanner(onShowDiagnostic: (() -> Unit)? = null) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Top,
	) {
		Icon(
			painter = painterResource(Res.drawable.icon_alert_info),
			contentDescription = null,
			modifier = Modifier.padding(top = 3.dp).size(16.dp),
			tint = LumoTheme.colors.textSecondary,
		)
		Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				text = stringResource(Res.string.signing_no_tokens_detected),
				style = LumoTheme.typography.body2,
				fontWeight = FontWeight.SemiBold,
				color = LumoTheme.colors.text,
			)
			Text(
				text = stringResource(Res.string.signing_no_tokens_hint_smartcard),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			Text(
				text = stringResource(Res.string.signing_no_tokens_hint_library),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			if (onShowDiagnostic != null) {
				Button(
					variant = ButtonVariant.PrimaryGhost,
					text = stringResource(Res.string.signing_show_diagnostic_info),
					onClick = onShowDiagnostic,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
		}
	}
}

/**
 * Blocking screen shown when the resolved profile / configuration mandates a timestamped
 * signature level but the server has timestamping disabled.
 *
 * Explains the conflict and the two ways out (pick a profile whose level the server can
 * satisfy, or ask the administrator to enable timestamping). There is no Sign affordance —
 * the footer shows only Close — because producing a B-B signature here would silently violate
 * the profile's required level. Mirrors the server-side `TIMESTAMP_NOT_ALLOWED` rejection.
 *
 * @param state The [SigningDialogState.TimestampingUnavailable] state carrying the active
 *   profile name (when any) and the level that requires a timestamp.
 */
@Composable
private fun TimestampingUnavailableContent(state: SigningDialogState.TimestampingUnavailable) {
	val levelLabel = when (state.requiredLevel) {
		SignatureLevel.PADES_BASELINE_T -> "B-T"
		SignatureLevel.PADES_BASELINE_LT -> "B-LT"
		SignatureLevel.PADES_BASELINE_LTA -> "B-LTA"
		SignatureLevel.PADES_BASELINE_B -> "B-B"
	}
	val intro = if (state.profileName != null) {
		stringResource(Res.string.signing_timestamping_intro_profile, state.profileName, levelLabel)
	} else {
		stringResource(Res.string.signing_timestamping_intro_no_profile, levelLabel)
	}
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_alert_warning),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				tint = LumoTheme.colors.warning,
			)
			Text(text = stringResource(Res.string.signing_timestamping_unavailable), style = LumoTheme.typography.h4)
		}
		Text(
			text = stringResource(Res.string.signing_timestamping_body, intro),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
		Text(
			text = stringResource(Res.string.signing_timestamping_select_profile),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	}
}

private val DSS_ID_PATTERN = Regex("""\[?[SsCcTt]-[A-Fa-f0-9]{16,}:?\s*""")
private val TRAILING_BRACKET = Regex("""\s*]$""")

/**
 * Remove internal DSS identifiers (signature / certificate / timestamp hex IDs) from
 * an error detail string so the UI shows only the human-readable part.
 */
private fun sanitizeDssDetails(raw: String): String {
	return raw
		.replace(DSS_ID_PATTERN, "")
		.replace(TRAILING_BRACKET, "")
		.trim()
}

