package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** An error from a profile management operation; UI resolves via [resolve]. */
sealed interface ProfileError {
	/** The new profile name was left blank. */
	data object NameRequired : ProfileError

	/** Verbatim domain error text. */
	data class Domain(val message: String) : ProfileError
}

/**
 * Resolve this profile error to its localized, human-readable message.
 */
@Composable
fun ProfileError.resolve(): String = when (this) {
	ProfileError.NameRequired -> stringResource(Res.string.profile_error_name_required)
	is ProfileError.Domain -> message
}
