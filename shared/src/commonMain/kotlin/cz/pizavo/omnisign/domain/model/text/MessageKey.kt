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

	/** Web target: the server rejected the operation because the resolved profile configuration is not valid for it. */
	SERVER_INVALID_CONFIGURATION,

	/** Web target: the server does not permit timestamping. */
	SERVER_TIMESTAMP_NOT_ALLOWED,

	/** Web target: the server does not permit the selected signing certificate. */
	SERVER_CERTIFICATE_NOT_ALLOWED,

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

	/** The input PDF is encrypted or password-protected, so it cannot be signed without its password. */
	SIGNING_PDF_ENCRYPTED,

	/** The input is not a valid PDF or could not be parsed for signing. */
	SIGNING_MALFORMED_PDF,

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

	/** Chain role: the end-entity signing certificate. */
	CERT_ROLE_SIGNING_CERTIFICATE,

	/** Chain role: the end-entity timestamp certificate. */
	CERT_ROLE_TIMESTAMP_CERTIFICATE,

	/** Chain role: a self-signed root certificate authority. */
	CERT_ROLE_ROOT_CA,

	/** Chain role: a non-self-signed (issued) certificate authority. */
	CERT_ROLE_CERTIFICATE_AUTHORITY,

	/** Chain role: an intermediate certificate authority between the leaf and the root. */
	CERT_ROLE_INTERMEDIATE_CA,

	/** Trust origin: the app-managed global trust store. */
	TRUST_SOURCE_GLOBAL_STORE,

	/** Trust origin: a named profile's trust store (arg: profile name). */
	TRUST_SOURCE_PROFILE,

	/** Trust origin: a trusted list other than the EU LOTL, with no specific name. */
	TRUST_SOURCE_TRUSTED_LIST,

	/** Revocation row label: the status the responder asserted. */
	REVOCATION_LABEL_STATUS,

	/** Revocation row label: the revocation mechanism (OCSP/CRL). */
	REVOCATION_LABEL_METHOD,

	/** Revocation row label: where the token came from. */
	REVOCATION_LABEL_SOURCE,

	/** Revocation row label: the responder / distribution-point address. */
	REVOCATION_LABEL_RESPONDER,

	/** Revocation row label: OCSP response production time. */
	REVOCATION_LABEL_RESPONSE_PRODUCED,

	/** Revocation row label: OCSP status validity start. */
	REVOCATION_LABEL_STATUS_AS_OF,

	/** Revocation row label: OCSP status validity end. */
	REVOCATION_LABEL_FRESH_UNTIL,

	/** Revocation row label: CRL issuance time. */
	REVOCATION_LABEL_CRL_ISSUED,

	/** Revocation row label: next scheduled CRL time. */
	REVOCATION_LABEL_NEXT_CRL_BY,

	/** Revocation row label: generic production time. */
	REVOCATION_LABEL_PRODUCED_AT,

	/** Revocation row label: generic validity-window start. */
	REVOCATION_LABEL_THIS_UPDATE,

	/** Revocation row label: generic validity-window end. */
	REVOCATION_LABEL_NEXT_UPDATE,

	/** Revocation row label: date the certificate was revoked. */
	REVOCATION_LABEL_REVOKED_ON,

	/** Revocation row label: revocation reason code. */
	REVOCATION_LABEL_REASON,

	/** Revocation source: embedded in the document and sealed by a document timestamp. */
	REVOCATION_SOURCE_EMBEDDED_SEALED,

	/** Revocation source: embedded in the document, not timestamp-protected. */
	REVOCATION_SOURCE_EMBEDDED,

	/** Revocation source: retrieved online during validation. */
	REVOCATION_SOURCE_ONLINE,

	/** Revocation status value: GOOD (not revoked). */
	REVOCATION_STATUS_GOOD,

	/** Revocation status value: REVOKED. */
	REVOCATION_STATUS_REVOKED,

	/** Revocation status value: UNKNOWN. */
	REVOCATION_STATUS_UNKNOWN,

	/** Revocation conclusion: the certificate was revoked as of a time (arg: time). */
	REVOCATION_CONCLUSION_REVOKED,

	/** Revocation conclusion: the certificate was not revoked as of a time (arg: time). */
	REVOCATION_CONCLUSION_NOT_REVOKED,

	/** Revocation conclusion: undetermined status as of a time (arg: time). */
	REVOCATION_CONCLUSION_UNDETERMINED,

	/** Trust tier label: qualified certificate on a QSCD. */
	TRUST_TIER_QUALIFIED,

	/** Trust tier label: qualified certificate without confirmed QSCD. */
	TRUST_TIER_RECOGNIZED,

	/** Trust tier label: not qualified, or qualification undetermined. */
	TRUST_TIER_NOT_QUALIFIED,

	/** Qualification info: the private key resides in a QSCD at issuance and signing time. */
	SIGNATURE_QSCD_RESIDENCE,

	/** Sanitized warning: CRL/OCSP revocation data could not be retrieved (arg: affected-count phrase). */
	WARNING_REVOCATION_NOT_FOUND,

	/**
	 * Sanitized warning: revocation data was retrieved but rejected because it was issued after the
	 * certificate expired, so its issuer no longer vouches for that period (arg: affected-count phrase).
	 */
	WARNING_REVOCATION_AFTER_CERTIFICATE_EXPIRY,

	/** Sanitized warning: revocation checks skipped for an untrusted chain (arg: affected-count phrase). */
	WARNING_REVOCATION_UNTRUSTED_CHAIN,

	/** Sanitized warning: a certificate's revocation status could not be confirmed (arg: affected-count phrase). */
	WARNING_REVOCATION_STATUS_UNKNOWN,

	/** Sanitized warning: revocation data required for proof-of-existence is missing (arg: affected-count phrase). */
	WARNING_REVOCATION_POE_MISSING,

	/**
	 * Sanitized warning: embedded revocation data predates the timestamp's proof-of-existence, and the
	 * issuer guarantees newer data by a time (args: affected-count phrase, due time).
	 */
	WARNING_REVOCATION_POE_STALE_BY_TIME,

	/** Sanitized warning: embedded revocation data predates the timestamp's proof-of-existence (arg: affected-count phrase). */
	WARNING_REVOCATION_POE_STALE_GENERIC,

	/**
	 * Sanitized warning: signing-chain revocation data predates the signature, and the issuer guarantees
	 * newer data by a time (args: affected-count phrase, due time).
	 */
	WARNING_FRESH_REVOCATION_MISSING_BY_TIME,

	/** Sanitized warning: signing-chain revocation data predates the signature (arg: affected-count phrase). */
	WARNING_FRESH_REVOCATION_MISSING_GENERIC,

	/** Sanitized warning: a timestamp's proof-of-existence could not be established, the TSA being untrusted (arg: affected-count phrase). */
	WARNING_TIMESTAMP_UNTRUSTED,

	/** Sanitized warning: one or more certificates carry malformed extensions that could not be parsed. */
	WARNING_CERTIFICATE_PARSE_ERROR,

	/** Sanitized warning: the timestamp server reported a failure (PKIFailureInfo). */
	WARNING_TSP_FAILURE,

	/** Trusted-list loading: one or more trusted lists could not be refreshed (arg: count phrase; arg: failed hosts). */
	WARNING_TRUSTED_LIST_REFRESH_INCOMPLETE,

	/** Validation: a signature's trust anchor is trusted for timestamping only, not for signing. */
	VALIDATION_SIGNATURE_POLICY_UNTRUSTED,

	/** Validation: a timestamp's trust anchor is trusted as a CA only, not for timestamping. */
	VALIDATION_TIMESTAMP_POLICY_UNTRUSTED,

	/** Validation: the signature's hash algorithm is disabled in the active configuration (arg: algorithm). */
	VALIDATION_HASH_DISABLED,

	/** Validation: the signature's encryption algorithm is disabled in the active configuration (arg: algorithm). */
	VALIDATION_ENCRYPTION_DISABLED,

	/** Validation: the EU LOTL could not be downloaded, leaving a signature or timestamp unverifiable against EU trust. */
	VALIDATION_EU_LOTL_UNAVAILABLE,
}
