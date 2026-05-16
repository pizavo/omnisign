package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate

/**
 * "Route A" experiment: try to enumerate a PKCS#11 token's certificates through a
 * SunPKCS#11 [KeyStore] **without** sending the PIN (`KeyStore.load(null, null)`, which
 * opens a public session and never calls `C_Login`).
 *
 * The point is to learn, against a real token, whether the signing certificate is a
 * public object readable before authentication.  If it is, OmniSign could list
 * certificates the way Adobe does — no PIN to look, PIN spent only on the signature —
 * instead of routing enumeration through the PIN-protected DSS `Pkcs11SignatureToken`.
 *
 * This deliberately loads SunPKCS#11 **in-process**, which is exactly what a production
 * Route A would do; it does not introduce a second native PKCS#11 consumer, so it does
 * not regress the cohabitation contract that made SunPKCS#11 the sole in-process
 * consumer.  A grossly broken middleware could still hard-crash the JVM on `load`
 * (the reason production discovery probes out-of-process); for an explicit, user-invoked
 * diagnostic against a known-good token that risk is accepted.  Everything catchable is
 * caught — the probe never throws and never blocks on input.
 *
 * Stateless; safe to register as a Koin singleton and call concurrently for distinct
 * tokens (each call builds its own provider instance and never mutates global
 * [Security] state).
 */
class Pkcs11NoLoginCertProbe {

	/**
	 * Configure a SunPKCS#11 provider for [libraryPath] (pinned to [slotId] when known)
	 * and enumerate its [KeyStore] aliases without authenticating.
	 *
	 * @param tokenName Display name of the token, used only for the provider name and logs.
	 * @param libraryPath Absolute path to the PKCS#11 module.
	 * @param slotId Slot to pin the provider to, or `null` to use SunPKCS#11's default
	 *   slot selection.
	 * @return A populated [Pkcs11DiagnosticsReport.NoLoginEnumeration]; `loaded = false`
	 *   with a reason in `error` when the no-login path is not viable on this token.
	 */
	fun enumerate(
		tokenName: String,
		libraryPath: String,
		slotId: Long?,
	): Pkcs11DiagnosticsReport.NoLoginEnumeration {
		fun notLoaded(reason: String) = Pkcs11DiagnosticsReport.NoLoginEnumeration(
			tokenName = tokenName,
			libraryPath = libraryPath,
			slotId = slotId,
			loaded = false,
			error = reason,
			entries = emptyList(),
		)

		if (!File(libraryPath).isFile) return notLoaded("library not found: $libraryPath")

		return try {
			val base = Security.getProvider("SunPKCS11")
				?: return notLoaded("SunPKCS11 provider unavailable in this JDK")
			val provider = base.configure(buildInlineConfig(tokenName, libraryPath, slotId))
			val keyStore = KeyStore.getInstance("PKCS11", provider)
			keyStore.load(null, null)

			val entries = keyStore.aliases().toList().map { alias ->
				val cert = runCatching { keyStore.getCertificate(alias) }.getOrNull() as? X509Certificate
				Pkcs11DiagnosticsReport.NoLoginEntry(
					alias = alias,
					isKeyEntry = runCatching { keyStore.isKeyEntry(alias) }.getOrDefault(false),
					isCertificateEntry = runCatching { keyStore.isCertificateEntry(alias) }.getOrDefault(false),
					subjectDN = cert?.subjectX500Principal?.name,
					issuerDN = cert?.issuerX500Principal?.name,
					serialNumber = cert?.serialNumber?.toString(),
				)
			}
			Pkcs11DiagnosticsReport.NoLoginEnumeration(
				tokenName = tokenName,
				libraryPath = libraryPath,
				slotId = slotId,
				loaded = true,
				error = null,
				entries = entries,
			)
		} catch (t: Throwable) {
			logger.debug(t) { "No-login PKCS#11 enumeration failed for '$tokenName' ($libraryPath)" }
			notLoaded(describeFailure(t))
		}
	}

	/**
	 * Flatten a throwable's cause chain into a single readable string.
	 *
	 * SunPKCS#11's `KeyStore` rethrows the underlying PKCS#11 failure as a generic
	 * `IOException("load failed", cause)`, so the actionable detail (the
	 * `sun.security.pkcs11.wrapper.PKCS11Exception` and its `CKR_*` code) is only in
	 * the cause.  The chain is bounded to guard against self-referential causes.
	 */
	private fun describeFailure(t: Throwable): String =
		generateSequence<Throwable>(t) { it.cause?.takeIf { c -> c !== it } }
			.take(MAX_CAUSE_DEPTH)
			.joinToString(" ← ") { e ->
				val type = e::class.simpleName ?: e::class.java.name
				val message = e.message?.trim()?.ifBlank { null } ?: "(no message)"
				"$type: $message"
			}

	/**
	 * Build the SunPKCS#11 inline configuration string (leading `--` marks it as literal
	 * config text rather than a file path).  The provider name is salted with the library
	 * path hash so distinct tokens never collide on the SunPKCS#11 provider name.
	 */
	private fun buildInlineConfig(tokenName: String, libraryPath: String, slotId: Long?): String {
		val sanitized = tokenName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(MAX_NAME_CHARS)
		val salt = libraryPath.hashCode().toUInt().toString(RADIX_HEX)
		return buildString {
			append("--name=OmniSignNoLogin-").append(sanitized).append('-').append(salt).append('\n')
			append("library=").append(libraryPath).append('\n')
			if (slotId != null) append("slot=").append(slotId).append('\n')
		}
	}

	private companion object {
		val logger = KotlinLogging.logger {}
		const val MAX_NAME_CHARS = 40
		const val RADIX_HEX = 16
		const val MAX_CAUSE_DEPTH = 8
	}
}
