package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig

/**
 * Mutable-friendly UI state for the profile edit form.
 *
 * All fields mirror [ProfileConfig] but use primitive/nullable types suitable
 * for two-way data binding with Compose text fields and selectors.
 *
 * @property profileName The immutable profile name (displayed as a header, not editable).
 * @property description Free-text description of the profile.
 * @property hashAlgorithm Selected hash algorithm override, or `null` to inherit from global.
 * @property encryptionAlgorithm Selected encryption algorithm override, or `null` to inherit.
 * @property signatureLevel Selected PAdES signature level override, or `null` to inherit.
 * @property timestampEnabled Whether the timestamp server section is enabled.
 * @property timestampUrl TSA endpoint URL.
 * @property timestampUsername HTTP Basic auth username for the TSA.
 * @property timestampPassword HTTP Basic auth password for the current edit session.
 * @property hasStoredPassword Whether a password is already persisted in the OS credential store.
 * @property timestampTimeout HTTP request timeout in milliseconds, stored as a string for the text field.
 * @property disabledHashAlgorithms Hash algorithms disabled by this profile.
 * @property disabledEncryptionAlgorithms Encryption algorithms disabled by this profile.
 * @property saving Whether a save operation is currently in progress.
 * @property error Human-readable error message from the last failed operation, or `null`.
 */
data class ProfileEditState(
    val profileName: String,
    val description: String = "",
    val hashAlgorithm: HashAlgorithm? = null,
    val encryptionAlgorithm: EncryptionAlgorithm? = null,
    val signatureLevel: SignatureLevel? = null,
    val timestampEnabled: Boolean = false,
    val timestampUrl: String = "",
    val timestampUsername: String = "",
    val timestampPassword: String = "",
    val hasStoredPassword: Boolean = false,
    val timestampTimeout: String = "30000",
    val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
    val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null,
) {

    /**
     * Convert this UI state back into a persistable [ProfileConfig].
     *
     * The [timestampPassword] is intentionally **not** included in the returned config
     * because passwords are persisted separately through the OS credential store.
     * The [TimestampServerConfig.credentialKey] is set to the username when a password
     * has been entered or was already stored.
     */
    fun toProfileConfig(): ProfileConfig = ProfileConfig(
        name = profileName,
        description = description.ifBlank { null },
        hashAlgorithm = hashAlgorithm,
        encryptionAlgorithm = encryptionAlgorithm,
        signatureLevel = signatureLevel,
        timestampServer = if (timestampEnabled && timestampUrl.isNotBlank()) {
            val effectiveUsername = timestampUsername.ifBlank { null }
            val hasPassword = timestampPassword.isNotEmpty() || hasStoredPassword
            TimestampServerConfig(
                url = timestampUrl.trim(),
                username = effectiveUsername,
                credentialKey = if (hasPassword && effectiveUsername != null) effectiveUsername else null,
                timeout = timestampTimeout.toIntOrNull() ?: 30000,
            )
        } else {
            null
        },
        disabledHashAlgorithms = disabledHashAlgorithms,
        disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
    )

    companion object {

        /**
         * Build a [ProfileEditState] from an existing [ProfileConfig].
         *
         * @param profile The source profile configuration.
         * @param hasStoredPassword Whether a password is already persisted in the credential store.
         * @return A new edit state pre-populated with the profile's values.
         */
        fun from(profile: ProfileConfig, hasStoredPassword: Boolean = false): ProfileEditState = ProfileEditState(
            profileName = profile.name,
            description = profile.description.orEmpty(),
            hashAlgorithm = profile.hashAlgorithm,
            encryptionAlgorithm = profile.encryptionAlgorithm,
            signatureLevel = profile.signatureLevel,
            timestampEnabled = profile.timestampServer != null,
            timestampUrl = profile.timestampServer?.url.orEmpty(),
            timestampUsername = profile.timestampServer?.username.orEmpty(),
            timestampPassword = "",
            hasStoredPassword = hasStoredPassword,
            timestampTimeout = (profile.timestampServer?.timeout ?: 30000).toString(),
            disabledHashAlgorithms = profile.disabledHashAlgorithms,
            disabledEncryptionAlgorithms = profile.disabledEncryptionAlgorithms,
        )
    }
}

