package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Compose Resource for each translated [MessageKey]; absent keys fall back to bundled English. */
private val MESSAGE_RES: Map<MessageKey, StringResource> = mapOf(
	MessageKey.ARCHIVING_TARGET_LEVEL_NOT_HIGHER to Res.string.err_archiving_target_level_not_higher,
	MessageKey.ARCHIVING_TSA_REQUIRED to Res.string.err_archiving_tsa_required,
	MessageKey.ARCHIVING_PDF_ENCRYPTED to Res.string.err_archiving_pdf_encrypted,
	MessageKey.ARCHIVING_MALFORMED_PDF to Res.string.err_archiving_malformed_pdf,
	MessageKey.ARCHIVING_REVOCATION_INFO_FAILED to Res.string.err_archiving_revocation_info_failed,
	MessageKey.ARCHIVING_EXTENSION_FAILED to Res.string.err_archiving_extension_failed,
	MessageKey.ARCHIVING_TIMESTAMP_TOO_LARGE to Res.string.err_archiving_timestamp_too_large,
	MessageKey.ARCHIVING_FILE_NOT_FOUND to Res.string.err_archiving_file_not_found,
	MessageKey.ARCHIVING_RENEWAL_STATUS_UNDETERMINABLE to Res.string.err_archiving_renewal_status_undeterminable,
	MessageKey.ARCHIVING_RENEWAL_CHECK_FAILED to Res.string.err_archiving_renewal_check_failed,
	MessageKey.ARCHIVING_TIMESTAMP_INSPECT_FAILED to Res.string.err_archiving_timestamp_inspect_failed,
	MessageKey.ARCHIVING_REMOTE_EXTENSION_FAILED to Res.string.err_archiving_remote_extension_failed,
	MessageKey.ARCHIVING_WEB_RENEWAL_UNSUPPORTED to Res.string.err_archiving_web_renewal_unsupported,
	MessageKey.ARCHIVING_REMOTE_INSPECT_FAILED to Res.string.err_archiving_remote_inspect_failed,
	MessageKey.SIGNING_DISCOVER_TOKENS_FAILED to Res.string.err_signing_discover_tokens_failed,
	MessageKey.SIGNING_PIN_ENTRY_CANCELLED to Res.string.err_signing_pin_entry_cancelled,
	MessageKey.SIGNING_CREATE_TOKEN_FAILED to Res.string.err_signing_create_token_failed,
	MessageKey.SIGNING_FILE_NOT_FOUND to Res.string.err_signing_file_not_found,
	MessageKey.SIGNING_LOAD_CERTS_FROM_TOKEN_FAILED to Res.string.err_signing_load_certs_from_token_failed,
	MessageKey.SIGNING_LIST_CERTS_FAILED to Res.string.err_signing_list_certs_failed,
	MessageKey.SIGNING_TOKEN_NOT_FOUND to Res.string.err_signing_token_not_found,
	MessageKey.SIGNING_UNLOCK_TOKEN_FAILED to Res.string.err_signing_unlock_token_failed,
	MessageKey.SIGNING_PASSWORD_ENTRY_CANCELLED to Res.string.err_signing_password_entry_cancelled,
	MessageKey.SIGNING_LOAD_CERTS_FROM_FILE_FAILED to Res.string.err_signing_load_certs_from_file_failed,
	MessageKey.SIGNING_NO_CERT_FOUND to Res.string.err_signing_no_cert_found,
	MessageKey.SIGNING_NO_CERT_FOUND_FOR_ALIAS to Res.string.err_signing_no_cert_found_for_alias,
	MessageKey.SIGNING_REMOTE_SIGNING_FAILED to Res.string.err_signing_remote_signing_failed,
	MessageKey.SERVER_INVALID_CONFIGURATION to Res.string.err_server_invalid_configuration,
	MessageKey.SERVER_TIMESTAMP_NOT_ALLOWED to Res.string.err_server_timestamp_not_allowed,
	MessageKey.SERVER_CERTIFICATE_NOT_ALLOWED to Res.string.err_server_certificate_not_allowed,
	MessageKey.SIGNING_SIGNING_FAILED to Res.string.err_signing_signing_failed,
	MessageKey.SIGNING_LIST_CERTS_FROM_SERVER_FAILED to Res.string.err_signing_list_certs_from_server_failed,
	MessageKey.SIGNING_UNLOCK_NOT_SUPPORTED_WEB to Res.string.err_signing_unlock_not_supported_web,
	MessageKey.SIGNING_LOAD_FILE_NOT_SUPPORTED_WEB to Res.string.err_signing_load_file_not_supported_web,
	MessageKey.SIGNING_HASH_ENCRYPTION_INCOMPATIBLE to Res.string.err_signing_hash_encryption_incompatible,
	MessageKey.SIGNING_HASH_NOT_SUPPORTED_WINDOWS to Res.string.err_signing_hash_not_supported_windows,
	MessageKey.SIGNING_PDF_ENCRYPTED to Res.string.err_signing_pdf_encrypted,
	MessageKey.SIGNING_MALFORMED_PDF to Res.string.err_signing_malformed_pdf,
	MessageKey.SIGNING_SIGNATURE_TOO_LARGE to Res.string.err_signing_signature_too_large,
	MessageKey.VALIDATION_VALIDATION_FAILED to Res.string.err_validation_validation_failed,
	MessageKey.VALIDATION_REMOTE_VALIDATION_FAILED to Res.string.err_validation_remote_validation_failed,
	MessageKey.TRUSTSTORE_PARSE_FAILED to Res.string.err_truststore_parse_failed,
	MessageKey.TRUSTSTORE_STORE_FAILED to Res.string.err_truststore_store_failed,
	MessageKey.TRUSTSTORE_OPERATION_FAILED to Res.string.err_truststore_operation_failed,
	MessageKey.TRUSTSTORE_NO_STORED_CERT_GLOBAL to Res.string.err_truststore_no_stored_cert_global,
	MessageKey.TRUSTSTORE_NO_STORED_CERT_PROFILE to Res.string.err_truststore_no_stored_cert_profile,
	MessageKey.TRUSTSTORE_NOT_FOUND_GLOBAL to Res.string.err_truststore_not_found_global,
	MessageKey.TRUSTSTORE_NOT_FOUND_PROFILE to Res.string.err_truststore_not_found_profile,
	MessageKey.CONFIG_LOAD_FAILED to Res.string.err_config_load_failed,
	MessageKey.CONFIG_SAVE_FAILED to Res.string.err_config_save_failed,
	MessageKey.CONFIG_SERIALIZE_FAILED to Res.string.err_config_serialize_failed,
	MessageKey.CONFIG_DESERIALIZE_FAILED to Res.string.err_config_deserialize_failed,
	MessageKey.CONFIG_LOAD_FROM_SERVER_FAILED to Res.string.err_config_load_from_server_failed,
	MessageKey.CONFIG_SAVE_NOT_SUPPORTED_WEB to Res.string.err_config_save_not_supported_web,
	MessageKey.CONFIG_SERVER_READ_ONLY to Res.string.err_config_server_read_only,
	MessageKey.CONFIG_MISSING_CERTIFICATE_BYTES to Res.string.err_config_missing_certificate_bytes,
	MessageKey.CONFIG_MISSING_DER_ENTRY to Res.string.err_config_missing_der_entry,
	MessageKey.CONFIG_ARCHIVE_MISSING_CONFIG_ENTRY to Res.string.err_config_archive_missing_config_entry,
	MessageKey.CONFIG_ARCHIVE_UNRECOGNIZED_FORMAT to Res.string.err_config_archive_unrecognized_format,
	MessageKey.CONFIG_ARCHIVE_CORRUPT_MANIFEST to Res.string.err_config_archive_corrupt_manifest,
	MessageKey.CONFIG_ARCHIVE_UNREADABLE to Res.string.err_config_archive_unreadable,
	MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_HASH to Res.string.err_config_cannot_disable_default_hash,
	MessageKey.CONFIG_CANNOT_DISABLE_DEFAULT_ENCRYPTION to Res.string.err_config_cannot_disable_default_encryption,
	MessageKey.CONFIG_PROFILE_DISABLES_OWN_HASH to Res.string.err_config_profile_disables_own_hash,
	MessageKey.CONFIG_PROFILE_DISABLES_OWN_ENCRYPTION to Res.string.err_config_profile_disables_own_encryption,
	MessageKey.CONFIG_PROFILE_NOT_FOUND to Res.string.err_config_profile_not_found,
	MessageKey.CONFIG_NO_PROFILE_NAMED to Res.string.err_config_no_profile_named,
	MessageKey.CONFIG_TRUSTED_LIST_NOT_FOUND to Res.string.err_config_trusted_list_not_found,
	MessageKey.CONFIG_TRUSTED_LIST_NOT_FOUND_IN_PROFILE to Res.string.err_config_trusted_list_not_found_in_profile,
	MessageKey.CONFIG_TSP_NOT_FOUND to Res.string.err_config_tsp_not_found,
	MessageKey.CONFIG_SERVICE_NOT_FOUND to Res.string.err_config_service_not_found,
	MessageKey.CONFIG_DRAFT_NOT_FOUND to Res.string.err_config_draft_not_found,
	MessageKey.CONFIG_RENEWAL_JOB_NOT_FOUND to Res.string.err_config_renewal_job_not_found,
	MessageKey.CONFIG_PKCS11_LIBRARY_NOT_FOUND to Res.string.err_config_pkcs11_library_not_found,
	MessageKey.CONFIG_NO_SERIALIZER_FOR_FORMAT to Res.string.err_config_no_serializer_for_format,
	MessageKey.CERT_ROLE_SIGNING_CERTIFICATE to Res.string.cert_role_signing_certificate,
	MessageKey.CERT_ROLE_TIMESTAMP_CERTIFICATE to Res.string.cert_role_timestamp_certificate,
	MessageKey.CERT_ROLE_ROOT_CA to Res.string.cert_role_root_ca,
	MessageKey.CERT_ROLE_CERTIFICATE_AUTHORITY to Res.string.cert_role_certificate_authority,
	MessageKey.CERT_ROLE_INTERMEDIATE_CA to Res.string.cert_role_intermediate_ca,
	MessageKey.TRUST_SOURCE_GLOBAL_STORE to Res.string.trust_source_global_store,
	MessageKey.TRUST_SOURCE_PROFILE to Res.string.trust_source_profile,
	MessageKey.TRUST_SOURCE_TRUSTED_LIST to Res.string.trust_source_trusted_list,
	MessageKey.REVOCATION_LABEL_STATUS to Res.string.revocation_label_status,
	MessageKey.REVOCATION_LABEL_METHOD to Res.string.revocation_label_method,
	MessageKey.REVOCATION_LABEL_SOURCE to Res.string.revocation_label_source,
	MessageKey.REVOCATION_LABEL_RESPONDER to Res.string.revocation_label_responder,
	MessageKey.REVOCATION_LABEL_RESPONSE_PRODUCED to Res.string.revocation_label_response_produced,
	MessageKey.REVOCATION_LABEL_STATUS_AS_OF to Res.string.revocation_label_status_as_of,
	MessageKey.REVOCATION_LABEL_FRESH_UNTIL to Res.string.revocation_label_fresh_until,
	MessageKey.REVOCATION_LABEL_CRL_ISSUED to Res.string.revocation_label_crl_issued,
	MessageKey.REVOCATION_LABEL_NEXT_CRL_BY to Res.string.revocation_label_next_crl_by,
	MessageKey.REVOCATION_LABEL_PRODUCED_AT to Res.string.revocation_label_produced_at,
	MessageKey.REVOCATION_LABEL_THIS_UPDATE to Res.string.revocation_label_this_update,
	MessageKey.REVOCATION_LABEL_NEXT_UPDATE to Res.string.revocation_label_next_update,
	MessageKey.REVOCATION_LABEL_REVOKED_ON to Res.string.revocation_label_revoked_on,
	MessageKey.REVOCATION_LABEL_REASON to Res.string.revocation_label_reason,
	MessageKey.REVOCATION_SOURCE_EMBEDDED_SEALED to Res.string.revocation_source_embedded_sealed,
	MessageKey.REVOCATION_SOURCE_EMBEDDED to Res.string.revocation_source_embedded,
	MessageKey.REVOCATION_SOURCE_ONLINE to Res.string.revocation_source_online,
	MessageKey.REVOCATION_STATUS_GOOD to Res.string.revocation_status_good,
	MessageKey.REVOCATION_STATUS_REVOKED to Res.string.revocation_status_revoked,
	MessageKey.REVOCATION_STATUS_UNKNOWN to Res.string.revocation_status_unknown,
	MessageKey.REVOCATION_CONCLUSION_REVOKED to Res.string.revocation_conclusion_revoked,
	MessageKey.REVOCATION_CONCLUSION_NOT_REVOKED to Res.string.revocation_conclusion_not_revoked,
	MessageKey.REVOCATION_CONCLUSION_UNDETERMINED to Res.string.revocation_conclusion_undetermined,
	MessageKey.TRUST_TIER_QUALIFIED to Res.string.trust_tier_qualified,
	MessageKey.TRUST_TIER_RECOGNIZED to Res.string.trust_tier_recognized,
	MessageKey.TRUST_TIER_NOT_QUALIFIED to Res.string.trust_tier_not_qualified,
	MessageKey.SIGNATURE_QSCD_RESIDENCE to Res.string.signature_qscd_residence,
	MessageKey.WARNING_REVOCATION_NOT_FOUND to Res.string.warning_revocation_not_found,
	MessageKey.WARNING_REVOCATION_AFTER_CERTIFICATE_EXPIRY to Res.string.warning_revocation_after_certificate_expiry,
	MessageKey.WARNING_REVOCATION_UNTRUSTED_CHAIN to Res.string.warning_revocation_untrusted_chain,
	MessageKey.WARNING_REVOCATION_STATUS_UNKNOWN to Res.string.warning_revocation_status_unknown,
	MessageKey.WARNING_REVOCATION_POE_MISSING to Res.string.warning_revocation_poe_missing,
	MessageKey.WARNING_REVOCATION_POE_STALE_BY_TIME to Res.string.warning_revocation_poe_stale_by_time,
	MessageKey.WARNING_REVOCATION_POE_STALE_GENERIC to Res.string.warning_revocation_poe_stale_generic,
	MessageKey.WARNING_FRESH_REVOCATION_MISSING_BY_TIME to Res.string.warning_fresh_revocation_missing_by_time,
	MessageKey.WARNING_FRESH_REVOCATION_MISSING_GENERIC to Res.string.warning_fresh_revocation_missing_generic,
	MessageKey.WARNING_TIMESTAMP_UNTRUSTED to Res.string.warning_timestamp_untrusted,
	MessageKey.WARNING_CERTIFICATE_PARSE_ERROR to Res.string.warning_certificate_parse_error,
	MessageKey.WARNING_TSP_FAILURE to Res.string.warning_tsp_failure,
	MessageKey.WARNING_TRUSTED_LIST_REFRESH_INCOMPLETE to Res.string.warning_tl_refresh_incomplete,
	MessageKey.VALIDATION_SIGNATURE_POLICY_UNTRUSTED to Res.string.validation_signature_policy_untrusted,
	MessageKey.VALIDATION_TIMESTAMP_POLICY_UNTRUSTED to Res.string.validation_timestamp_policy_untrusted,
	MessageKey.VALIDATION_HASH_DISABLED to Res.string.validation_hash_disabled,
	MessageKey.VALIDATION_ENCRYPTION_DISABLED to Res.string.validation_encryption_disabled,
	MessageKey.VALIDATION_EU_LOTL_UNAVAILABLE to Res.string.validation_eu_lotl_unavailable,
)

