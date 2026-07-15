package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.ui.model.localizedCountPhrase
import cz.pizavo.omnisign.ui.model.localizedSummary
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Renders a single warning row with a warning icon and annotated summary text.
 *
 * The summary is wrapped in [SelectableContent] so it can be selected and copied. When the
 * warning has [AnnotatedWarning.affectedIds], the certificate or timestamp count mention is
 * rendered as an underlined, clickable span (which stays clickable inside the selection
 * scope); clicking it opens a small dialog listing the affected DSS identifiers, also
 * selectable, so the user can copy them.
 *
 * @param warning The annotated warning to display.
 * @param modifier Optional modifier for the row.
 */
@Composable
fun WarningRow(
	warning: AnnotatedWarning,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		verticalAlignment = Alignment.Top,
	) {
		Icon(
			painter = painterResource(Res.drawable.icon_alert_warning),
			contentDescription = null,
			modifier = Modifier.padding(top = 3.dp).size(14.dp),
			tint = LumoTheme.colors.warning,
		)
		SelectableContent {
			val summary = warning.localizedSummary()
			val countPhrase = warning.localizedCountPhrase()
			val countStart = countPhrase?.let { summary.indexOf(it) } ?: -1
			if (countPhrase != null && countStart >= 0) {
				AnnotatedWarningText(warning, summary, countStart, countPhrase)
			} else {
				Text(
					text = summary,
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.warning,
				)
			}
		}
	}
}

/**
 * Renders the warning [summary] as a single flowing text with the affected-entity count phrase —
 * [countPhrase], found at index [countStart] — as an underlined, clickable link. Clicking the link
 * opens a dialog listing the affected entity identifiers with selectable text.
 *
 * Uses [LinkAnnotation.Clickable] so that the hand cursor is shown automatically when hovering over
 * the underlined portion, and [BasicText] handles click detection on the annotated range.
 */
@Composable
private fun AnnotatedWarningText(
	warning: AnnotatedWarning,
	summary: String,
	countStart: Int,
	countPhrase: String,
) {
	var showDialog by remember { mutableStateOf(false) }

	val warningColor = LumoTheme.colors.warning
	val style = LumoTheme.typography.body2

	val annotatedString = buildAnnotatedString {
		withStyle(SpanStyle(color = warningColor)) {
			append(summary.substring(0, countStart))
		}
		withLink(
			LinkAnnotation.Clickable(
				tag = "ids",
				styles = TextLinkStyles(
					style = SpanStyle(
						color = warningColor,
						textDecoration = TextDecoration.Underline,
					),
				),
			) {
				showDialog = true
			},
		) {
			append(countPhrase)
		}
		withStyle(SpanStyle(color = warningColor)) {
			append(summary.substring(countStart + countPhrase.length))
		}
	}

	BasicText(
		text = annotatedString,
		style = style,
	)

	if (showDialog) {
		val entityLabel = if (warning.affectedIds.any { it.startsWith("T-") })
			stringResource(Res.string.warningrow_affected_timestamps)
		else
			stringResource(Res.string.warningrow_affected_certificates)
		
		AffectedEntitiesDialog(
			title = entityLabel,
			ids = warning.affectedIds,
			idNames = warning.idNames,
			onDismiss = { showDialog = false },
		)
	}
}

/**
 * Small dialog listing affected certificate or timestamp identifiers.
 *
 * When a human-readable name is available in [idNames] for a given ID, it is displayed
 * as the primary label with the raw DSS identifier shown underneath in secondary style.
 * All text is wrapped in a [SelectableContent] so the user can select and copy
 * identifiers for searching or troubleshooting purposes.
 *
 * @param title Dialog heading (e.g. "Affected Certificates").
 * @param ids Full DSS identifiers to display.
 * @param idNames Mapping from DSS identifier to human-readable name (e.g. subject CN).
 * @param onDismiss Called when the dialog is dismissed.
 */
@Composable
private fun AffectedEntitiesDialog(
	title: String,
	ids: List<String>,
	idNames: Map<String, String>,
	onDismiss: () -> Unit,
) {
	BasicAlertDialog(onDismissRequest = onDismiss) {
		Surface(
			shape = RoundedCornerShape(16.dp),
			color = LumoTheme.colors.surface,
			shadowElevation = 4.dp,
		) {
			Column(modifier = Modifier.padding(24.dp)) {
				Text(
					text = title,
					style = LumoTheme.typography.h4,
					color = LumoTheme.colors.text,
				)
				Spacer(Modifier.height(12.dp))
				SelectableContent {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						ids.forEach { id ->
							val name = idNames[id]
							if (name != null) {
								Column {
									Text(
										text = name,
										style = LumoTheme.typography.body2,
										color = LumoTheme.colors.text,
									)
								Text(
									text = id,
									style = LumoTheme.typography.body3,
									color = LumoTheme.colors.textSecondary,
								)
								}
							} else {
								Text(
									text = id,
									style = LumoTheme.typography.body2,
									color = LumoTheme.colors.text,
								)
							}
						}
					}
				}
				Spacer(Modifier.height(16.dp))
				Button(
					variant = ButtonVariant.Ghost,
					text = stringResource(Res.string.action_close),
					onClick = onDismiss,
					modifier = Modifier.align(Alignment.End),
				)
			}
		}
	}
}





