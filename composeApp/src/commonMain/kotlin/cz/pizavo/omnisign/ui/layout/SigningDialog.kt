package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.LockedTokenInfo
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.progressindicators.CircularProgressIndicator
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.SigningDialogState
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

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
 * @param onFieldChange Called with a transform to update a field in the [SigningDialogState.Ready] state.
 * @param onSign Called when the user clicks the Sign button.
 * @param onAbortRevocation Called when the user aborts after a revocation warning.
 * @param onAcceptRevocation Called when the user continues despite revocation warnings.
 * @param onUnlockToken Called with a token ID when the user clicks Unlock on a locked token.
 * @param onImportPkcs12 Called with the absolute file path when the user picks a PKCS#12 file to import.
 * @param onDismiss Called when the user cancels or closes the dialog.
 */
@Composable
fun SigningDialog(
	state: SigningDialogState,
	onFieldChange: ((SigningDialogState.Ready) -> SigningDialogState.Ready) -> Unit,
	onSign: () -> Unit,
	onAbortRevocation: () -> Unit,
	onAcceptRevocation: () -> Unit,
	onUnlockToken: (tokenId: String) -> Unit,
	onImportPkcs12: (filePath: String) -> Unit,
	onDismiss: () -> Unit,
) {
	Dialog(
		onDismissRequest = {
			if (state !is SigningDialogState.Signing) onDismiss()
		},
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.widthIn(min = 560.dp, max = 720.dp)
				.heightIn(min = 400.dp, max = 640.dp),
			shape = RoundedCornerShape(16.dp),
			color = LumoTheme.colors.surface,
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				SigningDialogHeader(
					onClose = onDismiss,
					closeable = state !is SigningDialogState.Signing,
				)
				
				HorizontalDivider()
				
				Box(modifier = Modifier.weight(1f)) {
					when (state) {
						is SigningDialogState.Idle -> {}
						is SigningDialogState.Loading -> LoadingContent("Discovering certificates...")
						is SigningDialogState.Ready -> SigningFormContent(
							state = state,
							onFieldChange = onFieldChange,
							onUnlockToken = onUnlockToken,
							onImportPkcs12 = onImportPkcs12,
						)
						
						is SigningDialogState.Signing -> LoadingContent("Signing document...")
						is SigningDialogState.RevocationWarning -> RevocationWarningContent(state)
						is SigningDialogState.Success -> SigningSuccessContent(state)
						is SigningDialogState.Error -> ErrorContent(
							message = state.message,
							details = state.details,
						)
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
}

/**
 * Header row with the dialog title and close button.
 *
 * @param onClose Callback invoked when the close button is clicked.
 * @param closeable Whether the close button is enabled.
 */
@Composable
private fun SigningDialogHeader(onClose: () -> Unit, closeable: Boolean) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = "Sign Document", style = LumoTheme.typography.h3)
		IconButton(
			variant = IconButtonVariant.Ghost,
			enabled = closeable,
			onClick = onClose,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = "Close",
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
 */
@Composable
private fun SigningFormContent(
	state: SigningDialogState.Ready,
	onFieldChange: ((SigningDialogState.Ready) -> SigningDialogState.Ready) -> Unit,
	onUnlockToken: (tokenId: String) -> Unit,
	onImportPkcs12: (filePath: String) -> Unit,
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
					label = { Text("Certificate") },
					nullLabel = "Select a certificate\u2026",
					showNullOption = false,
					itemLabel = { alias ->
						val cert = state.certificates.find { it.alias == alias }
						if (cert != null) "${cert.alias} — ${cert.subjectDN}" else alias
					},
					itemContent = { alias ->
						val cert = state.certificates.find { it.alias == alias }
						CertDropdownRow(alias = alias, cert = cert)
					},
					modifier = Modifier.weight(1f),
				)
				IconButton(
					variant = IconButtonVariant.Ghost,
					onClick = { pkcs12Picker.launch() },
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_upload),
						contentDescription = "Import PKCS#12 file",
						modifier = Modifier.size(20.dp),
					)
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
				label = { Text("Hash Algorithm") },
				nullLabel = "Default (${state.configHashAlgorithm.name})",
				showNullOption = true,
				disabledOptions = state.disabledHashAlgorithms,
				itemLabel = { it.name },
				modifier = Modifier.weight(1f),
			)
		}
		
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(16.dp),
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
				Text(text = "Signature timestamp", style = LumoTheme.typography.body2)
				InfoTooltip(text = "Produces PAdES BASELINE B-LT")
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
				Text(text = "Archival timestamp", style = LumoTheme.typography.body2)
				InfoTooltip(text = "Produces PAdES BASELINE B-LTA")
			}
			
			if (state.addArchivalTimestamp) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Checkbox(
						checked = state.addToRenewalJob,
						onCheckedChange = { checked ->
							onFieldChange { it.copy(addToRenewalJob = checked) }
						},
						enabled = state.coveringRenewalJobName == null,
					)
					Text(text = "Add to renewal job", style = LumoTheme.typography.body2)
					InfoTooltip(
						text = if (state.coveringRenewalJobName != null)
							"Already covered by \"${state.coveringRenewalJobName}\""
						else
							"Set up automatic archival renewal after signing",
					)
				}
			}
		}
		
		UnderlinedTextField(
			value = state.reason,
			onValueChange = { v -> onFieldChange { it.copy(reason = v) } },
			singleLine = true,
			label = { Text("Reason (optional)") },
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
				label = { Text("Location (optional)") },
				modifier = Modifier.weight(1f),
			)
			
			UnderlinedTextField(
				value = state.contactInfo,
				onValueChange = { v -> onFieldChange { it.copy(contactInfo = v) } },
				singleLine = true,
				label = { Text("Contact Info (optional)") },
				modifier = Modifier.weight(1f),
			)
		}
		
		UnderlinedTextField(
			value = state.outputPath,
			onValueChange = { v -> onFieldChange { it.copy(outputPath = v) } },
			singleLine = true,
			label = { Text("Output file path") },
			modifier = Modifier.fillMaxWidth(),
		)
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
			Text(text = "Document signed successfully", style = LumoTheme.typography.h4)
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		SigningResultRow(label = "Output file", value = state.outputFile)
		SigningResultRow(label = "Signature ID", value = state.signatureId)
		SigningResultRow(label = "Signature level", value = state.signatureLevel)
		
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
			Text(text = "Revocation data unavailable", style = LumoTheme.typography.h4)
		}
		
		Spacer(modifier = Modifier.height(4.dp))
		
		Text(
			text = "The document was signed, but revocation information (CRL/OCSP) could not " +
					"be embedded. Without this data the signature may not be suitable for " +
					"long-term validation.",
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
					text = "Cancel",
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
				Button(
					text = "Sign",
					variant = ButtonVariant.Primary,
					enabled = state.outputPath.isNotBlank() && state.selectedAlias != null,
					onClick = onSign,
				)
			}
			
			is SigningDialogState.RevocationWarning -> {
				Button(
					text = "Continue anyway",
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onAcceptRevocation,
				)
				Button(
					text = "Abort",
					variant = ButtonVariant.Primary,
					onClick = onAbortRevocation,
				)
			}
			
			is SigningDialogState.Success -> {
				Button(
					text = "Close",
					variant = ButtonVariant.Primary,
					onClick = onDismiss,
				)
			}
			
			is SigningDialogState.Error -> {
				Button(
					text = "Close",
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
 * Error display with the message and optional details.
 *
 * DSS exception messages often contain internal identifiers (e.g. `S-<hex>`, `C-<hex>`)
 * that are meaningless to end users. [sanitizeDssDetails] strips them before display.
 *
 * @param message Primary error message.
 * @param details Optional detailed error information.
 */
@Composable
internal fun ErrorContent(message: String, details: String?) {
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
			Text(text = message, style = LumoTheme.typography.h4)
		}
		if (!details.isNullOrBlank()) {
			Text(
				text = sanitizeDssDetails(details),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
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
					text = if (count == 1) "1 locked token" else "$count locked tokens",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
					modifier = Modifier.weight(1f),
				)
				Icon(
					painter = painterResource(Res.drawable.icon_chevron_down),
					contentDescription = if (accordionState.expanded) "Collapse" else "Expand",
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
								tooltip = { Tooltip { Text("Unlock") } },
							) {
								IconButton(
									variant = IconButtonVariant.Ghost,
									onClick = { onUnlockToken(locked.tokenId) },
								) {
									Icon(
										painter = painterResource(Res.drawable.icon_lock_open),
										contentDescription = "Unlock",
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
 * Renders the certificate alias in bold with an optional eIDAS rosette icon alongside it,
 * and the subject DN underneath in a smaller secondary style. When [cert] is `null` (alias
 * not yet matched to a loaded certificate) only the raw [alias] string is shown.
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
				text = cert.alias,
				style = LumoTheme.typography.body2,
				fontWeight = FontWeight.SemiBold,
			)
			when {
				cert.isQscd == true -> Icon(
					painter = painterResource(Res.drawable.icon_rosette_check),
					contentDescription = "Qualified (QSCD)",
					modifier = Modifier.size(14.dp),
					tint = LumoTheme.colors.icons.trustQualifiedQscd,
				)
				cert.isQualified == true -> Icon(
					painter = painterResource(Res.drawable.icon_rosette),
					contentDescription = "Qualified",
					modifier = Modifier.size(14.dp),
					tint = LumoTheme.colors.icons.trustQualified,
				)
			}
		}
		Text(
			text = cert.subjectDN,
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
			label = "Qualified (QSCD)",
			tooltip = "The certificate carries the QcSSCD statement — the private key is stored on a Qualified Signature/Seal Creation Device.",
		)
		cert.isQualified == true -> BadgeConfig(
			icon = Res.drawable.icon_rosette,
			tint = LumoTheme.colors.icons.trustQualified,
			label = "Qualified",
			tooltip = "The certificate carries the QcCompliance statement, but QSCD status was not confirmed.",
		)
		cert.isQualified == false -> BadgeConfig(
			icon = Res.drawable.icon_shield_x,
			tint = LumoTheme.colors.textSecondary,
			label = "Not qualified",
			tooltip = "The certificate does not carry QCStatements indicating eIDAS qualification.",
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
private fun TokenWarningRow(message: String) {
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
		Text(
			text = message,
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.warning,
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