/**
 * The affected-entity count plural for each warning key whose summary embeds a count, keyed so a
 * localizing frontend can render the phrase ("2 certificates") in its own locale with correct plural
 * agreement. Keys absent here carry no count.
 */
private val WARNING_COUNT_PLURAL: Map<MessageKey, PluralStringResource> = mapOf(
	MessageKey.WARNING_REVOCATION_NOT_FOUND to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_REVOCATION_AFTER_CERTIFICATE_EXPIRY to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_REVOCATION_UNTRUSTED_CHAIN to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_REVOCATION_STATUS_UNKNOWN to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_REVOCATION_POE_MISSING to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_REVOCATION_POE_STALE_BY_TIME to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_REVOCATION_POE_STALE_GENERIC to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_FRESH_REVOCATION_MISSING_BY_TIME to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_FRESH_REVOCATION_MISSING_GENERIC to Res.plurals.warning_affected_certificates,
	MessageKey.WARNING_TIMESTAMP_UNTRUSTED to Res.plurals.warning_affected_timestamps,
	MessageKey.WARNING_TRUSTED_LIST_REFRESH_INCOMPLETE to Res.plurals.warning_affected_trusted_lists,
)

/** Resolve this text to the active locale: translated Compose Resource if present, else bundled English. */
@Composable
fun LocalizableText.localized(): String = when (this) {
	is LocalizableText.Literal -> value
	is LocalizableText.Keyed -> MESSAGE_RES[key]?.let { stringResource(it, *args.toTypedArray()) } ?: english()
}

