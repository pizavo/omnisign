package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** An error from adding a trusted certificate in the global scope; UI resolves via [resolve]. */
sealed interface TrustedCertAddError {
	/** The certificate is already trusted in the global scope. */
	data object AlreadyTrusted : TrustedCertAddError

	/** Verbatim domain error text. */
	data class Domain(val message: String) : TrustedCertAddError
}

/**
 * Resolve this trusted-certificate-add error to its localized, human-readable message.
 */
@Composable
fun TrustedCertAddError.resolve(): String = when (this) {
	TrustedCertAddError.AlreadyTrusted -> stringResource(Res.string.settings_certadd_already_trusted)
	is TrustedCertAddError.Domain -> message
}
