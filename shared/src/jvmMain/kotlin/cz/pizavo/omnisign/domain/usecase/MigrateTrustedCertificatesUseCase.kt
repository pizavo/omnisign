package cz.pizavo.omnisign.domain.usecase

import arrow.core.right
import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

/**
 * One-shot startup migration of legacy inline trusted certificates into the [TrustStore].
 *
 * Earlier versions stored directly-trusted certificates as Base64-encoded DER inside the
 * configuration's `validation.trustedCertificates`, globally and per profile. That field is now a
 * declarative reference list ([cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef]) and
 * the certificate material lives in the [TrustStore]. Because the live schema no longer carries the
 * bytes, this use case reads any remaining inline entries straight from the on-disk config JSON,
 * imports them into the store, and then clears the inline lists from the configuration.
 *
 * It is idempotent and cheap to run on every startup: once the file holds no inline entries it only
 * parses the file and returns. Each certificate is migrated independently — one whose Base64 cannot
 * be decoded, or which the store rejects, is logged and skipped. The store deduplicates by
 * fingerprint, so a re-run after a failed save is safe.
 *
 * @property configRepository Loads and saves the live configuration when clearing the inline lists.
 * @property trustStore Destination store the inline certificates are imported into.
 * @property configPath On-disk config file read for the legacy inline bytes; defaults to the
 *   desktop/CLI config location.
 */
class MigrateTrustedCertificatesUseCase(
	private val configRepository: ConfigRepository,
	private val trustStore: TrustStore,
	private val configPath: Path = FileConfigRepository.getDefaultConfigPath(),
) {
	/**
	 * Migrate every inline trusted certificate found in the on-disk config into the [TrustStore]
	 * and clear the inline lists from the saved configuration.
	 *
	 * @return The number of certificates moved into the store, or a
	 *   [cz.pizavo.omnisign.domain.model.error.ConfigurationError] if persisting the cleared
	 *   configuration fails.
	 */
	suspend operator fun invoke(): OperationResult<Int> {
		val inlineByScope = readLegacyInlineCerts()
		if (inlineByScope.isEmpty()) return 0.right()

		var migrated = 0
		for ((scope, certs) in inlineByScope) {
			for (cert in certs) {
				val der = runCatching { Base64.getDecoder().decode(cert.base64) }.getOrElse {
					logger.warn(it) { "Skipping legacy trusted certificate '${cert.name}' in $scope: invalid Base64" }
					continue
				}
				trustStore.add(scope, der, cert.type, source = MIGRATION_SOURCE).fold(
					ifLeft = {
						logger.warn { "Skipping legacy trusted certificate '${cert.name}' in $scope: ${it.message}" }
					},
					ifRight = {
						migrated++
						logger.info { "Migrated legacy trusted certificate '${cert.name}' into the trust store ($scope)" }
					},
				)
			}
		}

		return clearInlineCerts().map { migrated }
	}

	/**
	 * Parse [configPath] and extract the legacy inline certificates per scope, tolerating a missing
	 * or unparseable file (treated as nothing to migrate).
	 */
	private fun readLegacyInlineCerts(): Map<TrustScope, List<LegacyInlineCert>> {
		if (!configPath.exists()) return emptyMap()
		val root = runCatching { Json.parseToJsonElement(configPath.readText()) as? JsonObject }.getOrElse {
			logger.warn(it) { "Could not parse $configPath for legacy trusted-certificate migration; skipping" }
			null
		} ?: return emptyMap()

		val result = LinkedHashMap<TrustScope, List<LegacyInlineCert>>()
		extractCerts((root["global"] as? JsonObject)?.get("validation") as? JsonObject)
			.takeIf { it.isNotEmpty() }?.let { result[TrustScope.Global] = it }
		(root["profiles"] as? JsonObject)?.forEach { (name, profile) ->
			extractCerts((profile as? JsonObject)?.get("validation") as? JsonObject)
				.takeIf { it.isNotEmpty() }?.let { result[TrustScope.Profile(name)] = it }
		}
		return result
	}

	/**
	 * Read the legacy `trustedCertificates` array out of a `validation` object, keeping only
	 * entries that carry a Base64 cert and a recognized type.
	 */
	private fun extractCerts(validation: JsonObject?): List<LegacyInlineCert> =
		(validation?.get("trustedCertificates") as? JsonArray).orEmpty().mapNotNull { element ->
			val obj = element as? JsonObject ?: return@mapNotNull null
			val base64 = (obj["certificateBase64"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
			val typeName = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
			val type = runCatching { TrustedCertificateType.valueOf(typeName) }.getOrNull() ?: return@mapNotNull null
			val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "<unnamed>"
			LegacyInlineCert(name, type, base64)
		}

	/**
	 * Clear the inline `trustedCertificates` lists from the saved configuration (global and every
	 * profile), leaving the rest of the configuration untouched.
	 */
	private suspend fun clearInlineCerts(): OperationResult<Unit> {
		val config = configRepository.getCurrentConfig()
		val cleared = config.copy(
			global = config.global.copy(
				validation = config.global.validation.copy(trustedCertificates = emptyList()),
			),
			profiles = config.profiles.mapValues { (_, profile) ->
				val validation = profile.validation ?: return@mapValues profile
				profile.copy(validation = validation.copy(trustedCertificates = emptyList()))
			},
		)
		return configRepository.saveConfig(cleared)
	}

	/**
	 * A single legacy inline certificate read from the on-disk config.
	 */
	private data class LegacyInlineCert(
		val name: String,
		val type: TrustedCertificateType,
		val base64: String,
	)

	companion object {
		/**
		 * Provenance recorded in the trust-store index for certificates moved from inline config.
		 */
		private const val MIGRATION_SOURCE = "inline"
	}
}
