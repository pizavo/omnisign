package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * A declarative reference to a directly-trusted certificate, used by the server to provision its
 * trust store from `signing.yml` at boot. Desktop and CLI author the store interactively and leave
 * this list empty.
 *
 * Exactly one of [path] or [inline] supplies the certificate bytes: [path] is authoritative and
 * preferred, [inline] is a self-contained import vector for single-file deployments. The boot-time
 * reconcile copies the bytes into the content-addressed trust directory, so the source file may be
 * deleted afterward.
 *
 * @property path Filesystem path to a PEM or DER certificate, resolved relative to the policy file.
 *   Authoritative; mutually exclusive with [inline].
 * @property inline Base64-encoded DER certificate; mutually exclusive with [path].
 * @property type Trust role granted in this scope (CA, TSA, or ANY). Required.
 * @property fingerprint Optional integrity pin, algorithm-prefixed like the stored file
 *   (`sha256-<hex>`). When present and the source is readable, a computed mismatch fails startup.
 */
@Serializable
data class TrustedCertificateRef(
	val path: String? = null,
	val inline: String? = null,
	val type: TrustedCertificateType,
	val fingerprint: String? = null,
)
