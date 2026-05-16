package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.service.Pkcs11DiagnosticSnapshot
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_alert_info
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Modal dialog that surfaces a [Pkcs11DiagnosticSnapshot] for end-user troubleshooting.
 *
 * Shown when the user clicks "Show diagnostic info" in the sign dialog's empty-state banner.
 * The content is intentionally read-only and explanatory — the goal is to bridge the gap
 * between "my token isn't showing up" and the concrete action the user can take (add the
 * path via Global Settings, drop the library into the drop directory, plug in the reader,
 * etc.) without forcing them to read source code or run a CLI command.
 *
 * Three sections, each can render an empty state:
 *
 * 1. **PC/SC readers** — what the OS reports as connected card readers, including ATR hex
 *    when a card is inserted.  An empty list usually means the smart-card service isn't
 *    running or no compatible reader is plugged in.
 * 2. **Candidate PKCS#11 libraries** — what discovery would attempt to probe right now,
 *    merged across OS-native sources, the drop directory, and user-supplied entries.
 * 3. **Drop directory** — the absolute path where the user can copy a PKCS#11 library file
 *    for automatic pickup, when their middleware isn't discoverable through Calais / p11-kit.
 *
 * @param snapshot The current diagnostic snapshot to render.
 * @param onDismiss Called when the user dismisses the dialog.
 * @param onOpenPkcs11Settings Optional callback invoked when the user activates the
 *   inline "Global Settings → PKCS#11 Libraries" link in the candidate-libraries
 *   empty-state hint.  When `null`, the phrase is rendered as plain text.
 * @param onOpenDropDirectory Optional callback invoked when the user activates the
 *   inline "the drop directory" link in the candidate-libraries empty-state hint
 *   or clicks the rendered path inside the "Drop directory" section — used to
 *   reveal the canonical drop folder in the host OS file manager so a downloaded
 *   PKCS#11 library can be dropped in without copy-pasting the path.  When `null`,
 *   both occurrences fall back to plain text.
 */
@Composable
fun Pkcs11DiagnosticDialog(
	snapshot: Pkcs11DiagnosticSnapshot,
	onDismiss: () -> Unit,
	onOpenPkcs11Settings: (() -> Unit)? = null,
	onOpenDropDirectory: (() -> Unit)? = null,
) {
	Dialog(
		onDismissRequest = onDismiss,
		modifier = Modifier
			.widthIn(min = 480.dp, max = 640.dp)
			.heightIn(min = 320.dp, max = 600.dp),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			DiagnosticHeader(onDismiss)
			HorizontalDivider()
			
			VerticalScrollableColumn(
				modifier = Modifier.weight(1f),
				contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
				verticalArrangement = Arrangement.spacedBy(20.dp),
			) {
				DiagnosticSection(title = "PC/SC readers") {
					if (snapshot.pcscReaders.isEmpty()) {
						EmptySectionText(
							"No PC/SC readers detected. The platform smart-card service " +
									"may be stopped, or no compatible reader is connected."
						)
					} else {
						snapshot.pcscReaders.forEach { reader ->
							PcscReaderRow(reader)
						}
					}
				}
				
				DiagnosticSection(title = "Candidate PKCS#11 libraries") {
					if (snapshot.candidateLibraries.isEmpty()) {
						CandidateLibrariesEmptyHint(
							onOpenPkcs11Settings = onOpenPkcs11Settings,
							onOpenDropDirectory = onOpenDropDirectory,
						)
					} else {
						snapshot.candidateLibraries.forEach { candidate ->
							CandidateLibraryRow(candidate)
						}
					}
				}

				val dropDir = snapshot.dropDirectoryPath
				if (dropDir != null) {
					DiagnosticSection(title = "Drop directory") {
						Text(
							text = "Copy a PKCS#11 library file (.dll / .so / .dylib) into this " +
									"directory to have discovery pick it up automatically:",
							style = LumoTheme.typography.body2,
							color = LumoTheme.colors.textSecondary,
						)
						DropDirectoryPathText(path = dropDir, onOpen = onOpenDropDirectory)
					}
				}
			}
			
			HorizontalDivider()
			
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 10.dp),
				horizontalArrangement = Arrangement.End,
			) {
				Button(
					variant = ButtonVariant.Primary,
					text = "Close",
					onClick = onDismiss,
				)
			}
		}
	}
}

/**
 * Header row with the dialog title and close button — mirrors the sign-dialog header layout
 * for visual consistency.
 */
@Composable
private fun DiagnosticHeader(onClose: () -> Unit) {
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
			Icon(
				painter = painterResource(Res.drawable.icon_alert_info),
				contentDescription = null,
				modifier = Modifier.size(18.dp),
				tint = LumoTheme.colors.textSecondary,
			)
			Text(text = "PKCS#11 diagnostic", style = LumoTheme.typography.h3)
		}
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
 * Sectioned content block with a heading and a content slot.
 */
@Composable
private fun DiagnosticSection(title: String, content: @Composable ColumnScope.() -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(
			text = title,
			style = LumoTheme.typography.body1,
			fontWeight = FontWeight.SemiBold,
			color = LumoTheme.colors.text,
		)
		content()
	}
}

