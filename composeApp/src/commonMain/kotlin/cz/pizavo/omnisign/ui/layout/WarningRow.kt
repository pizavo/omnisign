package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_alert_warning
import org.jetbrains.compose.resources.painterResource

private val CERT_COUNT_PATTERN = Regex("""\d+ certificates?""")
private val TIMESTAMP_COUNT_PATTERN = Regex("""\d+ timestamps?""")

/**
 * Renders a single warning row with a warning icon and annotated summary text.
 *
 * When the warning has [AnnotatedWarning.affectedIds], the certificate or timestamp
 * count mention in the summary is rendered as underlined, clickable text with a hand
 * cursor. Clicking the underlined span opens a small dialog listing the affected DSS
 * identifiers with selectable text so the user can copy them.
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
		if (warning.affectedIds.isNotEmpty()) {
			AnnotatedWarningText(warning)
		} else {
			Text(
				text = warning.summary,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.warning,
			)
		}
	}
}

/**
 * Renders the warning summary as a single flowing text with the entity count span
 * as an underlined, clickable link. Clicking the link opens a dialog listing the
 * affected entity identifiers with selectable text.
 *
 * Uses [LinkAnnotation.Clickable] so that the hand cursor is shown automatically
 * when hovering over the underlined portion, and [BasicText] handles click detection
 * on the annotated range.
 */
@Composable
private fun AnnotatedWarningText(warning: AnnotatedWarning) {
	var showDialog by remember { mutableStateOf(false) }
	
	val summary = warning.summary
	val countMatch = CERT_COUNT_PATTERN.find(summary)
		?: TIMESTAMP_COUNT_PATTERN.find(summary)
	
	val warningColor = LumoTheme.colors.warning
	val style = LumoTheme.typography.body2
	
	val annotatedString = buildAnnotatedString {
		if (countMatch != null) {
			withStyle(SpanStyle(color = warningColor)) {
				append(summary.substring(0, countMatch.range.first))
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
				append(countMatch.value)
			}
			withStyle(SpanStyle(color = warningColor)) {
				append(summary.substring(countMatch.range.last + 1))
			}
		} else {
			withStyle(SpanStyle(color = warningColor)) {
				append(summary)
			}
		}
	}
	
	BasicText(
		text = annotatedString,
		style = style,
	)
	
	if (showDialog) {
		val entityLabel = if (warning.affectedIds.any { it.startsWith("T-") })
			"Affected Timestamps"
		else
			"Affected Certificates"
		
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
 * All text is wrapped in a [SelectionContainer] so the user can select and copy
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
				SelectionContainer {
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
					text = "Close",
					onClick = onDismiss,
					modifier = Modifier.align(Alignment.End),
				)
			}
		}
	}
}





