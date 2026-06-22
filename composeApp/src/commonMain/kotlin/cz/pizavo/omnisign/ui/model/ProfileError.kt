package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** An error from a profile management operation; UI resolves via [resolve]. */
sealed interface ProfileError {
	/** The new profile name was left blank. */
	data object NameRequired : ProfileError

	/** A domain error carried as localizable [text] (resolved to the active locale by the UI). */
	data class Domain(val text: LocalizableText) : ProfileError
}

/**
 * Resolve this profile error to its localized, human-readable message.
 */
@Composable
fun ProfileError.resolve(): String = when (this) {
	ProfileError.NameRequired -> stringResource(Res.string.profile_error_name_required)
	is ProfileError.Domain -> text.localized()
}
