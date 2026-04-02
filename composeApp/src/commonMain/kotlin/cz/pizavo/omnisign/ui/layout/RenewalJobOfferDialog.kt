package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.RenewalJobOfferState
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_alert_warning
import omnisign.composeapp.generated.resources.icon_check
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Modal dialog for assigning a newly created B-LTA document to a renewal job.
 *
 * Shown after a successful LTA signing or timestamping when the user opted in
 * via the "Add to renewal job" checkbox. The dialog lets the user either:
 * - Select an existing renewal job to add the file to, or
 * - Create a new renewal job with the file as its initial glob pattern.
 *
 * When an existing job already covers the file (a glob pattern matches the
 * output path), an informational message is shown instead of the assignment form.
 *
 * @param state Current [RenewalJobOfferState] with available jobs, profiles, and coverage info.
 * @param onAssignExisting Called with the job name when the user assigns to an existing job.
 * @param onCreateNew Called with a new [RenewalJob] when the user creates a new job.
 * @param onDismiss Called when the user closes the dialog.
 */
@Composable
fun RenewalJobOfferDialog(
	state: RenewalJobOfferState,
	onAssignExisting: (String) -> Unit,
	onCreateNew: (RenewalJob) -> Unit,
	onDismiss: () -> Unit,
) {
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.widthIn(min = 520.dp, max = 680.dp)
				.heightIn(min = 280.dp, max = 560.dp),
			shape = RoundedCornerShape(16.dp),
			color = LumoTheme.colors.surface,
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				RenewalOfferHeader(onClose = onDismiss)

				HorizontalDivider()

				Box(modifier = Modifier.weight(1f)) {
					if (state.assignedJobName != null) {
						RenewalOfferAssignedContent(state.assignedJobName)
					} else if (state.coveringJob != null) {
						RenewalOfferAlreadyCoveredContent(state.coveringJob)
					} else {
						RenewalOfferFormContent(
							state = state,
							onAssignExisting = onAssignExisting,
							onCreateNew = onCreateNew,
						)
					}
				}

				HorizontalDivider()

				RenewalOfferFooter(
					isAssigned = state.assignedJobName != null || state.coveringJob != null,
					onDismiss = onDismiss,
				)
			}
		}
	}
}

/**
 * Header row for the renewal offer dialog.
 *
 * @param onClose Called when the close button is clicked.
 */
@Composable
private fun RenewalOfferHeader(onClose: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = "Add to Renewal Job", style = LumoTheme.typography.h3)
		IconButton(
			variant = IconButtonVariant.Ghost,
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
 * Content shown when the file was successfully assigned to a renewal job.
 *
 * @param jobName Name of the job the file was assigned to.
 */
@Composable
private fun RenewalOfferAssignedContent(jobName: String) {
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
			Text(text = "File added to renewal job", style = LumoTheme.typography.h4)
		}
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = "The output file has been added to the renewal job \"$jobName\". " +
					"It will be automatically re-timestamped when its archival timestamp nears expiry.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	}
}

/**
 * Content shown when an existing job already covers the file.
 *
 * @param coveringJob The job that already covers the file.
 */
@Composable
private fun RenewalOfferAlreadyCoveredContent(coveringJob: RenewalJob) {
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
			Text(text = "Already covered by a renewal job", style = LumoTheme.typography.h4)
		}
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = "The output file is already covered by the renewal job \"${coveringJob.name}\". " +
					"No additional configuration is needed — the file will be automatically " +
					"re-timestamped when its archival timestamp nears expiry.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	}
}

/**
 * Main form content for the renewal offer dialog.
 *
 * Lets the user choose between assigning to an existing job or creating a new one.
 *
 * @param state Current [RenewalJobOfferState].
 * @param onAssignExisting Called with the job name when assigning to an existing job.
 * @param onCreateNew Called with a new [RenewalJob] when creating.
 */
