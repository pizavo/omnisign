package cz.pizavo.omnisign.config

/**
 * Operation-gating configuration: which API operations the server exposes and which
 * certificate aliases are eligible when signing is enabled.
 *
 * Lives under `operations:` (rather than `allowedOperations:` / `allowedCertificateAliases:`
 * at the root) because the two fields are tightly coupled — [certificateAliases] only
 * matters when [AllowedOperation.SIGN] is in [allowed], and grouping them in one block
 * makes that coupling visible in the schema. Matches the nesting pattern of
 * [TlsConfig], [ProxyConfig], [CorsConfig], and [AuthConfig].
 *
 * @property allowed Set of operations the server exposes. Defaults to
 *   `setOf(`[AllowedOperation.VALIDATE]`)` — the only operation that exposes no
 *   server-side signing material or timestamping endpoint to API callers and is therefore
 *   safe to enable by default. [AllowedOperation.SIGN] and [AllowedOperation.TIMESTAMP]
 *   are opt-in for institutional deployments where the server holds an HSM/seal
 *   certificate or runs an institutional TSA proxy.
 * @property certificateAliases When non-null, only these certificate aliases may be used
 *   for signing via the API. Provides defense-in-depth so that personal certificates
 *   installed on the server are never accidentally exposed. When `null` and
 *   [AllowedOperation.SIGN] is in [allowed], all discovered signing certificates are
 *   available. An explicitly empty list while SIGN is enabled is rejected at startup by
 *   [validateOperationsConfig] — it would permit no certificate at all. Meaningless when
 *   [AllowedOperation.SIGN] is not in [allowed].
 * @property signingKeystorePath Absolute path to a PKCS#12 (`.p12`/`.pfx`) keystore the server
 *   signs from, for deployments that hold their signing key as a file rather than in a PKCS#11
 *   token/HSM. When set (and [AllowedOperation.SIGN] is enabled) every sign request signs with
 *   this keystore; [certificateAliases] then selects the entry within it (a request omitting the
 *   alias uses the keystore's sole key). The keystore password is supplied from the environment
 *   via `OMNISIGN_SIGNING_KEYSTORE_PASSWORD` (never in YAML), mirroring the TLS keystore. When
 *   `null`, signing falls back to PKCS#11 token discovery, so an HSM-backed deployment leaves this
 *   unset. When set, the keystore's certificate(s) are also surfaced through
 *   `GET /api/v1/certificates` alongside any discovered tokens, so a remote client (e.g. the web
 *   app) can select this signing identity. Meaningless when [AllowedOperation.SIGN] is not in
 *   [allowed]; a path set without SIGN is rejected at startup by [validateOperationsConfig].
 */
data class OperationsConfig(
	val allowed: Set<AllowedOperation> = setOf(AllowedOperation.VALIDATE),
	val certificateAliases: List<String>? = null,
	val signingKeystorePath: String? = null,
)
