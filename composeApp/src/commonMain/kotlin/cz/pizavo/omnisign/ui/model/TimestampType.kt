package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Timestamp operation types presented to the user in the extension dialog.
 *
 * Each type maps to a PAdES target [SignatureLevel] used when invoking the
 * DSS extension operation.
 *
 * @property targetLevel The PAdES level that DSS should extend the document to.
 */
enum class TimestampType(val targetLevel: SignatureLevel) {

	/**
	 * Add a signature timestamp and embed revocation data (CRL/OCSP).
	 *
	 * Extends the document to PAdES BASELINE-LT. If revocation data
	 * cannot be obtained, the user may be offered a fallback to BASELINE-T.
	 */
	SIGNATURE_TIMESTAMP(SignatureLevel.PADES_BASELINE_LT),

	/**
	 * Add an archival document timestamp.
	 *
	 * Extends the document to PAdES BASELINE-LTA (or renews an existing
	 * LTA document). Always available.
	 */
	ARCHIVAL_TIMESTAMP(SignatureLevel.PADES_BASELINE_LTA);

	/**
	 * Human-readable label displayed in the dropdown, resolved in the current locale.
	 */
	@Composable
	fun label(): String = when (this) {
		SIGNATURE_TIMESTAMP -> stringResource(Res.string.timestamp_type_signature)
		ARCHIVAL_TIMESTAMP -> stringResource(Res.string.timestamp_type_archival)
	}
}
