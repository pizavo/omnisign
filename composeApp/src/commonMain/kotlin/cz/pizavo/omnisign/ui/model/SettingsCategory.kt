package cz.pizavo.omnisign.ui.model

/**
 * Identifies a settings category displayed in the left navigation of the settings dialog.
 *
 * Categories are organized into top-level groups that may contain children.
 * A group entry acts as a header and selects the first child when clicked,
 * while a leaf entry shows its own content panel.
 *
 * @property label Human-readable name displayed in the navigation sidebar.
 * @property description Brief explanation shown at the top of the content panel when this category is selected.
 * @property parent The parent group this category belongs to, or `null` for top-level groups.
 */
enum class SettingsCategory(
    val label: String,
    val description: String,
    val parent: SettingsCategory? = null,
) {
    /** Top-level group for signing-related settings. */
    Signing(
        label = "Signing",
        description = "Configure default signing algorithms, signature level, and globally disabled algorithms.",
    ),

    /** Default hash algorithm, encryption algorithm, and signature level. */
    SigningDefaults(
        label = "Defaults",
        description = "Default hash algorithm, encryption algorithm, and PAdES signature level used when no profile override is active.",
        parent = Signing,
    ),

    /** Globally disabled hash and encryption algorithms. */
    DisabledAlgorithms(
        label = "Disabled Algorithms",
        description = "Algorithms disabled here cannot be selected at any level — profiles and operations that reference a disabled algorithm are rejected during config resolution.",
        parent = Signing,
    ),

    /** Top-level group for external service configuration. */
    Services(
        label = "Services",
        description = "Configure connections to external services used during signing and validation.",
    ),

    /** Timestamp server (TSA) configuration. */
    TimestampServer(
        label = "Timestamp Server",
        description = "RFC 3161 Timestamp Authority used to add trusted timestamps to signatures (required for B-T and above).",
        parent = Services,
    ),

    /** OCSP and CRL timeout settings. */
    OcspCrl(
        label = "OCSP & CRL",
        description = "Connection timeouts for Online Certificate Status Protocol and Certificate Revocation List requests.",
        parent = Services,
    ),

    /** Top-level group for validation configuration. */
    Validation(
        label = "Validation",
        description = "Configure document validation policy, trust sources, and algorithm constraint handling.",
    ),

    /** Validation policy and trust source settings. */
    ValidationPolicy(
        label = "Policy & Trust",
        description = "Validation policy source, certificate revocation checking, and EU List of Trusted Lists integration.",
        parent = Validation,
    ),

    /** Algorithm constraint levels for validation. */
    AlgorithmConstraints(
        label = "Algorithm Constraints",
        description = "Control how the validator reacts when a cryptographic algorithm has passed its expiration date.",
        parent = Validation,
    ),

    /** Directly trusted CA and TSA certificates. */
    TrustedCertificates(
        label = "Trusted Certificates",
        description = "Directly trusted CA and TSA certificates stored inline. These are wired into DSS alongside any ETSI trusted lists, without requiring an XML document.",
        parent = Validation,
    ),

    /** Custom external ETSI Trusted List sources. */
    CustomTrustedLists(
        label = "Trusted Lists",
        description = "Register external ETSI TS 119612 Trusted List XML sources. Each entry may be an HTTPS URL or a local file path. An optional signing certificate verifies the TL's XML signature — strongly recommended for non-EU lists.",
        parent = Validation,
    ),

    /** Top-level group for archival renewal configuration. */
    Archiving(
        label = "Archiving",
        description = "Configure automatic archival renewal of B-LTA documents.",
    ),

    /** Named renewal jobs for automatic B-LTA re-timestamping. */
    RenewalJobs(
        label = "Renewal Jobs",
        description = "Named jobs that automatically re-timestamp B-LTA PDFs when their archival timestamp nears expiry. Each job defines glob patterns for the files to watch and a renewal buffer in days.",
        parent = Archiving,
    ),

    /** OS-level daily scheduler for running renewal jobs automatically. */
    Scheduler(
        label = "Scheduler",
        description = "Configure the OS-level daily scheduler that runs renewal jobs automatically via cron (Linux/macOS) or Task Scheduler (Windows).",
        parent = Archiving,
    ),

    /** Top-level group for token/hardware configuration. */
    Tokens(
        label = "Tokens",
        description = "Configure hardware token and smart card middleware discovery.",
    ),

    /** User-registered PKCS#11 middleware libraries. */
    Pkcs11Libraries(
        label = "PKCS#11 Libraries",
        description = "Register custom PKCS#11 middleware libraries that are not discovered automatically by the OS.",
        parent = Tokens,
    ),

    /** Top-level group for desktop appearance settings (Linux only). */
    Appearance(
        label = "Appearance",
        description = "Desktop appearance and window decoration settings.",
    ),

    /** Window title bar mode (native vs. merged custom toolbar). */
    WindowTitleBar(
        label = "Window",
        description = "Choose whether the toolbar is merged into the title bar area (custom CSD) or displayed below the native OS title bar. Changing this setting requires an application restart.",
        parent = Appearance,
    );

    /** Whether this category is a top-level group (has children). */
    val isGroup: Boolean get() = entries.any { it.parent == this }

    /** Direct child categories of this group. */
    val children: List<SettingsCategory> get() = entries.filter { it.parent == this }

    companion object {

        /** Top-level groups in display order. */
        val groups: List<SettingsCategory> = entries.filter { it.parent == null }
    }
}

