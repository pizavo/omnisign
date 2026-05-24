package cz.pizavo.omnisign.domain.model.trust

/**
 * A trust scope: the bucket a directly-trusted certificate is referenced from.
 *
 * Trust is partitioned into one [Global] scope plus one [Profile] scope per named profile.
 * The resolved trust set for a profile is the union of [Global] and that [Profile].
 */
sealed interface TrustScope {
	/**
	 * The global scope, applied to every operation regardless of the active profile.
	 */
	data object Global : TrustScope

	/**
	 * The scope of a single named profile.
	 *
	 * @property name The profile name.
	 */
	data class Profile(val name: String) : TrustScope

	companion object {
		/**
		 * Map an optional profile name to a scope: a [Profile] when [profileName] is non-null,
		 * otherwise [Global].
		 */
		fun of(profileName: String?): TrustScope = profileName?.let { Profile(it) } ?: Global
	}
}
