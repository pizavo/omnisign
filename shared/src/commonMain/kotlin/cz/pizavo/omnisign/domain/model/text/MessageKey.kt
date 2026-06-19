package cz.pizavo.omnisign.domain.model.text

import kotlinx.serialization.Serializable

/**
 * Stable, locale-independent identifier for a user-facing message.
 *
 * Each frontend resolves a key to its own locale: composeApp (desktop and web) via Compose
 * Resources, while the CLI, the server, and logs fall back to the bundled English templates
 * in [EnglishMessages]. The enum serializes by name, so a server can hand a key (plus its
 * arguments) to the web client and let the client translate.
 */
@Serializable
enum class MessageKey {
	/** Extension target level is not higher than the document's current level. */
	ARCHIVING_TARGET_LEVEL_NOT_HIGHER,

	/** Extension to the requested level needs a timestamp server, but none is configured (arg: target level). */
	ARCHIVING_TSA_REQUIRED,

	/** The input PDF is encrypted or password-protected, so in-place extension is impossible. */
	ARCHIVING_PDF_ENCRYPTED,

	/** The input is not a valid PDF or could not be parsed. */
	ARCHIVING_MALFORMED_PDF,

	/** Revocation information for the signature could not be obtained. */
	ARCHIVING_REVOCATION_INFO_FAILED,

	/** A document extension failed for an unclassified reason. */
	ARCHIVING_EXTENSION_FAILED,

	/** The referenced file does not exist (arg: file path). */
	ARCHIVING_FILE_NOT_FOUND,

	/** Renewal status could not be determined because a timestamp's signing certificate is unresolvable. */
	ARCHIVING_RENEWAL_STATUS_UNDETERMINABLE,

	/** The archival-renewal check failed for an unclassified reason. */
	ARCHIVING_RENEWAL_CHECK_FAILED,

	/** The document's timestamp state could not be inspected. */
	ARCHIVING_TIMESTAMP_INSPECT_FAILED,

	/** A server-delegated document extension failed (web target). */
	ARCHIVING_REMOTE_EXTENSION_FAILED,

	/** Archival-renewal checks are unavailable on the web target (no filesystem access). */
	ARCHIVING_WEB_RENEWAL_UNSUPPORTED,

	/** A server-delegated document inspection failed (web target). */
	ARCHIVING_REMOTE_INSPECT_FAILED,

	/** Token discovery failed unexpectedly. */
	SIGNING_DISCOVER_TOKENS_FAILED,

	/** The user cancelled the PIN prompt for a token (arg: token name). */
	SIGNING_PIN_ENTRY_CANCELLED,

	/** The DSS signing token could not be created from the selected certificate. */
	SIGNING_CREATE_TOKEN_FAILED,

	/** The keystore file does not exist (arg: file path). */
	SIGNING_FILE_NOT_FOUND,

	/** Certificates could not be loaded from a token (arg: token name). */
	SIGNING_LOAD_CERTS_FROM_TOKEN_FAILED,

	/** Listing the available certificates across discovered tokens failed unexpectedly. */
	SIGNING_LIST_CERTS_FAILED,

	/** The named token is not among the discovered tokens (arg: token id). */
	SIGNING_TOKEN_NOT_FOUND,

	/** Unlocking a previously locked token failed unexpectedly. */
	SIGNING_UNLOCK_TOKEN_FAILED,

	/** The user cancelled the password prompt for a PKCS#12 file. */
	SIGNING_PASSWORD_ENTRY_CANCELLED,

	/** Certificates could not be loaded from a PKCS#12 file. */
	SIGNING_LOAD_CERTS_FROM_FILE_FAILED,

	/** No certificate was available to sign with (no alias requested). */
	SIGNING_NO_CERT_FOUND,

	/** No certificate matching the requested alias was found (arg: alias). */
	SIGNING_NO_CERT_FOUND_FOR_ALIAS,

	/** Remote (server-delegated) signing failed. */
	SIGNING_REMOTE_SIGNING_FAILED,

	/** The signing operation failed for an unclassified reason. */
	SIGNING_SIGNING_FAILED,

	/** Listing certificates from the server failed. */
	SIGNING_LIST_CERTS_FROM_SERVER_FAILED,

	/** Token unlock is not available on the web target. */
	SIGNING_UNLOCK_NOT_SUPPORTED_WEB,

	/** Loading a PKCS#12 file is not available on the web target. */
	SIGNING_LOAD_FILE_NOT_SUPPORTED_WEB,

	/** Hash and encryption algorithms are incompatible (args: hash, encryption, compatible hashes). */
	SIGNING_HASH_ENCRYPTION_INCOMPATIBLE,

	/** The chosen hash algorithm is not supported by the Windows Certificate Store (arg: hash). */
	SIGNING_HASH_NOT_SUPPORTED_WINDOWS,

