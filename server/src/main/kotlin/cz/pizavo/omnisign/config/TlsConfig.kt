package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.Sensitive

/**
 * TLS keystore configuration.
 *
 * When present and reverse-proxy mode is inactive ([ServerConfig.proxy] absent or
 * `proxy.enabled: false`), the server creates a TLS connector with TLS 1.2/1.3 and HTTP/2
 * ALPN negotiation. To restrict to TLS 1.3 only, pass
 * `-Djdk.tls.disabledAlgorithms=TLSv1,TLSv1.1,TLSv1.2` as a JVM argument.
 *
 * @property keystorePath Absolute path to the JKS or PKCS#12 keystore file.
 * @property keystorePassword Password protecting the keystore. Wrapped in [Sensitive] so
 *   it cannot leak through `toString` (data-class-generated, logger interpolation, status
 *   pages echoing `cause.message`).
 * @property keyAlias Alias of the private key entry inside the keystore.
 * @property privateKeyPassword Password for the private key entry; defaults to
 *   [keystorePassword]. Wrapped in [Sensitive] for the same reason as [keystorePassword].
 * @property hsts HTTP Strict Transport Security configuration. When non-null, the
 *   `Strict-Transport-Security` header is sent on every response. Nesting it here ensures
 *   HSTS is automatically disabled whenever the `tls:` block is removed.
 */
data class TlsConfig(
	val keystorePath: String,
	val keystorePassword: Sensitive<String>,
	val keyAlias: String = "omnisign",
	val privateKeyPassword: Sensitive<String> = keystorePassword,
	val hsts: HstsConfig? = null,
)

