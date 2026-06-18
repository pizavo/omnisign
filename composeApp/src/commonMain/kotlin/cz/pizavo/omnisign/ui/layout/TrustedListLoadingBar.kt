package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.progressindicators.LinearProgressIndicator
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A "Loading trusted lists…" caption above a [LinearProgressIndicator] for an in-flight trusted-list
 * refresh, shared by the Settings refresh row and the Signature panel.
 *
 * The bar is **indeterminate** while [TrustedListLoadProgress.fraction] is `null` — no lists are
 * scheduled yet (e.g. the EU LOTL is still being fetched) — and switches to a **determinate**
 * "loaded of total" bar once the lists are scheduled.
 *
 * @param progress Current member-state load progress.
 * @param modifier Optional [Modifier] for the wrapping column.
 */
@Composable
fun TrustedListLoadingBar(
    progress: TrustedListLoadProgress,
    modifier: Modifier = Modifier,
) {
    val fraction = progress.fraction
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (fraction == null) {
                stringResource(Res.string.tlloadingbar_loading)
            } else {
                "Loading trusted lists… ${progress.loaded} of ${progress.total}"
            },
            style = LumoTheme.typography.body3,
            color = LumoTheme.colors.textSecondary,
        )
        if (fraction == null) {
            LinearProgressIndicator()
        } else {
            LinearProgressIndicator(progress = fraction)
        }
    }
}