/**
 * Italicised explanatory text shown when a section has no entries.
 */
@Composable
private fun EmptySectionText(text: String) {
	Text(
		text = text,
		style = LumoTheme.typography.body2,
		color = LumoTheme.colors.textSecondary,
	)
}

/**
 * Empty-state hint for the candidate-libraries section.
 *
 * Two phrases can independently be rendered as clickable links depending on
 * which callbacks are wired:
 *
 * - "Global Settings → PKCS#11 Libraries" — driven by [onOpenPkcs11Settings],
 *   typically used to open the settings dialog at the PKCS#11 Libraries
 *   category.
 * - "the drop directory" — driven by [onOpenDropDirectory], typically used to
 *   reveal the canonical drop folder in the host OS file manager so a
 *   downloaded library file can be placed there directly.
 *
 * When both callbacks are `null` the entire sentence is rendered via
 * [EmptySectionText] as plain text, matching the rest of the dialog's empty
 * states.  When at least one callback is non-null the sentence is built as an
 * [androidx.compose.ui.text.AnnotatedString] so the visible style of unlinked
 * spans stays consistent with the linked ones.
 */
@Composable
private fun CandidateLibrariesEmptyHint(
	onOpenPkcs11Settings: (() -> Unit)?,
	onOpenDropDirectory: (() -> Unit)?,
) {
	if (onOpenPkcs11Settings == null && onOpenDropDirectory == null) {
		EmptySectionText(
			"Discovery found no candidate libraries. Either none of the " +
					"OS-native sources advertise a PKCS#11 module, or you " +
					"haven't added a path under Global Settings → PKCS#11 " +
					"Libraries, or dropped a library into the drop directory."
		)
		return
	}

	val linkStyles = TextLinkStyles(
		style = SpanStyle(
			color = LumoTheme.colors.primary,
			textDecoration = TextDecoration.Underline,
		),
	)
	val annotated = buildAnnotatedString {
		append(
			"Discovery found no candidate libraries. Either none of the " +
					"OS-native sources advertise a PKCS#11 module, or you " +
					"haven't added a path under "
		)
		if (onOpenPkcs11Settings != null) {
			withLink(
				LinkAnnotation.Clickable(
					tag = "pkcs11-settings",
					styles = linkStyles,
					linkInteractionListener = { onOpenPkcs11Settings() },
				),
			) {
				append("Global Settings → PKCS#11 Libraries")
			}
		} else {
			append("Global Settings → PKCS#11 Libraries")
		}
		append(", or dropped a library into ")
		if (onOpenDropDirectory != null) {
			withLink(
				LinkAnnotation.Clickable(
					tag = "drop-directory",
					styles = linkStyles,
					linkInteractionListener = { onOpenDropDirectory() },
				),
			) {
				append("the drop directory")
			}
		} else {
			append("the drop directory")
		}
		append(".")
	}
	Text(
		text = annotated,
		style = LumoTheme.typography.body2,
		color = LumoTheme.colors.textSecondary,
	)
}

/**
 * Renders the drop-directory path either as plain medium-weight text or, when
 * [onOpen] is non-null, as an underlined clickable link that invokes the
 * callback (typically used to reveal the folder in the host OS file manager).
 *
 * Kept as a separate helper so the empty-state hint and the dedicated "Drop
 * directory" section share identical typography rules for the path token.
 */
@Composable
private fun DropDirectoryPathText(path: String, onOpen: (() -> Unit)?) {
	if (onOpen == null) {
		Text(
			text = path,
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.text,
			fontWeight = FontWeight.Medium,
		)
		return
	}
	val annotated = buildAnnotatedString {
		withLink(
			LinkAnnotation.Clickable(
				tag = "drop-directory-path",
				styles = TextLinkStyles(
					style = SpanStyle(
						color = LumoTheme.colors.primary,
						textDecoration = TextDecoration.Underline,
						fontWeight = FontWeight.Medium,
					),
				),
				linkInteractionListener = { onOpen() },
			),
		) {
			append(path)
		}
	}
	Text(
		text = annotated,
		style = LumoTheme.typography.body2,
		color = LumoTheme.colors.text,
	)
}

/**
 * One PC/SC reader row: name, card-present badge, and ATR hex when available.
 */
@Composable
private fun PcscReaderRow(reader: Pkcs11DiagnosticSnapshot.PcscReaderInfo) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = reader.name,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.text,
				fontWeight = FontWeight.Medium,
			)
			Text(
				text = if (reader.cardPresent) "card present" else "empty",
				style = LumoTheme.typography.body2,
				color = if (reader.cardPresent) LumoTheme.colors.success else LumoTheme.colors.textSecondary,
			)
		}
		if (reader.atrHex != null) {
			Text(
				text = "ATR: ${reader.atrHex}",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
		}
	}
}

/**
 * One candidate PKCS#11 library row: vendor name + absolute path.
 */
@Composable
private fun CandidateLibraryRow(candidate: Pkcs11DiagnosticSnapshot.CandidateLibrary) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(
			text = candidate.displayName,
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.text,
			fontWeight = FontWeight.Medium,
		)
		Text(
			text = candidate.path,
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	}
}