@Composable
private fun RenewalOfferFormContent(
	state: RenewalJobOfferState,
	onAssignExisting: (String) -> Unit,
	onCreateNew: (RenewalJob) -> Unit,
) {
	var selectedExistingJob: String? by remember { mutableStateOf(null) }
	var newJobName by remember { mutableStateOf("") }
	var newJobBufferDays by remember {
		mutableStateOf(ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS.toString())
	}
	var newJobProfile: String? by remember { mutableStateOf(state.activeProfile) }
	var newJobNotify by remember { mutableStateOf(true) }
	var newJobLogFile by remember { mutableStateOf("") }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(
			text = "Set up automatic archival renewal for the signed B-LTA document.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(
				text = "File:",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			Text(
				text = state.outputFile,
				style = LumoTheme.typography.body2,
			)
		}

		if (state.error != null) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					painter = painterResource(Res.drawable.icon_alert_warning),
					contentDescription = null,
					modifier = Modifier.size(14.dp),
					tint = LumoTheme.colors.error,
				)
				Text(
					text = state.error,
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.error,
				)
			}
		}

		if (state.existingJobs.isNotEmpty()) {
			DropdownSelector(
				selected = selectedExistingJob,
				options = state.existingJobs.map { it.name },
				onSelect = { selectedExistingJob = it },
				label = { Text(text = "Existing job") },
				nullLabel = "Create new job",
				showNullOption = true,
				itemLabel = { it },
				modifier = Modifier.fillMaxWidth(),
			)
		}

		if (selectedExistingJob != null) {
			Button(
				text = "Add to \"$selectedExistingJob\"",
				variant = ButtonVariant.Primary,
				onClick = { selectedExistingJob?.let { onAssignExisting(it) } },
				modifier = Modifier.fillMaxWidth(),
			)
		} else {
			Spacer(modifier = Modifier.height(4.dp))
			Text(text = "New renewal job", style = LumoTheme.typography.label1)

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.Bottom,
			) {
				UnderlinedTextField(
					value = newJobName,
					onValueChange = { newJobName = it },
					label = { Text(text = "Name") },
					placeholder = { Text(text = "Job name") },
					singleLine = true,
					modifier = Modifier.weight(1f),
				)
				UnderlinedTextField(
					value = newJobBufferDays,
					onValueChange = { newJobBufferDays = it },
					label = { Text(text = "Buffer days") },
					placeholder = { Text(text = "${ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS}") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					modifier = Modifier.width(120.dp),
				)
			}

			DropdownSelector(
				selected = newJobProfile,
				options = state.availableProfiles,
				onSelect = { newJobProfile = it },
				label = { Text(text = "Profile") },
				nullLabel = "None (global settings only)",
				showNullOption = true,
				itemLabel = { it },
				modifier = Modifier.fillMaxWidth(),
			)

			UnderlinedTextField(
				value = newJobLogFile,
				onValueChange = { newJobLogFile = it },
				label = { Text(text = "Log file (optional)") },
				placeholder = { Text(text = "/var/log/omnisign-renewal.log") },
				singleLine = true,
				modifier = Modifier.fillMaxWidth(),
			)

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					modifier = Modifier.weight(1f),
				) {
					Checkbox(
						checked = newJobNotify,
						onCheckedChange = { newJobNotify = it },
					)
					Text(text = "Desktop notifications", style = LumoTheme.typography.body2)
				}
				Button(
					text = "Create & assign",
					variant = ButtonVariant.Primary,
					enabled = newJobName.isNotBlank(),
					onClick = {
						val parsedBuffer = newJobBufferDays.toIntOrNull()
							?: ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS
						onCreateNew(
							RenewalJob(
								name = newJobName.trim(),
								globs = listOf(state.outputFile),
								renewalBufferDays = parsedBuffer,
								profile = newJobProfile,
								logFile = newJobLogFile.trim().ifBlank { null },
								notify = newJobNotify,
							)
						)
					},
				)
			}
		}
	}
}

/**
 * Footer with Close button.
 *
 * @param isAssigned Whether the file has already been assigned (or is already covered).
 * @param onDismiss Called when Close is clicked.
 */
@Composable
private fun RenewalOfferFooter(
	isAssigned: Boolean,
	onDismiss: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
	) {
		Button(
			text = "Close",
			variant = if (isAssigned) ButtonVariant.Primary else ButtonVariant.SecondaryOutlined,
			onClick = onDismiss,
		)
	}
}