/**
 * Resolve a single message [LocalizableText] to the active locale.
 *
 * Behaves like [localized], with one addition for sanitized-warning summaries: for a
 * [LocalizableText.Keyed] whose category embeds an affected-entity count (see [WARNING_COUNT_PLURAL]),
 * the count is read back from the first argument — the English count phrase the sanitizer baked for
 * headless callers — and re-rendered as a locale-correct plural, so a translated sentence reads
 * "2 certifikáty" rather than the English "2 certificates" spliced in. Any further arguments (e.g. a
 * locale-independent due time) pass through. Untranslated keys fall back to the bundled English.
 */
@Composable
fun LocalizableText.localizedMessage(): String = when (this) {
	is LocalizableText.Literal -> value
	is LocalizableText.Keyed -> {
		val res = MESSAGE_RES[key]
		val plural = WARNING_COUNT_PLURAL[key]
		when {
			res == null -> english()
			plural == null -> stringResource(res, *args.toTypedArray())
			else -> {
				val count = args.firstOrNull()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 1
				val phrase = pluralStringResource(plural, count, count)
				stringResource(res, phrase, *args.drop(1).toTypedArray())
			}
		}
	}
}

/**
 * Resolve each message in this list to the active locale via [localizedMessage]. A `@Composable`
 * for-loop, because [localizedMessage] cannot be invoked inside a `map` lambda.
 */
