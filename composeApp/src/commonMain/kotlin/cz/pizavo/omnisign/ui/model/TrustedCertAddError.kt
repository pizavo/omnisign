package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** An error from adding a trusted certificate in the global or profile scope; UI resolves via [resolve]. */
sealed interface TrustedCertAddError {
	/** The certificate is already trusted in the global scope. */
	data object AlreadyTrusted : TrustedCertAddError

	/** The certificate is already trusted in the active profile's scope. */
	data object AlreadyTrustedInProfile : TrustedCertAddError

	/** A domain error carried as localizable [text] (resolved to the active locale by the UI). */
	data class Domain(val text: LocalizableText) : TrustedCertAddError
}

/**
 * Resolve this trusted-certificate-add error to its localized, human-readable message.
 */
@Composable
fun TrustedCertAddError.resolve(): String = when (this) {
	TrustedCertAddError.AlreadyTrusted -> stringResource(Res.string.settings_certadd_already_trusted)
	TrustedCertAddError.AlreadyTrustedInProfile -> stringResource(Res.string.profile_certadd_already_trusted)
	is TrustedCertAddError.Domain -> text.localized()
}
