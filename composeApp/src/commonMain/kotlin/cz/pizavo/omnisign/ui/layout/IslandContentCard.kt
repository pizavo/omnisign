package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.card.Card
import cz.pizavo.omnisign.lumo.components.card.CardDefaults

/**
 * Central content area of the island layout.
 *
 * Rendered as a flat Lumo [Card] with rounded corners that fills the remaining
 * horizontal space between the two sidebars. No elevation or shadow is applied
 * so the card sits flush with the background. In the future this card will host
 * a PDF document viewer.
 *
 * @param modifier Optional [Modifier] — typically includes `Modifier.weight(1f)` from
 *   the parent [Row][androidx.compose.foundation.layout.Row].
 * @param content Slot for the card body; defaults to a centered placeholder label.
 */
@Composable
fun IslandContentCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = { DefaultContentPlaceholder() },
) {
    Card(
        modifier = modifier.semantics { contentDescription = "Main document area" },
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        content = content,
    )
}

/**
 * Placeholder shown inside [IslandContentCard] before a real document viewer is wired.
 */
@Composable
private fun DefaultContentPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Document Viewer",
            style = LumoTheme.typography.h2,
            color = LumoTheme.colors.textSecondary,
        )
    }
}
