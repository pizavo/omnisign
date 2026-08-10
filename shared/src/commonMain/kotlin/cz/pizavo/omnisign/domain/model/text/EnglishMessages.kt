package cz.pizavo.omnisign.domain.model.text

/**
 * The bundled English templates for every [MessageKey] — the default/source locale.
 *
 * This is the single source of English used by the CLI, the server, logs, and as the
 * fallback any frontend applies when it has no translation for a key. Templates use
 * positional `%1$s`, `%2$s` placeholders (matching the Compose Resources convention), so
 * every locale — including this one — is free to arrange them in whatever order reads
 * naturally.
 */
internal object EnglishMessages {

	/**
	 * Render [key]'s English template, substituting [args] into its `%n$s` placeholders.
	 */
	fun render(key: MessageKey, args: List<String>): String {
		var result = template(key)
		for ((index, arg) in args.withIndex()) {
			result = result.replace("%${index + 1}\$s", arg)
		}
		return result
	}

	/**
	 * The raw English template for [key], before argument substitution.
	 */
	private fun template(key: MessageKey): String = when (key) {
		MessageKey.ARCHIVING_TARGET_LEVEL_NOT_HIGHER ->
			"Cannot extend to B-B: target level must be higher than the current level"
		MessageKey.ARCHIVING_TSA_REQUIRED ->
			"A timestamp server must be configured for extension to %1\$s"
		MessageKey.ARCHIVING_PDF_ENCRYPTED ->
			"The PDF is encrypted or password-protected and cannot be extended"
		MessageKey.ARCHIVING_MALFORMED_PDF ->
			"The file is not a valid PDF or could not be parsed"
		MessageKey.ARCHIVING_REVOCATION_INFO_FAILED ->
			"Failed to obtain revocation information"
		MessageKey.ARCHIVING_EXTENSION_FAILED ->
			"Document extension failed"
		MessageKey.ARCHIVING_FILE_NOT_FOUND ->
			"File not found: %1\$s"
		MessageKey.ARCHIVING_RENEWAL_STATUS_UNDETERMINABLE ->
			"Cannot determine whether the document needs renewal: a timestamp's signing certificate could not be resolved"
		MessageKey.ARCHIVING_RENEWAL_CHECK_FAILED ->
			"Failed to check archival renewal status"
		MessageKey.ARCHIVING_TIMESTAMP_INSPECT_FAILED ->
			"Failed to inspect document timestamp state"
		MessageKey.ARCHIVING_REMOTE_EXTENSION_FAILED ->
			"Remote extension failed"
		MessageKey.ARCHIVING_WEB_RENEWAL_UNSUPPORTED ->
			"Archival renewal checks are not supported on the web target"
		MessageKey.ARCHIVING_REMOTE_INSPECT_FAILED ->
			"Remote document inspection failed"
		MessageKey.SIGNING_DISCOVER_TOKENS_FAILED ->
			"Failed to discover tokens"
		MessageKey.SIGNING_PIN_ENTRY_CANCELLED ->
			"PIN entry cancelled for '%1\$s'"
		MessageKey.SIGNING_CREATE_TOKEN_FAILED ->
			"Failed to create signing token"
		MessageKey.SIGNING_FILE_NOT_FOUND ->
			"File not found: %1\$s"
		MessageKey.SIGNING_LOAD_CERTS_FROM_TOKEN_FAILED ->
			"Failed to load certificates from token '%1\$s'"
		MessageKey.SIGNING_LIST_CERTS_FAILED ->
			"Failed to list certificates"
		MessageKey.SIGNING_TOKEN_NOT_FOUND ->
			"Token '%1\$s' not found among discovered tokens"
		MessageKey.SIGNING_UNLOCK_TOKEN_FAILED ->
			"Failed to unlock token"
		MessageKey.SIGNING_PASSWORD_ENTRY_CANCELLED ->
			"Password entry cancelled"
		MessageKey.SIGNING_LOAD_CERTS_FROM_FILE_FAILED ->
			"Failed to load certificates from file"
		MessageKey.SIGNING_NO_CERT_FOUND ->
			"No suitable certificate found"
		MessageKey.SIGNING_NO_CERT_FOUND_FOR_ALIAS ->
			"No suitable certificate found for alias '%1\$s'"
		MessageKey.SIGNING_REMOTE_SIGNING_FAILED ->
			"Remote signing failed"
		MessageKey.SERVER_INVALID_CONFIGURATION ->
			"The server can't perform this operation with the selected profile's configuration"
		MessageKey.SERVER_TIMESTAMP_NOT_ALLOWED ->
			"This server does not permit timestamping"
		MessageKey.SERVER_CERTIFICATE_NOT_ALLOWED ->
			"The selected signing certificate is not permitted by the server"
		MessageKey.SIGNING_SIGNING_FAILED ->
			"Signing failed"
		MessageKey.SIGNING_LIST_CERTS_FROM_SERVER_FAILED ->
			"Failed to list certificates from server"
		MessageKey.SIGNING_UNLOCK_NOT_SUPPORTED_WEB ->
			"Token unlock is not supported on the web target"
		MessageKey.SIGNING_LOAD_FILE_NOT_SUPPORTED_WEB ->
			"Loading PKCS#12 files is not supported on the web target"
		MessageKey.SIGNING_HASH_ENCRYPTION_INCOMPATIBLE ->
			"Hash algorithm %1\$s is not compatible with encryption algorithm %2\$s. Compatible hash algorithms: %3\$s"
		MessageKey.SIGNING_HASH_NOT_SUPPORTED_WINDOWS ->
			"Hash algorithm %1\$s is not supported by the Windows Certificate Store"
		MessageKey.SIGNING_PDF_ENCRYPTED ->
			"The PDF is encrypted or password-protected and cannot be signed"
		MessageKey.SIGNING_MALFORMED_PDF ->
			"The file is not a valid PDF or could not be parsed"
		MessageKey.VALIDATION_VALIDATION_FAILED ->
			"Validation failed"
		MessageKey.VALIDATION_REMOTE_VALIDATION_FAILED ->
			"Remote validation failed"
		MessageKey.TRUSTSTORE_PARSE_FAILED ->
			"Could not parse the certificate"
		MessageKey.TRUSTSTORE_STORE_FAILED ->
			"Could not store the trusted certificate"
		MessageKey.TRUSTSTORE_OPERATION_FAILED ->
			"Trust store operation failed"
		MessageKey.TRUSTSTORE_NO_STORED_CERT_GLOBAL ->
			"No stored certificate %1\$s to reference from the global scope"
		MessageKey.TRUSTSTORE_NO_STORED_CERT_PROFILE ->
			"No stored certificate %1\$s to reference from profile '%2\$s'"
		MessageKey.TRUSTSTORE_NOT_FOUND_GLOBAL ->
			"No trusted certificate %1\$s in the global scope"
		MessageKey.TRUSTSTORE_NOT_FOUND_PROFILE ->
			"No trusted certificate %1\$s in profile '%2\$s'"
		MessageKey.CONFIG_LOAD_FAILED ->
			"Failed to load configuration"
		MessageKey.CONFIG_SAVE_FAILED ->
			"Failed to save configuration"
		MessageKey.CONFIG_SERIALIZE_FAILED ->
			"Failed to serialize configuration to %1\$s"
		MessageKey.CONFIG_DESERIALIZE_FAILED ->
			"Failed to deserialize configuration from %1\$s"
		MessageKey.CONFIG_LOAD_FROM_SERVER_FAILED ->
			"Failed to load configuration from server"
		MessageKey.CONFIG_SAVE_NOT_SUPPORTED_WEB ->
			"Saving configuration is not supported on the web target"
		MessageKey.CONFIG_SERVER_READ_ONLY ->
			"Server signing configuration is read-only"
		MessageKey.CONFIG_MISSING_CERTIFICATE_BYTES ->
			"Trust store is missing the bytes for referenced certificate %1\$s"
		MessageKey.CONFIG_MISSING_DER_ENTRY ->
			"Archive references certificate %1\$s but its DER entry is missing"
		MessageKey.CONFIG_ARCHIVE_MISSING_CONFIG_ENTRY ->
			"Configuration archive is missing a config.* entry"
		MessageKey.CONFIG_ARCHIVE_UNRECOGNIZED_FORMAT ->
			"Configuration archive has an unrecognized config format: %1\$s"
		MessageKey.CONFIG_ARCHIVE_CORRUPT_MANIFEST ->
			"Configuration archive has a corrupt trust manifest"
		MessageKey.CONFIG_ARCHIVE_UNREADABLE ->
			"Could not read the configuration archive"
		MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_HASH ->
			"Cannot disable the default hash algorithm %1\$s; change the default first"
		MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_ENCRYPTION ->
			"Cannot disable the default encryption algorithm %1\$s; change the default first"
		MessageKey.CONFIG_PROFILE_DISABLES_OWN_HASH ->
			"Profile '%1\$s' disables its own hash algorithm override %2\$s; " +
				"remove the override or remove it from the disabled set"
		MessageKey.CONFIG_PROFILE_DISABLES_OWN_ENCRYPTION ->
			"Profile '%1\$s' disables its own encryption algorithm override %2\$s; " +
				"remove the override or remove it from the disabled set"
		MessageKey.CONFIG_PROFILE_NOT_FOUND ->
			"Profile '%1\$s' does not exist"
		MessageKey.CONFIG_NO_PROFILE_NAMED ->
			"No profile named '%1\$s' found"
		MessageKey.CONFIG_TRUSTED_LIST_NOT_FOUND ->
			"No trusted list named '%1\$s' found"
		MessageKey.CONFIG_TRUSTED_LIST_NOT_FOUND_IN_PROFILE ->
			"No trusted list named '%1\$s' found in profile '%2\$s'"
		MessageKey.CONFIG_TSP_NOT_FOUND ->
			"No TSP named '%1\$s' in draft '%2\$s'"
		MessageKey.CONFIG_SERVICE_NOT_FOUND ->
			"No service named '%1\$s' in TSP '%2\$s'"
		MessageKey.CONFIG_DRAFT_NOT_FOUND ->
			"No TL draft named '%1\$s' found"
		MessageKey.CONFIG_RENEWAL_JOB_NOT_FOUND ->
			"Renewal job '%1\$s' does not exist"
		MessageKey.CONFIG_PKCS11_LIBRARY_NOT_FOUND ->
			"No PKCS#11 library named '%1\$s' is registered"
		MessageKey.CONFIG_NO_SERIALIZER_FOR_FORMAT ->
			"No serializer registered for format %1\$s"
		MessageKey.CERT_ROLE_SIGNING_CERTIFICATE ->
			"Signing certificate"
		MessageKey.CERT_ROLE_TIMESTAMP_CERTIFICATE ->
			"Timestamp certificate"
		MessageKey.CERT_ROLE_ROOT_CA ->
			"Root CA"
		MessageKey.CERT_ROLE_CERTIFICATE_AUTHORITY ->
			"Certificate Authority"
		MessageKey.CERT_ROLE_INTERMEDIATE_CA ->
			"Intermediate CA"
		MessageKey.TRUST_SOURCE_GLOBAL_STORE ->
			"Global trust store"
		MessageKey.TRUST_SOURCE_PROFILE ->
			"Profile: %1\$s"
		MessageKey.TRUST_SOURCE_TRUSTED_LIST ->
			"Trusted list"
		MessageKey.REVOCATION_LABEL_STATUS ->
			"Status"
		MessageKey.REVOCATION_LABEL_METHOD ->
			"Method"
		MessageKey.REVOCATION_LABEL_SOURCE ->
			"Source"
		MessageKey.REVOCATION_LABEL_RESPONDER ->
			"Responder"
		MessageKey.REVOCATION_LABEL_RESPONSE_PRODUCED ->
			"Response produced"
		MessageKey.REVOCATION_LABEL_STATUS_AS_OF ->
			"Status as of"
		MessageKey.REVOCATION_LABEL_FRESH_UNTIL ->
			"Fresh until"
		MessageKey.REVOCATION_LABEL_CRL_ISSUED ->
			"CRL issued"
		MessageKey.REVOCATION_LABEL_NEXT_CRL_BY ->
			"Next CRL by"
		MessageKey.REVOCATION_LABEL_PRODUCED_AT ->
			"Produced at"
		MessageKey.REVOCATION_LABEL_THIS_UPDATE ->
			"This update"
		MessageKey.REVOCATION_LABEL_NEXT_UPDATE ->
			"Next update"
		MessageKey.REVOCATION_LABEL_REVOKED_ON ->
			"Revoked on"
		MessageKey.REVOCATION_LABEL_REASON ->
			"Reason"
		MessageKey.REVOCATION_SOURCE_EMBEDDED_SEALED ->
			"Embedded in document, sealed by document timestamp"
		MessageKey.REVOCATION_SOURCE_EMBEDDED ->
			"Embedded in document (not timestamp-protected)"
		MessageKey.REVOCATION_SOURCE_ONLINE ->
			"Retrieved online during validation"
		MessageKey.REVOCATION_STATUS_GOOD ->
			"GOOD"
		MessageKey.REVOCATION_STATUS_REVOKED ->
			"REVOKED"
		MessageKey.REVOCATION_STATUS_UNKNOWN ->
			"UNKNOWN"
		MessageKey.REVOCATION_CONCLUSION_REVOKED ->
			"The signing certificate was revoked as of %1\$s."
		MessageKey.REVOCATION_CONCLUSION_NOT_REVOKED ->
			"The signing certificate was not revoked as of %1\$s."
		MessageKey.REVOCATION_CONCLUSION_UNDETERMINED ->
			"The signing certificate had an undetermined revocation status as of %1\$s."
		MessageKey.TRUST_TIER_QUALIFIED ->
			"Qualified"
		MessageKey.TRUST_TIER_RECOGNIZED ->
			"Recognized"
		MessageKey.TRUST_TIER_NOT_QUALIFIED ->
			"Not qualified"
		MessageKey.SIGNATURE_QSCD_RESIDENCE ->
			"The private key resides in a QSCD at both issuance and signing time."
		MessageKey.WARNING_REVOCATION_NOT_FOUND ->
			"Revocation data (CRL/OCSP) could not be retrieved for %1\$s. " +
				"Long-term signature validity may be affected."
		MessageKey.WARNING_REVOCATION_AFTER_CERTIFICATE_EXPIRY ->
			"Revocation data for %1\$s was issued after the certificate expired and cannot be used: " +
				"the issuer no longer vouches for that period. Long-term validation material has to " +
				"be added while the certificate is still valid."
		MessageKey.WARNING_REVOCATION_UNTRUSTED_CHAIN ->
			"Revocation checks were skipped for %1\$s in untrusted chain(s). " +
				"This is expected when no trusted list is configured."
		MessageKey.WARNING_REVOCATION_STATUS_UNKNOWN ->
			"Revocation status could not be confirmed for %1\$s. " +
				"The certificate chain may not be fully trusted by all validators."
		MessageKey.WARNING_REVOCATION_POE_MISSING ->
			"Revocation data required for proof-of-existence is missing for %1\$s."
		MessageKey.WARNING_REVOCATION_POE_STALE_BY_TIME ->
			"Revocation data for %1\$s predates the signature timestamp, so it does not " +
				"cover the timestamp's proof-of-existence. The issuer guarantees newer " +
				"revocation data by %2\$s; augmenting the signature after that time closes the gap."
		MessageKey.WARNING_REVOCATION_POE_STALE_GENERIC ->
			"Revocation data for %1\$s predates the signature timestamp, so it does not " +
				"cover the timestamp's proof-of-existence. Augmenting the signature once newer " +
				"revocation data is published closes the gap."
		MessageKey.WARNING_FRESH_REVOCATION_MISSING_BY_TIME ->
			"Revocation data for %1\$s in the signing chain predates the signature timestamp, " +
				"so it does not cover the moment of signing. The issuer guarantees newer " +
				"revocation data by %2\$s; augmenting the signature after that time closes the gap."
		MessageKey.WARNING_FRESH_REVOCATION_MISSING_GENERIC ->
			"Revocation data for %1\$s in the signing chain predates the signature timestamp, " +
				"so it does not cover the moment of signing. Augmenting the signature once newer " +
				"revocation data is published closes the gap."
		MessageKey.WARNING_TIMESTAMP_UNTRUSTED ->
			"Proof-of-existence could not be established for %1\$s because the issuing TSA " +
				"is not in the trusted list."
		MessageKey.WARNING_CERTIFICATE_PARSE_ERROR ->
			"Some certificates in the chain contain malformed extensions that could not be " +
				"fully parsed. This is typically caused by non-standard third-party certificates " +
				"(e.g. TSA) and does not affect the signature itself."
		MessageKey.WARNING_TSP_FAILURE ->
			"The timestamp server reported a problem (PKIFailureInfo). If the operation " +
				"succeeded, the timestamp may have been obtained on a retry."
		MessageKey.WARNING_TRUSTED_LIST_REFRESH_INCOMPLETE ->
			"Could not refresh %1\$s (%2\$s). Qualification assessment for certificates from these " +
				"sources may be incomplete."
		MessageKey.VALIDATION_SIGNATURE_POLICY_UNTRUSTED ->
			"Signature distrusted by policy: its trust anchor is trusted for timestamping only, " +
				"not for signing"
		MessageKey.VALIDATION_TIMESTAMP_POLICY_UNTRUSTED ->
			"Timestamp distrusted by policy: its trust anchor is trusted as a certificate authority " +
				"only, not for timestamping"
		MessageKey.VALIDATION_HASH_DISABLED ->
			"Hash algorithm %1\$s is disabled in your configuration"
		MessageKey.VALIDATION_ENCRYPTION_DISABLED ->
			"Encryption algorithm %1\$s is disabled in your configuration"
		MessageKey.VALIDATION_EU_LOTL_UNAVAILABLE ->
			"The EU list of trusted lists (LOTL) could not be downloaded, so a signature or timestamp " +
				"could not be checked against EU trust. Reconnect and refresh the trusted lists, then re-validate."
	}
}
