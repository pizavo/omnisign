package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Compose Resource for each translated [MessageKey]; absent keys fall back to bundled English. */
private val MESSAGE_RES: Map<MessageKey, StringResource> = mapOf(
	MessageKey.ARCHIVING_TARGET_LEVEL_NOT_HIGHER to Res.string.err_archiving_target_level_not_higher,
	MessageKey.ARCHIVING_TSA_REQUIRED to Res.string.err_archiving_tsa_required,
	MessageKey.ARCHIVING_PDF_ENCRYPTED to Res.string.err_archiving_pdf_encrypted,
	MessageKey.ARCHIVING_MALFORMED_PDF to Res.string.err_archiving_malformed_pdf,
	MessageKey.ARCHIVING_REVOCATION_INFO_FAILED to Res.string.err_archiving_revocation_info_failed,
	MessageKey.ARCHIVING_EXTENSION_FAILED to Res.string.err_archiving_extension_failed,
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
	MessageKey.SIGNING_SIGNING_FAILED to Res.string.err_signing_signing_failed,
	MessageKey.SIGNING_LIST_CERTS_FROM_SERVER_FAILED to Res.string.err_signing_list_certs_from_server_failed,
	MessageKey.SIGNING_UNLOCK_NOT_SUPPORTED_WEB to Res.string.err_signing_unlock_not_supported_web,
	MessageKey.SIGNING_LOAD_FILE_NOT_SUPPORTED_WEB to Res.string.err_signing_load_file_not_supported_web,
	MessageKey.SIGNING_HASH_ENCRYPTION_INCOMPATIBLE to Res.string.err_signing_hash_encryption_incompatible,
	MessageKey.SIGNING_HASH_NOT_SUPPORTED_WINDOWS to Res.string.err_signing_hash_not_supported_windows,
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
)

/** Resolve this text to the active locale: translated Compose Resource if present, else bundled English. */
@Composable
fun LocalizableText.localized(): String = when (this) {
	is LocalizableText.Literal -> value
	is LocalizableText.Keyed -> MESSAGE_RES[key]?.let { stringResource(it, *args.toTypedArray()) } ?: english()
}