@Composable
fun List<LocalizableText>.localizedMessages(): List<String> {
	val resolved = ArrayList<String>(size)
	for (message in this) resolved += message.localizedMessage()
	return resolved
}

/**
 * Resolve a sanitized warning's [AnnotatedWarning.summary] to the active locale via [localizedMessage],
 * which re-derives the plural count from the baked count phrase (equal to the [AnnotatedWarning.affectedIds]
 * count). [localizedCountPhrase] locates the same phrase for the clickable span.
 */
@Composable
fun AnnotatedWarning.localizedSummary(): String = summary.localizedMessage()

/**
 * The localized, pluralized count phrase (e.g. "2 certificates") a warning's summary embeds, or null
 * when the warning carries no count or names no specific entity. The count comes from
 * [AnnotatedWarning.affectedIds] so the phrase agrees with what the "show affected" affordance lists,
 * letting a caller locate it within [localizedSummary] to render it as the clickable span.
 */
@Composable
fun AnnotatedWarning.localizedCountPhrase(): String? {
	val keyed = summary as? LocalizableText.Keyed ?: return null
	val plural = WARNING_COUNT_PLURAL[keyed.key] ?: return null
	if (affectedIds.isEmpty()) return null
	return pluralStringResource(plural, affectedIds.size, affectedIds.size)
}