	/** Local (DSS-backed) document validation failed for an unclassified reason. */
	VALIDATION_VALIDATION_FAILED,

	/** Remote (server-delegated) document validation failed. */
	VALIDATION_REMOTE_VALIDATION_FAILED,

	/** The supplied bytes could not be parsed as an X.509 certificate. */
	TRUSTSTORE_PARSE_FAILED,

	/** Writing the certificate into the trust store failed. */
	TRUSTSTORE_STORE_FAILED,

	/** A trust store operation failed for an unclassified reason. */
	TRUSTSTORE_OPERATION_FAILED,

	/** No stored certificate exists to reference from the global scope (arg: fingerprint). */
	TRUSTSTORE_NO_STORED_CERT_GLOBAL,

	/** No stored certificate exists to reference from a profile scope (args: fingerprint, profile name). */
	TRUSTSTORE_NO_STORED_CERT_PROFILE,

	/** No trusted certificate is present in the global scope (arg: fingerprint). */
	TRUSTSTORE_NOT_FOUND_GLOBAL,

	/** No trusted certificate is present in a profile scope (args: fingerprint, profile name). */
	TRUSTSTORE_NOT_FOUND_PROFILE,

	/** Loading the on-disk configuration failed for an unclassified reason. */
	CONFIG_LOAD_FAILED,

	/** Saving the on-disk configuration failed for an unclassified reason. */
	CONFIG_SAVE_FAILED,

	/** Serializing the configuration to a text format failed (arg: format name). */
	CONFIG_SERIALIZE_FAILED,

	/** Deserializing the configuration from a text format failed (arg: format name). */
	CONFIG_DESERIALIZE_FAILED,

	/** Loading the configuration from the server over the API failed. */
	CONFIG_LOAD_FROM_SERVER_FAILED,

	/** Saving the configuration is not available on the web target. */
	CONFIG_SAVE_NOT_SUPPORTED_WEB,

	/** The server's signing configuration is fixed at startup and cannot be mutated at runtime. */
	CONFIG_SERVER_READ_ONLY,

	/** The trust store lacks the bytes for a certificate the archive references (arg: fingerprint). */
	CONFIG_MISSING_CERTIFICATE_BYTES,

	/** The archive references a certificate whose DER entry is absent (arg: fingerprint). */
	CONFIG_MISSING_DER_ENTRY,

	/** The configuration archive does not contain a `config.*` entry. */
	CONFIG_ARCHIVE_MISSING_CONFIG_ENTRY,

	/** The configuration archive's config entry has an unrecognized format (arg: entry name). */
	CONFIG_ARCHIVE_UNRECOGNIZED_FORMAT,

	/** The configuration archive's trust manifest could not be parsed. */
	CONFIG_ARCHIVE_CORRUPT_MANIFEST,

	/** The configuration archive could not be read. */
	CONFIG_ARCHIVE_UNREADABLE,

	/** The default hash algorithm cannot be disabled while it is the default (arg: algorithm). */
	CONFIG_CANNOT_DISABLE_DEFAULT_HASH,

	/** The default encryption algorithm cannot be disabled while it is the default (arg: algorithm). */
	CONFIG_CANNOT_DISABLE_DEFAULT_ENCRYPTION,

	/** A profile disables its own hash algorithm override (args: profile name, algorithm). */
	CONFIG_PROFILE_DISABLES_OWN_HASH,

	/** A profile disables its own encryption algorithm override (args: profile name, algorithm). */
	CONFIG_PROFILE_DISABLES_OWN_ENCRYPTION,

	/** No profile with the requested name exists (arg: profile name). */
	CONFIG_PROFILE_NOT_FOUND,

	/** No profile with the requested name was found for a trusted-list operation (arg: profile name). */
	CONFIG_NO_PROFILE_NAMED,

	/** No trusted list with the requested name exists in the global scope (arg: name). */
	CONFIG_TRUSTED_LIST_NOT_FOUND,

	/** No trusted list with the requested name exists in a profile (args: name, profile name). */
	CONFIG_TRUSTED_LIST_NOT_FOUND_IN_PROFILE,

	/** No TSP with the requested name exists in a draft (args: TSP name, draft name). */
	CONFIG_TSP_NOT_FOUND,

	/** No service with the requested name exists under a TSP (args: service name, TSP name). */
	CONFIG_SERVICE_NOT_FOUND,

	/** No TL builder draft with the requested name exists (arg: name). */
	CONFIG_DRAFT_NOT_FOUND,

	/** No renewal job with the requested name exists (arg: name). */
	CONFIG_RENEWAL_JOB_NOT_FOUND,

	/** No PKCS#11 library with the requested name is registered (arg: name). */
	CONFIG_PKCS11_LIBRARY_NOT_FOUND,

	/** No configuration serializer is registered for the requested format (arg: format). */
	CONFIG_NO_SERIALIZER_FOR_FORMAT,
}
