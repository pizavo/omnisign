package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.ButtonVariant
import cz.pizavo.omnisign.lumo.components.Surface
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField

/**
 * Modal dialog that prompts the user for a password or PIN.
 *
 * Rendered when the [cz.pizavo.omnisign.ui.platform.PasswordDialogController]
 * posts a [cz.pizavo.omnisign.ui.platform.PasswordDialogRequest]. The dialog blocks
 * the DSS background thread until the user confirms or cancels.
 *
 * @param title Dialog title text.
 * @param prompt Descriptive message explaining what the password is for.
 * @param onConfirm Called with the entered password when the user clicks Confirm.
 * @param onCancel Called when the user dismisses the dialog without entering a password.
 */
@Composable
fun PasswordDialog(
	title: String,
	prompt: String,
	onConfirm: (String) -> Unit,
	onCancel: () -> Unit,
) {
	var password by remember { mutableStateOf("") }

	Dialog(
		onDismissRequest = onCancel,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier.widthIn(min = 360.dp, max = 460.dp),
			shape = RoundedCornerShape(16.dp),
			color = LumoTheme.colors.surface,
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.padding(24.dp)) {
				Text(text = title, style = LumoTheme.typography.h3)

				Spacer(modifier = Modifier.height(12.dp))

				Text(
					text = prompt,
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)

				Spacer(modifier = Modifier.height(16.dp))

				UnderlinedTextField(
					value = password,
					onValueChange = { password = it },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation(),
					label = { Text("Password") },
					modifier = Modifier.fillMaxWidth(),
				)

				Spacer(modifier = Modifier.height(20.dp))

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
				) {
					Button(
						text = "Cancel",
						variant = ButtonVariant.SecondaryOutlined,
						onClick = onCancel,
					)
					Button(
						text = "Confirm",
						variant = ButtonVariant.Primary,
						enabled = password.isNotEmpty(),
						onClick = { onConfirm(password) },
					)
				}
			}
		}
	}
}

