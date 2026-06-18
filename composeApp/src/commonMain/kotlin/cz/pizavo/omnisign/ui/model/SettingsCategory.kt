package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Identifies a settings category displayed in the left navigation of the settings dialog.
 *
 * Categories are organized into top-level groups that may contain children.
 * A group entry acts as a header and selects the first child when clicked,
 * while a leaf entry shows its own content panel. The [label] and [description]
 * are locale-resolved at composition time.
 *
 * @property parent The parent group this category belongs to, or `null` for top-level groups.
 */
enum class SettingsCategory(
    val parent: SettingsCategory? = null,
) {
    /** Top-level group for signing-related settings. */
    Signing,

    /** Default hash algorithm, encryption algorithm, and signature level. */
    SigningDefaults(parent = Signing),

    /** Globally disabled hash and encryption algorithms. */
    DisabledAlgorithms(parent = Signing),

    /** Top-level group for external service configuration. */
    Services,

    /** Timestamp server (TSA) configuration. */
    TimestampServer(parent = Services),

    /** OCSP and CRL timeout settings. */
    OcspCrl(parent = Services),

    /** Top-level group for validation configuration. */
    Validation,

    /** Validation policy and trust source settings. */
    ValidationPolicy(parent = Validation),

    /** Algorithm constraint levels for validation. */
    AlgorithmConstraints(parent = Validation),

    /** Directly trusted CA and TSA certificates for the global scope. */
    TrustedCertificates(parent = Validation),

    /** Custom external ETSI Trusted List sources. */
    CustomTrustedLists(parent = Validation),

    /** Top-level group for archival renewal configuration. */
    Archiving,

    /** Named renewal jobs for automatic B-LTA re-timestamping. */
    RenewalJobs(parent = Archiving),

    /** OS-level daily scheduler for running renewal jobs automatically. */
    Scheduler(parent = Archiving),

    /** Top-level group for token/hardware configuration. */
    Tokens,

    /** User-registered PKCS#11 middleware libraries. */
    Pkcs11Libraries(parent = Tokens),

    /** Top-level group for configuration backup (export / import). */
    Backup,

    /** Export / import the full configuration as a single archive. */
    ConfigBackup(parent = Backup),

    /** Top-level group for desktop appearance settings (Linux only). */
    Appearance,

    /** Window title bar mode (native vs. merged custom toolbar). */
    WindowTitleBar(parent = Appearance);

    /** Whether this category is a top-level group (has children). */
    val isGroup: Boolean get() = entries.any { it.parent == this }

    /** Direct child categories of this group. */
    val children: List<SettingsCategory> get() = entries.filter { it.parent == this }

    /** Human-readable name displayed in the navigation sidebar, resolved in the current locale. */
    @Composable
    fun label(): String = when (this) {
        Signing -> stringResource(Res.string.settingscat_signing)
        SigningDefaults -> stringResource(Res.string.settingscat_defaults)
        DisabledAlgorithms -> stringResource(Res.string.settingscat_disabled_algorithms)
        Services -> stringResource(Res.string.settingscat_services)
        TimestampServer -> stringResource(Res.string.settingscat_timestamp_server)
        OcspCrl -> stringResource(Res.string.settingscat_ocsp_crl)
        Validation -> stringResource(Res.string.settingscat_validation)
        ValidationPolicy -> stringResource(Res.string.settingscat_validation_policy)
        AlgorithmConstraints -> stringResource(Res.string.settingscat_algorithm_constraints)
        TrustedCertificates -> stringResource(Res.string.settingscat_trusted_certificates)
        CustomTrustedLists -> stringResource(Res.string.settingscat_custom_trusted_lists)
        Archiving -> stringResource(Res.string.settingscat_archiving)
        RenewalJobs -> stringResource(Res.string.settingscat_renewal_jobs)
        Scheduler -> stringResource(Res.string.settingscat_scheduler)
        Tokens -> stringResource(Res.string.settingscat_tokens)
        Pkcs11Libraries -> stringResource(Res.string.settingscat_pkcs11_libraries)
        Backup -> stringResource(Res.string.settingscat_backup)
        ConfigBackup -> stringResource(Res.string.settingscat_config_backup)
        Appearance -> stringResource(Res.string.settingscat_appearance)
        WindowTitleBar -> stringResource(Res.string.settingscat_window)
    }

    /** Brief explanation shown at the top of the content panel when this category is selected. */
    @Composable
    fun description(): String = when (this) {
        Signing -> stringResource(Res.string.settingscat_signing_desc)
        SigningDefaults -> stringResource(Res.string.settingscat_defaults_desc)
        DisabledAlgorithms -> stringResource(Res.string.settingscat_disabled_algorithms_desc)
        Services -> stringResource(Res.string.settingscat_services_desc)
        TimestampServer -> stringResource(Res.string.settingscat_timestamp_server_desc)
        OcspCrl -> stringResource(Res.string.settingscat_ocsp_crl_desc)
        Validation -> stringResource(Res.string.settingscat_validation_desc)
        ValidationPolicy -> stringResource(Res.string.settingscat_validation_policy_desc)
        AlgorithmConstraints -> stringResource(Res.string.settingscat_algorithm_constraints_desc)
        TrustedCertificates -> stringResource(Res.string.settingscat_trusted_certificates_desc)
        CustomTrustedLists -> stringResource(Res.string.settingscat_custom_trusted_lists_desc)
        Archiving -> stringResource(Res.string.settingscat_archiving_desc)
        RenewalJobs -> stringResource(Res.string.settingscat_renewal_jobs_desc)
        Scheduler -> stringResource(Res.string.settingscat_scheduler_desc)
        Tokens -> stringResource(Res.string.settingscat_tokens_desc)
        Pkcs11Libraries -> stringResource(Res.string.settingscat_pkcs11_libraries_desc)
        Backup -> stringResource(Res.string.settingscat_backup_desc)
        ConfigBackup -> stringResource(Res.string.settingscat_config_backup_desc)
        Appearance -> stringResource(Res.string.settingscat_appearance_desc)
        WindowTitleBar -> stringResource(Res.string.settingscat_window_desc)
    }

    companion object {

        /** Top-level groups in display order. */
        val groups: List<SettingsCategory> = entries.filter { it.parent == null }
    }
}
