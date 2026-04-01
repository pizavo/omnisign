package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.ButtonVariant
import cz.pizavo.omnisign.lumo.components.Checkbox
import cz.pizavo.omnisign.lumo.components.Chip
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Settings section for managing [RenewalJob] entries.
 *
 * Displays a list of existing renewal jobs with remove buttons, followed by
 * an inline form for adding new jobs. Changes are accumulated locally in
 * [GlobalConfigEditState.renewalJobs] and only persisted when the settings
 * dialog's `Save` button is clicked.
 *
 * @param state Current global config edit state containing the renewal jobs list.
 * @param onFieldChange Called with a transform to update the edit state.
 */
@Composable
fun RenewalJobsSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	if (state.renewalJobs.isEmpty()) {
		Text(
			text = "No renewal jobs configured.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	} else {
		state.renewalJobs.forEachIndexed { index, job ->
			RenewalJobRow(
				job = job,
				onRemove = {
					onFieldChange {
						it.copy(renewalJobs = it.renewalJobs.toMutableList().apply { removeAt(index) })
					}
				},
			)
			if (index < state.renewalJobs.lastIndex) {
				Spacer(modifier = Modifier.height(8.dp))
			}
		}
	}

	Spacer(modifier = Modifier.height(12.dp))

	RenewalJobAddForm(
		availableProfiles = state.availableProfiles,
		activeProfile = state.activeProfile,
		error = state.renewalJobAddError,
		onClearError = { onFieldChange { it.copy(renewalJobAddError = null) } },
		onError = { message -> onFieldChange { it.copy(renewalJobAddError = message) } },
		onAdd = { job ->
			onFieldChange { current ->
				if (current.renewalJobs.any { it.name == job.name }) {
					current.copy(renewalJobAddError = "A renewal job named '${job.name}' already exists.")
				} else {
					current.copy(
						renewalJobs = current.renewalJobs + job,
						renewalJobAddError = null,
					)
				}
			}
		},
	)
}

/**
 * Single row displaying a configured [RenewalJob] with metadata chips and a remove button.
 *
 * @param job The renewal job to display.
 * @param onRemove Callback invoked when the user clicks the remove button.
 */
@Composable
private fun RenewalJobRow(
	job: RenewalJob,
	onRemove: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.Top,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(text = job.name, style = LumoTheme.typography.label1)
				val profileName = job.profile
				if (profileName != null) {
					Chip(
						label = {
							Text(
								text = profileName,
								style = LumoTheme.typography.body2,
							)
						},
						selected = false,
						enabled = false,
						onClick = {},
					)
				}
			}
			job.globs.forEach { glob ->
				Text(
					text = glob,
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
			}
			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = "Buffer: ${job.renewalBufferDays} days",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
				Text(
					text = if (job.notify) "Notify: on" else "Notify: off",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
			}
			if (job.logFile != null) {
				Text(
					text = "Log: ${job.logFile}",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
			}
		}
		IconButton(
			variant = IconButtonVariant.Ghost,
			onClick = onRemove,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = "Remove ${job.name}",
				modifier = Modifier.size(16.dp),
			)
		}
	}
}

/**
 * Inline form for adding a new [RenewalJob].
 *
 * Provides fields for name, glob patterns (comma-separated), buffer days,
 * profile selection, log file path, and notification toggle.
 *
 * The profile dropdown pre-selects the currently active profile so that new
 * jobs inherit the active TSA/revocation settings by default. Selecting
 * "None (global settings only)" stores `null`, which bypasses profile
 * resolution entirely and uses only the global configuration.
 *
 * @param availableProfiles Profile names available for the profile dropdown.
 * @param activeProfile The currently active profile name, pre-selected by default.
 * @param error Human-readable error from the last failed addition attempt, or `null`.
 * @param onClearError Called to dismiss [error] when the user starts a new interaction.
 * @param onError Called with a human-readable message when validation fails.
 * @param onAdd Called with the constructed [RenewalJob] when the user clicks Add.
 */
@Composable
private fun RenewalJobAddForm(
	availableProfiles: List<String>,
	activeProfile: String?,
	error: String?,
	onClearError: () -> Unit,
	onError: (String) -> Unit,
	onAdd: (RenewalJob) -> Unit,
) {
	var name by remember { mutableStateOf("") }
	var globs by remember { mutableStateOf("") }
	var bufferDays by remember { mutableStateOf(ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS.toString()) }
	var profile by remember { mutableStateOf(activeProfile) }
	var logFile by remember { mutableStateOf("") }
	var notify by remember { mutableStateOf(true) }

	if (error != null) {
		Text(
			text = error,
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.error,
		)
		Spacer(modifier = Modifier.height(4.dp))
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = name,
			onValueChange = {
				name = it
				onClearError()
			},
			label = { Text(text = "Name") },
			placeholder = { Text(text = "Job name") },
			singleLine = true,
			modifier = Modifier.weight(1f),
		)
		UnderlinedTextField(
			value = globs,
			onValueChange = {
				globs = it
				onClearError()
			},
			label = { Text(text = "Glob patterns (comma-separated)") },
			placeholder = { Text(text = "/docs/**/*.pdf, /archive/*.pdf") },
			singleLine = true,
			modifier = Modifier.weight(2f),
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = bufferDays,
			onValueChange = {
				bufferDays = it
				onClearError()
			},
			label = { Text(text = "Buffer days") },
			placeholder = { Text(text = "${ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS}") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.width(120.dp),
		)
		DropdownSelector(
			selected = profile,
			options = availableProfiles,
			onSelect = {
				profile = it
				onClearError()
			},
			label = { Text(text = "Profile") },
			nullLabel = "None (global settings only)",
			showNullOption = true,
			itemLabel = { it },
			modifier = Modifier.weight(1f),
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = logFile,
			onValueChange = {
				logFile = it
				onClearError()
			},
			label = { Text(text = "Log file (optional)") },
			placeholder = { Text(text = "/var/log/omnisign-renewal.log") },
			singleLine = true,
			modifier = Modifier.weight(1f),
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

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
				checked = notify,
				onCheckedChange = { notify = it },
			)
			Text(text = "Desktop notifications", style = LumoTheme.typography.body2)
		}
		Button(
			text = "Add",
			variant = ButtonVariant.PrimaryOutlined,
			enabled = name.isNotBlank() && globs.isNotBlank(),
			onClick = {
				val trimmedName = name.trim()
				val parsedGlobs = globs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
				val parsedBuffer = bufferDays.toIntOrNull()

				if (parsedGlobs.isEmpty()) {
					onError("At least one glob pattern is required.")
					return@Button
				}
				if (parsedBuffer == null || parsedBuffer <= 0) {
					onError("Buffer days must be a positive integer.")
					return@Button
				}

				onAdd(
					RenewalJob(
						name = trimmedName,
						globs = parsedGlobs,
						renewalBufferDays = parsedBuffer,
						profile = profile,
						logFile = logFile.trim().ifBlank { null },
						notify = notify,
					)
				)
				name = ""
				globs = ""
				bufferDays = ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS.toString()
				profile = activeProfile
				logFile = ""
				notify = true
			},
		)
	}
}



