package cz.pizavo.omnisign.domain.model.error

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey

/**
 * Configuration-related errors.
 *
 * The case (subtype) is the stable category used for handling — the server maps each to an
 * HTTP status in `StatusPages.kt`. The specific user-facing message is carried as localizable
 * [LocalizableError.text], and the English [OperationError.message] is derived from it. Use the
 * [Companion] factories for the keyed (translatable) messages; reach for [LocalizableText.Literal]
 * only for messages that embed a CLI instruction, propagate library/exception text, or are
 * computed at runtime.
 */
sealed interface ConfigurationError : OperationError, LocalizableError {

	/** English rendering of [text]; the fallback for non-localizing consumers (CLI, server, logs). */
	override val message: String get() = text.english()

	/**
	 * Configuration could not be loaded.
	 */
	data class LoadFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ConfigurationError

	/**
	 * Configuration could not be saved.
	 */
	data class SaveFailed(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ConfigurationError

	/**
	 * Configuration validation failed.
	 */
	data class InvalidConfiguration(
		override val text: LocalizableText,
		override val details: String? = null,
		override val cause: Throwable? = null,
	) : ConfigurationError

	companion object {

		/** Loading the on-disk configuration failed for an unclassified reason. */
		fun loadFailed(details: String? = null, cause: Throwable? = null): LoadFailed =
			LoadFailed(LocalizableText.of(MessageKey.CONFIG_LOAD_FAILED), details, cause)

		/** Saving the on-disk configuration failed for an unclassified reason. */
		fun saveFailed(details: String? = null, cause: Throwable? = null): SaveFailed =
			SaveFailed(LocalizableText.of(MessageKey.CONFIG_SAVE_FAILED), details, cause)

		/** Serializing the configuration to the [format] text representation failed. */
		fun serializeFailed(format: String, details: String? = null, cause: Throwable? = null): SaveFailed =
			SaveFailed(LocalizableText.of(MessageKey.CONFIG_SERIALIZE_FAILED, format), details, cause)

		/** Deserializing the configuration from the [format] text representation failed. */
		fun deserializeFailed(format: String, details: String? = null, cause: Throwable? = null): LoadFailed =
			LoadFailed(LocalizableText.of(MessageKey.CONFIG_DESERIALIZE_FAILED, format), details, cause)

		/** Loading the configuration from the server over the API failed. */
		fun loadFromServerFailed(details: String? = null, cause: Throwable? = null): LoadFailed =
			LoadFailed(LocalizableText.of(MessageKey.CONFIG_LOAD_FROM_SERVER_FAILED), details, cause)

		/** Saving the configuration is not available on the web target. */
		fun saveNotSupportedOnWeb(details: String? = null): SaveFailed =
			SaveFailed(LocalizableText.of(MessageKey.CONFIG_SAVE_NOT_SUPPORTED_WEB), details)

		/** The server's signing configuration is fixed at startup and cannot be mutated at runtime. */
		fun serverReadOnly(details: String? = null): SaveFailed =
			SaveFailed(LocalizableText.of(MessageKey.CONFIG_SERVER_READ_ONLY), details)

		/** The trust store lacks the bytes for certificate [fingerprint] referenced by the archive. */
		fun missingCertificateBytes(fingerprint: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_MISSING_CERTIFICATE_BYTES, fingerprint))

		/** The archive references certificate [fingerprint] but its DER entry is absent. */
		fun missingDerEntry(fingerprint: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_MISSING_DER_ENTRY, fingerprint))

		/** The configuration archive does not contain a `config.*` entry. */
		fun archiveMissingConfigEntry(): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_ARCHIVE_MISSING_CONFIG_ENTRY))

		/** The configuration archive's config entry [entryName] has an unrecognized format. */
		fun archiveUnrecognizedFormat(entryName: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_ARCHIVE_UNRECOGNIZED_FORMAT, entryName))

		/** The configuration archive's trust manifest could not be parsed. */
		fun archiveCorruptManifest(details: String? = null): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_ARCHIVE_CORRUPT_MANIFEST), details)

		/** The configuration archive could not be read. */
		fun archiveUnreadable(details: String? = null): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_ARCHIVE_UNREADABLE), details)

		/** The default hash algorithm [algorithm] cannot be disabled while it is the default. */
		fun cannotDisableDefaultHash(algorithm: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_HASH, algorithm))

		/** The default encryption algorithm [algorithm] cannot be disabled while it is the default. */
		fun cannotDisableDefaultEncryption(algorithm: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_ENCRYPTION, algorithm))

		/** Profile [profileName] disables its own hash algorithm override [algorithm]. */
		fun profileDisablesOwnHash(profileName: String, algorithm: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_PROFILE_DISABLES_OWN_HASH, profileName, algorithm))

		/** Profile [profileName] disables its own encryption algorithm override [algorithm]. */
		fun profileDisablesOwnEncryption(profileName: String, algorithm: String): InvalidConfiguration =
			InvalidConfiguration(
				LocalizableText.of(MessageKey.CONFIG_PROFILE_DISABLES_OWN_ENCRYPTION, profileName, algorithm)
			)

		/** No profile named [profileName] exists. */
		fun profileNotFound(profileName: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_PROFILE_NOT_FOUND, profileName))

		/** No profile named [profileName] was found for a trusted-list operation. */
		fun noProfileNamed(profileName: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_NO_PROFILE_NAMED, profileName))

		/** No trusted list named [name] exists in the global scope. */
		fun trustedListNotFound(name: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_TRUSTED_LIST_NOT_FOUND, name))

		/** No trusted list named [name] exists in profile [profileName]. */
		fun trustedListNotFoundInProfile(name: String, profileName: String): InvalidConfiguration =
			InvalidConfiguration(
				LocalizableText.of(MessageKey.CONFIG_TRUSTED_LIST_NOT_FOUND_IN_PROFILE, name, profileName)
			)

		/** No TSP named [tspName] exists in draft [draftName]. */
		fun tspNotFound(tspName: String, draftName: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_TSP_NOT_FOUND, tspName, draftName))

		/** No service named [serviceName] exists under TSP [tspName]. */
		fun serviceNotFound(serviceName: String, tspName: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_SERVICE_NOT_FOUND, serviceName, tspName))

		/** No TL builder draft named [name] exists. */
		fun draftNotFound(name: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_DRAFT_NOT_FOUND, name))

		/** No renewal job named [name] exists. */
		fun renewalJobNotFound(name: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_RENEWAL_JOB_NOT_FOUND, name))

		/** No PKCS#11 library named [name] is registered. */
		fun pkcs11LibraryNotFound(name: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_PKCS11_LIBRARY_NOT_FOUND, name))

		/** No configuration serializer is registered for [format]. */
		fun noSerializerForFormat(format: String): InvalidConfiguration =
			InvalidConfiguration(LocalizableText.of(MessageKey.CONFIG_NO_SERIALIZER_FOR_FORMAT, format))
	}
}
