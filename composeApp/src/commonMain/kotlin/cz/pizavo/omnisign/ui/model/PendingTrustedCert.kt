package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import kotlin.time.Instant

/**
 * A trusted-certificate addition staged in an edit form but not yet written to the trust store.
 *
 * Held in [GlobalConfigEditState]/[ProfileEditState] until the user saves, so the addition can be
 * reviewed and discarded on Cancel alongside the rest of the form, exactly like a staged custom
 * trusted list. The certificate is parsed when it is staged (via
 * [cz.pizavo.omnisign.domain.repository.TrustStore.inspect]) so the row can show its subject and
 * expiry and so duplicates can be detected by [fingerprint]; the raw [bytes] are carried verbatim
 * and imported on save.
 *
 * Identity is by reference; instances are created once when staged and carried unchanged through
 * edit-state copies, so the default reference equality is sufficient for change detection.
 *
 * @property source The path the certificate was read from, recorded as provenance on save.
 * @property type The trust role to grant when the addition is applied.
 * @property bytes The raw certificate file content (PEM or DER) to import on save.
 * @property fingerprint The certificate's algorithm-prefixed SHA-256 fingerprint, used to detect duplicates.
 * @property subjectDN The certificate subject distinguished name, shown in the staged row.
 * @property notAfter End of the certificate validity period, shown in the staged row.
 */
class PendingTrustedCert(
    val source: String,
    val type: TrustedCertificateType,
    val bytes: ByteArray,
    val fingerprint: String,
    val subjectDN: String,
    val notAfter: Instant,
)
