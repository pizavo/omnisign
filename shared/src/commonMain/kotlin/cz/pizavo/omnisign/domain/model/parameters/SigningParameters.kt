package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.value.Sensitive

/**
 * Parameters for a signing operation.
 *
 * Carries the document as in-memory bytes so the same parameter shape works for the JVM
 * in-process signer (which wraps them in a DSS `InMemoryDocument`) and for the web target's
 * `RemoteSigningRepository` (which POSTs them as the `file` multipart field). Configuration
 * is described two ways so each impl can pick what it needs: the JVM in-process flow passes
 * a pre-resolved [resolvedConfig], whereas the web flow leaves [resolvedConfig] null and
 * instead sends the [profileName] plus per-request [disabledHashAlgorithms] /
 * [disabledEncryptionAlgorithms] overrides so the server can resolve against its own
 * configuration.
 *
 * @property inputBytes Raw PDF bytes to sign.
 * @property inputName File name attached to the document (used as the DSS document name on
 *   JVM and as the multipart `filename=` on web).
 * @property certificateAlias Alias identifying which certificate to use; null selects the first
 *   available on JVM, or the server default on web.
 * @property certificateSlotId For a PKCS#11 selection, the slot the chosen certificate lives in,
 *   so signing pins to the slot holding its private key on cards that present several
 *   token-present slots; null lets sign-time resolution locate the slot by [certificateAlias].
 *   JVM-only; the web target ignores this field.
 * @property keystoreFile Absolute path to a PKCS#12 (`.p12`/`.pfx`) keystore to sign with, instead
 *   of a discovered token. When set, [certificateAlias] selects *within* the keystore (omit to use
 *   its sole key). JVM/CLI only; the web target rejects it.
 * @property keystorePassword In-memory password for [keystoreFile] when supplied non-interactively;
 *   null lets the JVM signer prompt interactively when the keystore is password-protected. A
 *   [Sensitive], so it is never persisted or serialized.
 * @property hashAlgorithm Hash algorithm for the signature digest; falls back to the resolved
 *   config default.
 * @property encryptionAlgorithm Encryption (signing key) algorithm override; null lets DSS
 *   infer from the certificate key type.
 * @property signatureLevel PAdES level for the signature; falls back to the resolved config
 *   default.
 * @property reason Optional reason for signing embedded in the PDF signature dictionary.
 * @property location Optional signing location embedded in the PDF signature dictionary.
 * @property contactInfo Optional contact information embedded in the PDF signature dictionary.
 * @property addTimestamp Whether to include an RFC 3161 timestamp in the signature.
 * @property visibleSignature Optional visible signature appearance parameters. JVM-only;
 *   the web target ignores this field.
 * @property resolvedConfig Pre-resolved configuration for the JVM in-process flow. The web
 *   target ignores this field and resolves server-side from [profileName] and the per-request
 *   override sets below.
 * @property profileName Optional name of a server-side configuration profile to apply. Used by
 *   the web target's `RemoteSigningRepository` to drive server-side resolution; JVM impls
 *   ignore it when [resolvedConfig] is already populated.
 * @property disabledHashAlgorithms Per-request strictly-tightening overrides for the hash
 *   algorithms the operation refuses. Sent as the `disableHashAlgorithm` multipart field by
 *   the web target.
 * @property disabledEncryptionAlgorithms Per-request strictly-tightening overrides for the
 *   encryption algorithms the operation refuses. Sent as the `disableEncryptionAlgorithm`
 *   multipart field by the web target.
 */
data class SigningParameters(
	val inputBytes: ByteArray,
	val inputName: String,
	val certificateAlias: String? = null,
	val certificateSlotId: Long? = null,
	val keystoreFile: String? = null,
	val keystorePassword: Sensitive<String>? = null,
	val hashAlgorithm: HashAlgorithm? = null,
	val encryptionAlgorithm: EncryptionAlgorithm? = null,
	val signatureLevel: SignatureLevel? = null,
	val reason: String? = null,
	val location: String? = null,
	val contactInfo: String? = null,
	val addTimestamp: Boolean = true,
	val visibleSignature: VisibleSignatureParameters? = null,
	val resolvedConfig: ResolvedConfig? = null,
	val profileName: String? = null,
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is SigningParameters) return false
		return inputName == other.inputName &&
			inputBytes.contentEquals(other.inputBytes) &&
			certificateAlias == other.certificateAlias &&
			certificateSlotId == other.certificateSlotId &&
			keystoreFile == other.keystoreFile &&
			keystorePassword == other.keystorePassword &&
			hashAlgorithm == other.hashAlgorithm &&
			encryptionAlgorithm == other.encryptionAlgorithm &&
			signatureLevel == other.signatureLevel &&
			reason == other.reason &&
			location == other.location &&
			contactInfo == other.contactInfo &&
			addTimestamp == other.addTimestamp &&
			visibleSignature == other.visibleSignature &&
			resolvedConfig == other.resolvedConfig &&
			profileName == other.profileName &&
			disabledHashAlgorithms == other.disabledHashAlgorithms &&
			disabledEncryptionAlgorithms == other.disabledEncryptionAlgorithms
	}

	override fun hashCode(): Int {
		var result = inputBytes.contentHashCode()
		result = 31 * result + inputName.hashCode()
		result = 31 * result + (certificateAlias?.hashCode() ?: 0)
		result = 31 * result + (certificateSlotId?.hashCode() ?: 0)
		result = 31 * result + (keystoreFile?.hashCode() ?: 0)
		result = 31 * result + (keystorePassword?.hashCode() ?: 0)
		result = 31 * result + (hashAlgorithm?.hashCode() ?: 0)
		result = 31 * result + (encryptionAlgorithm?.hashCode() ?: 0)
		result = 31 * result + (signatureLevel?.hashCode() ?: 0)
		result = 31 * result + (reason?.hashCode() ?: 0)
		result = 31 * result + (location?.hashCode() ?: 0)
		result = 31 * result + (contactInfo?.hashCode() ?: 0)
		result = 31 * result + addTimestamp.hashCode()
		result = 31 * result + (visibleSignature?.hashCode() ?: 0)
		result = 31 * result + (resolvedConfig?.hashCode() ?: 0)
		result = 31 * result + (profileName?.hashCode() ?: 0)
		result = 31 * result + disabledHashAlgorithms.hashCode()
		result = 31 * result + disabledEncryptionAlgorithms.hashCode()
		return result
	}
}

