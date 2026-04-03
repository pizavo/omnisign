package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.ButtonVariant
import cz.pizavo.omnisign.lumo.components.Chip
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_folder
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Reusable section for listing, adding, and removing custom ETSI Trusted List sources.
 *
 * Displays a list of existing [CustomTrustedListConfig] entries with source and optional
 * signing certificate badge, followed by an inline form for adding new entries.
 * The component is agnostic of storage scope — callers wire it into the global settings
 * dialog or a profile edit panel.
 *
 * @param trustedLists The current list of custom trusted list sources to display.
 * @param onAdd Called with a newly constructed [CustomTrustedListConfig] to append.
 * @param onRemove Called with the index of the entry to remove.
 * @param addError Human-readable error from the last failed addition attempt, or `null`.
 * @param onClearError Called to dismiss [addError] when the user starts a new interaction.
 * @param onError Called with a human-readable message when adding a trusted list fails validation.
 * @param onBuild Called when the user clicks "Build Custom TL". When `null` the button is hidden
 *   (e.g., on Wasm where the compiler is unavailable).
 */
@Composable
fun CustomTrustedListsSection(
	trustedLists: List<CustomTrustedListConfig>,
	onAdd: (CustomTrustedListConfig) -> Unit,
	onRemove: (Int) -> Unit,
	addError: String? = null,
	onClearError: () -> Unit = {},
	onError: (String) -> Unit = {},
	onBuild: (() -> Unit)? = null,
) {
	if (trustedLists.isEmpty()) {
		Text(
			text = "No custom trusted lists registered.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	} else {
		trustedLists.forEachIndexed { index, tl ->
			TrustedListRow(
				trustedList = tl,
				onRemove = { onRemove(index) },
			)
			if (index < trustedLists.lastIndex) {
				Spacer(modifier = Modifier.height(4.dp))
			}
		}
	}

	Spacer(modifier = Modifier.height(12.dp))

	TrustedListAddForm(
		onAdd = onAdd,
		error = addError,
		onClearError = onClearError,
		onError = onError,
	)

	if (onBuild != null) {
		Spacer(modifier = Modifier.height(12.dp))
		Button(
			text = "Build Custom TL",
			variant = ButtonVariant.SecondaryOutlined,
			onClick = onBuild,
		)
	}
}

/**
 * Single row displaying a registered custom trusted list with its source and a remove button.
 *
 * @param trustedList The trusted list entry to display.
 * @param onRemove Callback invoked when the user clicks the remove button.
 */
@Composable
private fun TrustedListRow(
	trustedList: CustomTrustedListConfig,
	onRemove: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(text = trustedList.name, style = LumoTheme.typography.label1)
				if (trustedList.signingCertPath != null) {
					Chip(
						label = {
							Text(
								text = "Signed",
								style = LumoTheme.typography.body2,
							)
						},
						selected = false,
						enabled = false,
						onClick = {},
					)
				}
			}
			Text(
				text = trustedList.source,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			if (trustedList.signingCertPath != null) {
				Text(
					text = "Cert: ${trustedList.signingCertPath}",
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
				contentDescription = "Remove ${trustedList.name}",
				modifier = Modifier.size(16.dp),
			)
		}
	}
}

/**
 * Inline form for adding a new custom trusted list source.
 *
 * Provides a name field, a source URL/path field, and an optional signing certificate
 * path field with a file picker. A warning is shown when the source does not start with
 * `https://` or `file:///`. On submission, the [onAdd] callback receives a constructed
 * [CustomTrustedListConfig].
 *
 * @param onAdd Called with the new [CustomTrustedListConfig] on successful validation.
 * @param error Human-readable error message from the last failed attempt, or `null`.
 * @param onClearError Called to clear [error] when the user starts a new interaction.
 * @param onError Called with a human-readable message when validation fails.
 */
@Composable
private fun TrustedListAddForm(
	onAdd: (CustomTrustedListConfig) -> Unit,
	error: String? = null,
	onClearError: () -> Unit = {},
	onError: (String) -> Unit = {},
) {
	var name by remember { mutableStateOf("") }
	var source by remember { mutableStateOf("") }
	var signingCertPath by remember { mutableStateOf("") }

	val certFilePicker = rememberFilePickerLauncher(
		type = FileKitType.File(extensions = listOf("pem", "der", "crt", "cer")),
	) { file: PlatformFile? ->
		if (file != null) {
			signingCertPath = platformFilePath(file) ?: file.name
			onClearError()
		}
	}

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
			placeholder = { Text(text = "Label") },
			singleLine = true,
			modifier = Modifier.weight(1f),
		)
		UnderlinedTextField(
			value = source,
			onValueChange = {
				source = it
				onClearError()
			},
			label = { Text(text = "Source (URL or file path)") },
			placeholder = { Text(text = "https://… or file:///…") },
			singleLine = true,
			modifier = Modifier.weight(2f),
		)
	}

	val sourceWarning = source.isNotBlank() &&
			!source.startsWith("https://") &&
			!source.startsWith("file:///")

	if (sourceWarning) {
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = "⚠ Source should start with https:// or file:/// — other schemes may not be supported by DSS.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.warning,
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = signingCertPath,
			onValueChange = {
				signingCertPath = it
				onClearError()
			},
			label = { Text(text = "Signing certificate (optional)") },
			placeholder = { Text(text = "/path/to/tl-signer.pem") },
			singleLine = true,
			modifier = Modifier.weight(1f),
			trailingIcon = {
				TooltipBox(
					tooltip = { Tooltip { Text(text = "Browse") } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { certFilePicker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_folder),
							contentDescription = "Browse for signing certificate",
							modifier = Modifier.size(18.dp),
						)
					}
				}
			},
		)
		Button(
			text = "Add",
			variant = ButtonVariant.PrimaryOutlined,
			enabled = name.isNotBlank() && source.isNotBlank(),
			onClick = {
				val trimmedName = name.trim()
				val trimmedSource = source.trim()
				val trimmedCert = signingCertPath.trim().ifBlank { null }

				onAdd(
					CustomTrustedListConfig(
						name = trimmedName,
						source = trimmedSource,
						signingCertPath = trimmedCert,
					)
				)
				name = ""
				source = ""
				signingCertPath = ""
			},
		)
	}
}



